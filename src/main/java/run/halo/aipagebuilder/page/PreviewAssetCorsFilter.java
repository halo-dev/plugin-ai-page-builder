package run.halo.aipagebuilder.page;

import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import run.halo.app.security.AdditionalWebFilter;

/**
 * Sandboxed previews run in an opaque origin, so the theme CSS, module scripts and fonts they
 * reference, as well as the feeds their widgets fetch, are cross-origin requests. Those
 * resources are public, so allow every origin without granting the preview page same-origin
 * access to the site. The response must not depend on the request Origin: shared caches ignore
 * {@code Vary: Origin}, and a cached no-CORS copy would keep breaking the preview.
 */
@Component
public class PreviewAssetCorsFilter implements AdditionalWebFilter {
    private static final List<String> ASSET_PREFIXES =
        List.of("/themes/", "/plugins/", "/feed/");
    private static final List<String> PUBLIC_FILES =
        List.of("/rss.xml", "/feed.xml", "/sitemap.xml");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (ASSET_PREFIXES.stream().anyMatch(path::startsWith) || PUBLIC_FILES.contains(path)) {
            exchange.getResponse().getHeaders()
                .set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        }
        return chain.filter(exchange);
    }
}
