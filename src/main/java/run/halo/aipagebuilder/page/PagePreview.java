package run.halo.aipagebuilder.page;

import java.net.URI;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RenderingResponse;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.aipagebuilder.ai.PublicApiCatalog;
import run.halo.aipagebuilder.model.PageDocument;

/** Owns preview isolation, including the request used during deferred template rendering. */
@Component
public class PagePreview {
    /** Public site documents that theme widgets fetch without exposing any API. */
    private static final List<String> PUBLIC_PATHS =
        List.of("/rss.xml", "/feed.xml", "/sitemap.xml", "/feed/");
    private final PageRenderer renderer;
    private final PublicApiCatalog publicApiCatalog;
    private final PageOrigins origins;

    public PagePreview(PageRenderer renderer, PublicApiCatalog publicApiCatalog,
        ExternalUrlSupplier externalUrlSupplier) {
        this.renderer = renderer;
        this.publicApiCatalog = publicApiCatalog;
        this.origins = new PageOrigins(externalUrlSupplier);
    }

    public Mono<ServerResponse> render(ServerRequest request, String name, String slug,
        boolean themeLayout, PageDocument document, String title, String description) {
        return renderer.render(request, name, slug, themeLayout, document, title, description)
            .flatMap(response -> RenderingResponse.from(response)
                .header("Content-Security-Policy", policy(request).document()).build())
            .cast(ServerResponse.class);
    }

    public Mono<ServerResponse> token(ServerRequest request, String name, String slug,
        boolean themeLayout, PageDocument document, String title, String description) {
        return Mono.defer(() -> {
            var safeRequest = PagePreviewResponse.withoutCredential(request);
            return renderer.render(safeRequest, name, slug, themeLayout, document, title, description)
                .flatMap(response -> {
                    var policy = policy(safeRequest);
                    var uri = safeRequest.uri();
                    return RenderingResponse.from(response)
                        .header("Content-Security-Policy", policy.host(uri)).build()
                        .map(host -> new PagePreviewResponse(host, uri, policy.child(uri)));
                });
        });
    }

    private Policy policy(ServerRequest request) {
        // Resources resolve against the origin actually serving the preview, which can
        // differ from the configured external URL used for public links.
        var base = origins.servingOrigin(request);
        // Public paths live below the context path, so every source must name it too.
        var origin = scopedOrigin(base, request.requestPath().contextPath().value());
        var paths = Stream.concat(allowedPrefixes().stream(), PUBLIC_PATHS.stream()).toList();
        var sources = origin == null ? List.<String>of()
            : paths.stream().map(prefix -> origin + prefix).distinct().toList();
        // A trailing slash also allows resources below a configured deployment sub-path.
        var resourceSources = base == null ? "'unsafe-inline'" : base + "/ 'unsafe-inline'";
        var connectSources = sources.isEmpty() ? "'none'" : String.join(" ", sources);
        // Theme runtimes may compile expressions. Connections remain limited to public paths.
        return new Policy("default-src 'none'; script-src " + resourceSources
            + " 'unsafe-eval'; style-src " + resourceSources
            + "; img-src * data: blob:; font-src * data:; media-src *"
            + "; connect-src " + connectSources);
    }

    /** Adds the deployment path the preview document is served under. */
    private static String scopedOrigin(String base, String contextPath) {
        if (base == null || contextPath.isEmpty()) return base;
        // The external URL fallback may already carry the configured path. Compare the
        // suffix as plain text so a malformed authority or path cannot throw here.
        var path = base.indexOf('/', base.indexOf("://") + 3);
        return contextPath.equals(path < 0 ? "" : base.substring(path)) ? base : base + contextPath;
    }

    private List<String> allowedPrefixes() {
        try {
            return publicApiCatalog.publicPathPrefixes();
        } catch (IllegalStateException error) {
            org.slf4j.LoggerFactory.getLogger(PagePreview.class)
                .warn("Unable to build the preview API allowlist; blocking API access", error);
            return List.of();
        }
    }

    private record Policy(String common) {
        String document() {
            return "sandbox allow-scripts; " + content("'none'", "'none'");
        }

        String host(URI uri) {
            return "sandbox allow-scripts; " + content(uri.toASCIIString(), "data:");
        }

        String child(URI uri) {
            // The iframe provides sandboxing; CSP meta does not support that directive.
            return content(uri.toASCIIString(), "'none'");
        }

        private String content(String base, String frames) {
            return common + "; base-uri " + base + "; frame-src " + frames
                + "; object-src 'none'; worker-src 'none'; form-action 'none'";
        }
    }
}
