package run.halo.aipagebuilder.page;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
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
                        // Preview scripts must not inherit the administrator's origin or issue
                        // Console API requests; only anonymous public APIs stay reachable.
                        response.header("Content-Security-Policy", previewPolicy(request));
                    }
                    return response.render(template, Map.of("aiPageBuilder", pageView,
                        "_templateId", "plugin:ai-page-builder:" + templateName));
                });
        });
    }

    private String previewPolicy(ServerRequest request) {
        var sources = publicApiCatalog.publicPathPrefixes().stream()
            .map(prefix -> origin(request) + prefix)
            .distinct()
            .toList();
        return "sandbox allow-scripts; connect-src "
            + (sources.isEmpty() ? "'none'" : String.join(" ", sources))
            + "; frame-src 'none'; object-src 'none'; worker-src 'none'; form-action 'none'";
    }

    /**
     * Prefers the externally configured URL (external-url) so CSP sources match the origin
     * visitors actually use behind a reverse proxy; otherwise derives it from the request.
     */
    private String origin(ServerRequest request) {
        var url = externalUrlSupplier.getURL(request.exchange().getRequest());
        if (url.getAuthority() != null && !url.getAuthority().isBlank()) {
            return url.getProtocol() + "://" + url.getAuthority();
        }
        var uri = request.uri();
        var scheme = uri.getScheme();
        if (scheme == null) {
            var forwarded = request.headers().firstHeader("X-Forwarded-Proto");
            scheme = forwarded == null ? "http" : forwarded.split(",", 2)[0].trim();
        }
        var authority = uri.getRawAuthority();
        if (authority == null) authority = request.headers().firstHeader("Host");
        return scheme + "://" + authority;
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
