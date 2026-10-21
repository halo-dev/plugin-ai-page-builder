package run.halo.aipagebuilder.mcp;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.aipagebuilder.security.PageAccess;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.aipagebuilder.Json;
import run.halo.aipagebuilder.ai.DocumentHash;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.content.PageDocumentPatch;
import run.halo.aipagebuilder.content.PageSourceEdits;
import run.halo.aipagebuilder.page.PageApi;
import run.halo.aipagebuilder.page.PagePreviewStore;
import run.halo.aipagebuilder.page.PageService;
import run.halo.aipagebuilder.theme.ThemeContextService;
import run.halo.aipagebuilder.theme.ThemeStyleContext;
import run.halo.mcpserver.api.McpToolAnnotations;
import run.halo.mcpserver.api.McpToolDefinition;
import run.halo.mcpserver.api.McpToolException;
import run.halo.mcpserver.api.McpToolProvider;
import run.halo.mcpserver.api.McpToolResult;

/** Loaded only through PageMcpConfiguration when the optional API is available. */
public final class PageMcpToolProvider implements McpToolProvider {
    private final PageService pages;
    private final ContentPolicy policy;
    private final PageSourceEdits sourceEdits;
    private final ThemeContextService themes;
    private final PagePreviewStore previews;
    private final ExternalUrlSupplier urls;

    public PageMcpToolProvider(PageService pages, ContentPolicy policy, ThemeContextService themes,
        PagePreviewStore previews, ExternalUrlSupplier urls) {
        this.pages = pages;
        this.policy = policy;
        this.sourceEdits = new PageSourceEdits(policy);
        this.themes = themes;
        this.previews = previews;
        this.urls = urls;
    }

    @Override
    public Flux<McpToolDefinition> tools() {
        return Flux.fromIterable(PageMcpTools.definitions()).map(tool -> McpToolDefinition.builder()
            .name(tool.name()).title(tool.name()).description(tool.description())
            .displayTitle(tool.title()).displayDescription(tool.displayDescription())
                .category(tool.category())
            .inputSchema(tool.schema())
            .outputSchema(PageMcpOutputSchemas.forTool(tool.name()))
            .annotations(tool.readOnly() ? McpToolAnnotations.readOnly(tool.name())
                : new McpToolAnnotations(false, !List.of("create_page", "preview_page")
                    .contains(tool.name()), false, false, tool.name()))
            .permission(invocation -> actor().hasElement().onErrorReturn(false))
            .handler(invocation -> actor().flatMap(actor ->
                    execute(invocation.toolName(), invocation.arguments(), actor))
                .map(McpToolResult::success)
                .onErrorResume(ResponseStatusException.class,
                    error -> Mono.just(McpToolResult.error(
                    switch (error.getStatusCode().value()) {
                        case 400 -> "INVALID_ARGUMENT";
                        case 403 -> "FORBIDDEN";
                        case 404 -> "NOT_FOUND";
                        case 409 -> "CONFLICT";
                        default -> "INTERNAL_ERROR";
                    }, error.getStatusCode().is5xxServerError() ? "Page operation failed"
                        : Objects.requireNonNullElse(error.getReason(), "Page operation failed"))))
                .onErrorResume(IllegalArgumentException.class,
                    error -> Mono.just(McpToolResult.error("INVALID_ARGUMENT",
                        error.getMessage()))))
            .build());
    }

    private static Mono<String> actor() {
        return PageAccess.superAdministrator()
            .switchIfEmpty(Mono.error(new McpToolException("FORBIDDEN",
                "Super administrator access is required")));
    }

    private Mono<Map<String, Object>> execute(String tool, Map<String, Object> args, String actor) {
        return Mono.defer(() -> switch (tool) {
            case "get_page_capabilities" -> Mono.just(policy.capabilities());
            case "get_theme_context" -> (args.containsKey("name")
                ? pages.get(string(args, "name"))
                    .flatMap(detail -> theme(detail.page().themeLayout()))
                : themes.current()).map(PageMcpToolProvider::map);
            case "list_pages" -> pages.list(integer(args, "page", 1), integer(args, "size", 20),
                (String) args.get("keyword"), (String) args.get("status"),
                (String) args.get("sort"))
                .map(PageMcpToolProvider::map);
            case "get_page" -> read(args);
            case "create_page" -> pages.create(new PageApi.CreatePageRequest(string(args, "slug"),
                string(args, "title"), (Boolean) args.getOrDefault("themeLayout", true)), actor)
                .map(this::summary);
            case "update_page_properties" -> pages.updateProperties(string(args, "name"),
                new PageApi.UpdatePagePropertiesRequest(version(args), (String) args.get("title"),
                    (String) args.get("description"), (String) args.get("slug"),
                    (Boolean) args.get("themeLayout"), null, null)).map(this::summary);
            case "update_page", "patch_page" -> edit(args, actor, tool.equals("patch_page"));
            case "preview_page" -> preview(args, actor);
            case "publish_page" -> pages.publish(string(args, "name"), version(args),
                string(args, "expectedDraftRevision")).map(this::summary);
            case "unpublish_page" -> pages.unpublish(string(args, "name"), version(args))
                .map(this::summary);
            case "list_page_revisions" -> pages.revisions(string(args, "name"),
                integer(args, "page", 1), integer(args, "size", 20)).map(PageMcpToolProvider::map);
            case "restore_page_revision" -> pages.restore(string(args, "name"),
                new PageApi.RestoreRequest(version(args), string(args, "revision")), actor)
                .map(this::summary);
            default -> Mono.error(new IllegalArgumentException("Unknown page tool"));
        });
    }

