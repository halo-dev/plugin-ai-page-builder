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
 * reference are cross-origin CORS requests. Those assets are public, so echo the null origin
 * back for them without granting the preview page same-origin access to the site.
 */
@Component
public class PreviewAssetCorsFilter implements AdditionalWebFilter {
    private static final String SANDBOXED_ORIGIN = "null";
    private static final List<String> ASSET_PREFIXES = List.of("/themes/", "/plugins/");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        var path = request.getPath().value();
        var sandboxed = SANDBOXED_ORIGIN.equals(request.getHeaders().getFirst(HttpHeaders.ORIGIN));
        if (sandboxed && ASSET_PREFIXES.stream().anyMatch(path::startsWith)) {
            var headers = exchange.getResponse().getHeaders();
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, SANDBOXED_ORIGIN);
            headers.add(HttpHeaders.VARY, HttpHeaders.ORIGIN);
        }
        return chain.filter(exchange);
    }
}
