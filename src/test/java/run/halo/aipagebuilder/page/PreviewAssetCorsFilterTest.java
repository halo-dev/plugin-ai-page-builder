package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class PreviewAssetCorsFilterTest {
    private final PreviewAssetCorsFilter filter = new PreviewAssetCorsFilter();
    private final AtomicBoolean chained = new AtomicBoolean();
    private final WebFilterChain chain = exchange -> Mono.fromRunnable(() -> chained.set(true));

    @Test
    void allowsSandboxedOriginForThemeAssets() {
        var exchange = exchange("/themes/theme-earth/assets/layout.js", "null");
        filter.filter(exchange, chain).block();
        assertThat(allowOrigin(exchange)).isEqualTo("null");
        assertThat(exchange.getResponse().getHeaders().get(HttpHeaders.VARY))
            .contains(HttpHeaders.ORIGIN);
        assertThat(chained.get()).isTrue();
    }

    @Test
    void allowsSandboxedOriginForPluginAssets() {
        var exchange = exchange("/plugins/example/assets/icon.svg", "null");
        filter.filter(exchange, chain).block();
        assertThat(allowOrigin(exchange)).isEqualTo("null");
    }

    @Test
    void allowsSandboxedOriginForAssetsUnderAnApplicationBasePath() {
        var exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/site/themes/theme-earth/assets/layout.js")
                .contextPath("/site").header(HttpHeaders.ORIGIN, "null"));
        filter.filter(exchange, chain).block();
        assertThat(allowOrigin(exchange)).isEqualTo("null");
    }

    @Test
    void leavesRegularOriginsUntouched() {
        var exchange = exchange("/themes/theme-earth/assets/layout.js", "https://example.com");
        filter.filter(exchange, chain).block();
        assertThat(allowOrigin(exchange)).isNull();
        assertThat(exchange.getResponse().getHeaders().get(HttpHeaders.VARY))
            .contains(HttpHeaders.ORIGIN);
    }

    @Test
    void leavesOtherPathsUntouched() {
        var exchange = exchange("/apis/api.content.halo.run/v1alpha1/posts", "null");
        filter.filter(exchange, chain).block();
        assertThat(allowOrigin(exchange)).isNull();
    }

    private static String allowOrigin(MockServerWebExchange exchange) {
        return exchange.getResponse().getHeaders()
            .getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    private static MockServerWebExchange exchange(String path, String origin) {
        return MockServerWebExchange.from(
            MockServerHttpRequest.get(path).header(HttpHeaders.ORIGIN, origin));
    }
}