    private Mono<Map<String, Object>> read(Map<String, Object> args) {
        var name = string(args, "name");
        var target = (String) args.getOrDefault("target", "draft");
        if (!List.of("draft", "published", "revision").contains(target)
            || !target.equals("revision") && args.containsKey("revision")) {
            throw new IllegalArgumentException("revision is only valid for target=revision");
        }
        return pages.get(name).flatMap(current -> {
            String revision = switch (target) {
                case "draft" -> current.page().draftRevision();
                case "published" -> current.page().publishedRevision();
                default -> string(args, "revision");
            };
            if (revision == null) return Mono.error(new
                ResponseStatusException(HttpStatus.NOT_FOUND,
                "Page has no published revision"));
            var document = target.equals("draft") ? Mono.just(current.document())
                : pages.revisionDetail(name, revision).map(PageApi.RevisionDetail::document);
            return document.map(value -> {
                var result = summary(current);
                result.put("target", target);
                result.put("revision", revision);
                result.put("mode", value.mode());
                var source = new LinkedHashMap<String, Object>();
                source.put("html", value.html());
                source.put("css", value.css());
                source.put("js", value.js());
                result.put("document", source);
                result.put("baseHash", DocumentHash.of(value.html(), value.css(), value.js()));
                return result;
            });
        });
    }

    private Mono<Map<String, Object>> edit(Map<String, Object> args, String actor, boolean patch) {
        var name = string(args, "name");
        var version = version(args);
        return pages.get(name).flatMap(current -> {
            checkVersion(current, version);
            var original = current.document();
            Map<String, ?> fields;
            if (patch) {
                if (!DocumentHash.of(original.html(), original.css(), original.js())
                    .equals(string(args, "baseHash"))) throw conflict();
                fields = PageDocumentPatch.apply(original, args);
            } else {
                fields = args;
                if (List.of("html", "css", "js").stream()
                    .noneMatch(args::containsKey)) {
                    throw new IllegalArgumentException("Provide at least one document field");
                }
            }
            var html = field(fields, "html", original.html());
            var css = field(fields, "css", original.css());
            var js = field(fields, "js", original.js());
            var prepared = sourceEdits.prepare(original, html, css, js);
            var context = prepared.cssChanged() ? theme(current.page().themeLayout())
                : Mono.just(ThemeStyleContext.unavailable());
            return context.defaultIfEmpty(ThemeStyleContext.unavailable())
                .map(prepared::finish)
                .flatMap(candidate -> pages.save(name,
                    new PageApi.SavePageRequest(version, candidate, "ai"), actor))
                .map(this::summary);
        });
    }

    private Mono<ThemeStyleContext> theme(boolean layout) {
        return layout ? themes.current() : Mono.just(ThemeStyleContext.standalone());
    }

    private Mono<Map<String, Object>> preview(Map<String, Object> args, String actor) {
        var base = baseUrl();
        if (base == null) return Mono.error(new McpToolException("SITE_URL_UNAVAILABLE",
            "Configure Halo's absolute external URL before issuing a preview"));
        return pages.get(string(args, "name")).map(detail -> {
            checkVersion(detail, version(args));
            if (!detail.page().draftRevision().equals(string(args, "expectedDraftRevision"))) {
                throw conflict();
            }
            var ticket = previews.issue(detail.page(), actor);
            return Map.<String, Object>of("name", detail.page().name(),
                "version", detail.page().version(),
                "draftRevision", detail.page().draftRevision(), "previewUrl",
                base + PagePreviewStore.PATH + ticket.token(), "expiresAt",
                ticket.expiresAt().toString());
        });
    }

    private Map<String, Object> summary(PageApi.PageDetail detail) {
        var result = map(detail.page());
        result.put("mode", detail.document().mode());
        if (detail.page().slug() != null) {
            var path = "/" + detail.page().slug();
            result.put("publicPath", path);
            var base = baseUrl();
            if (base != null) result.put("publicUrl", base + path);
        }
        return result;
    }

    private String baseUrl() {
        var configured = urls.getRaw();
        if (configured == null) return null;
        var uri = URI.create(configured.toExternalForm());
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
            || uri.getUserInfo() != null || uri.getQuery() != null
            || uri.getFragment() != null) return null;
        return uri.toASCIIString().replaceAll("/+$", "");
    }

    private static void checkVersion(PageApi.PageDetail detail, Long version) {
        if (!Objects.equals(detail.page().version(), version)) throw conflict();
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
            "Page changed; reload before continuing");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return new LinkedHashMap<>(Json.MAPPER.convertValue(value, Map.class));
    }

    private static String field(Map<String, ?> fields, String key, String fallback) {
        if (!fields.containsKey(key)) return fallback;
        if (!(fields.get(key) instanceof String value)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return value;
    }

    private static String string(Map<String, Object> args, String key) {
        if (!(args.get(key) instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static Long version(Map<String, Object> args) {
        if (!(args.get("expectedVersion") instanceof Number value)) {
            throw new IllegalArgumentException("expectedVersion is required");
        }
        return new java.math.BigDecimal(value.toString()).longValueExact();
    }

    private static int integer(Map<String, Object> args, String key, int fallback) {
        return args.containsKey(key) ? new java.math.BigDecimal(args.get(key).toString())
            .intValueExact()
            : fallback;
    }
}
