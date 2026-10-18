package run.halo.aipagebuilder.page;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.app.theme.TemplateNameResolver;
import run.halo.aipagebuilder.ai.PublicApiCatalog;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;

@Component
public class PageRenderer {
    private static final Set<String> WEB_SCHEMES = Set.of("http", "https");
    private static final Pattern AUTHORITY = Pattern.compile("[A-Za-z0-9._\\-:\\[\\]]+");
    private final TemplateNameResolver templateNameResolver;
    private final ContentPolicy contentPolicy;
    private final PublicApiCatalog publicApiCatalog;
    private final ExternalUrlSupplier externalUrlSupplier;

    public PageRenderer(TemplateNameResolver templateNameResolver, ContentPolicy contentPolicy,
        PublicApiCatalog publicApiCatalog, ExternalUrlSupplier externalUrlSupplier) {
        this.templateNameResolver = templateNameResolver;
        this.contentPolicy = contentPolicy;
        this.publicApiCatalog = publicApiCatalog;
        this.externalUrlSupplier = externalUrlSupplier;
    }

    public Mono<ServerResponse> render(ServerRequest request, String name, String slug,
        boolean themeLayout, PageDocument document) {
        return render(request, name, slug, themeLayout, document, false, false);
    }

    public Mono<ServerResponse> render(ServerRequest request, String name, String slug,
        boolean themeLayout, PageDocument document, boolean disableScripts) {
        return render(request, name, slug, themeLayout, document, false, disableScripts);
    }

    public Mono<ServerResponse> preview(ServerRequest request, String name, String slug,
        boolean themeLayout, PageDocument document) {
        return render(request, name, slug, themeLayout, document, true, false);
    }

    private Mono<ServerResponse> render(ServerRequest request, String name, String slug,
        boolean themeLayout, PageDocument document, boolean preview, boolean disableScripts) {
        return Mono.defer(() -> {
            contentPolicy.validate(document);
            var rootId = "ai-page-builder-" + name;
            var scriptBlocked = disableScripts && !document.js().isEmpty();
            var safeHtml = contentPolicy.sanitizeHtml(document.html());
            var pageView = new PageView(slug, document.title(), document.description(), rootId,
                renderHtml(safeHtml, document.js(), disableScripts),
                contentPolicy.renderCss(document.css(), rootId), scriptBlocked);
            var templateName = themeLayout ? "ai-page-builder" : "ai-page-builder-standalone";
            return templateNameResolver.resolveTemplateNameOrDefault(request.exchange(), templateName)
                .flatMap(template -> {
                    var response = ServerResponse.ok().contentType(MediaType.TEXT_HTML)
                        .cacheControl(CacheControl.noStore());
                    if (preview) {
                        // Preview content runs in an opaque origin. CSP blocks Console API
                        // requests, external scripts/styles, frames, workers and form
                        // submissions; images, fonts and media stay unrestricted so imported
                        // pages keep their external assets.
                        response.header("Content-Security-Policy", previewPolicy(request));
                    }
                    return response.render(template, Map.of("aiPageBuilder", pageView,
                        "_templateId", "plugin:ai-page-builder:" + templateName));
                });
        });
    }

    private String previewPolicy(ServerRequest request) {
        var base = origin(request);
        var sources = base == null ? List.<String>of()
            : allowedPrefixes().stream().map(prefix -> base + prefix).distinct().toList();
        // Without a trailing slash a CSP source path matches that exact path only, so a
        // sub-path deployment such as /site would block /site/themes and /site/plugins.
        var resourceSources = base == null ? "'unsafe-inline'" : base + "/ 'unsafe-inline'";
        var connectSources = sources.isEmpty() ? "'none'" : String.join(" ", sources);
        // 'unsafe-eval' restores theme runtimes that compile expressions at runtime (Alpine,
        // for example). It adds no data access: page scripts are already arbitrary inline JS
        // and connect-src still limits what they can reach.
        return "sandbox allow-scripts; default-src 'none'; script-src " + resourceSources
            + " 'unsafe-eval'; style-src " + resourceSources
            + "; img-src * data: blob:; font-src * data:; media-src *"
            + "; connect-src " + connectSources
            + "; base-uri 'none'; frame-src 'none'; object-src 'none'; worker-src 'none'"
            + "; form-action 'none'";
    }

    private List<String> allowedPrefixes() {
        try {
            return publicApiCatalog.publicPathPrefixes();
        } catch (IllegalStateException error) {
            // A catalog refresh must not turn every preview into a 500 error page; fall back
            // to the most restrictive policy until the next request can build the allowlist.
            org.slf4j.LoggerFactory.getLogger(PageRenderer.class)
                .warn("Unable to build the preview API allowlist; blocking API access", error);
            return List.of();
        }
    }

    /**
     * Canonical public origin used for public links. Prefers the configured external URL so
     * visitors behind a reverse proxy see the address they actually use.
     */
    String origin(ServerRequest request) {
        var external = externalOrigin(request);
        return external != null ? external : requestOrigin(request);
    }

    private String externalOrigin(ServerRequest request) {
        java.net.URL url = null;
        try {
            url = externalUrlSupplier.getURL(request.exchange().getRequest());
        } catch (RuntimeException error) {
            org.slf4j.LoggerFactory.getLogger(PageRenderer.class)
                .debug("Unable to resolve the external URL; falling back to the request", error);
        }
        if (url == null) return null;
        var base = checkedOrigin(url.getProtocol(), url.getAuthority());
        if (base == null) return null;
        var path = url.getPath();
        if (path != null && !path.isBlank() && !"/".equals(path)) {
            // A configured path must not be able to inject another CSP directive.
            if (!path.matches("[A-Za-z0-9._~!$&'()*+=:@%/\\-]*")) return null;
            return base + (path.endsWith("/") ? path.substring(0, path.length() - 1) : path);
        }
        return base;
    }

    private static String requestOrigin(ServerRequest request) {
        var forwarded = request.headers().firstHeader("X-Forwarded-Proto");
        var scheme = forwarded != null ? forwarded.split(",", 2)[0].trim()
            : request.uri().getScheme();
        if (scheme == null || scheme.isBlank()) scheme = "http";
        var authority = request.uri().getRawAuthority();
        if (authority == null) authority = request.headers().firstHeader("Host");
        return checkedOrigin(scheme, authority);
    }

    /** Rejects values that would break out of the CSP source list. */
    private static String checkedOrigin(String scheme, String authority) {
        if (scheme == null || !WEB_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) return null;
        if (authority == null || !AUTHORITY.matcher(authority).matches()) return null;
        return scheme.toLowerCase(Locale.ROOT) + "://" + authority;
    }

    private static String renderHtml(String html, String js, boolean disableScripts) {
        if (js.isEmpty() || disableScripts) {
            return html;
        }
        // Encode source as data so script end tags cannot escape into the theme markup.
        var source = Base64.getEncoder().encodeToString(js.getBytes(StandardCharsets.UTF_8));
        return html + """
            <script>(() => {
              const script = document.createElement('script');
              script.textContent = new TextDecoder().decode(Uint8Array.from(atob('%s'), char => char.charCodeAt(0)));
              document.currentScript.parentNode.appendChild(script);
            })();</script>
            """.formatted(source);
    }

    /** Public template contract; html includes the body followed by its script bootstrap, if any. */
    public record PageView(String slug, String title, String description, String rootId,
        String html, String css, boolean scriptBlocked) {
    }
}
