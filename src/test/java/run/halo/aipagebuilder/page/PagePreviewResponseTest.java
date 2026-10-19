package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.View;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class PagePreviewResponseTest {
    @Test
    void deferredViewRenderingCannotRecoverCredentialFromRequestOrRoutingAttributes() {
        var token = "secret-preview-token";
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET,
                URI.create("https://example.test/site/preview/ai-page-builder/" + token
                    + "?token=" + token))
            .contextPath("/site").header("Referer", "https://example.test/" + token));
        var strategies = HandlerStrategies.withDefaults();
        var request = ServerRequest.create(exchange, strategies.messageReaders());
        exchange.getAttributes().put(RouterFunctions.REQUEST_ATTRIBUTE, request);
        exchange.getAttributes().put(RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
            Map.of("token", token));
        var safe = PagePreviewResponse.withoutCredential(request);
        assertThat(safe.uri()).isEqualTo(URI.create("https://example.test/site/"));
        var rendered = ServerResponse.ok().contentType(MediaType.TEXT_HTML)
            .header("Content-Security-Policy", "sandbox allow-scripts; default-src 'none'; "
                + "script-src 'unsafe-inline'; base-uri 'none'; frame-src 'none'")
            .render("test").block();
        var response = new PagePreviewResponse(rendered, safe.uri());
        response.writeTo(exchange, new ServerResponse.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return strategies.messageWriters();
            }

            @Override
            public List<ViewResolver> viewResolvers() {
                return List.of((name, locale) -> Mono.just(new View() {
                    @Override
                    public Mono<Void> render(Map<String, ?> model, MediaType contentType,
                        ServerWebExchange incoming) {
                        assertThat(incoming.getRequest().getURI()).isEqualTo(safe.uri());
                        assertThat(incoming.getRequest().getHeaders().getFirst("Referer")).isNull();
                        assertThat(incoming.getAttributes()).doesNotContainKeys(
                            RouterFunctions.REQUEST_ATTRIBUTE,
                            RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                        var html = "<html><head><title>Preview</title></head><body>"
                            + incoming.getRequest().getURI()
                            + "<script>window.executed = true</script></body></html>";
                        var buffer = incoming.getResponse().bufferFactory()
                            .wrap(html.getBytes(StandardCharsets.UTF_8));
                        // Thymeleaf may flush incrementally; do not let that bypass wrapping.
                        return incoming.getResponse()
                            .writeAndFlushWith(Mono.just(Mono.just(buffer)));
                    }
                }));
            }
        }).block();
        var hostHtml = exchange.getResponse().getBodyAsString().block();
        assertThat(hostHtml).doesNotContain(token);
        var host = Jsoup.parse(hostHtml);
        assertThat(host.select("script")).isEmpty();
        var frame = host.selectFirst("iframe");
        var child = new String(Base64.getDecoder().decode(frame.attr("src").split(",", 2)[1]),
            StandardCharsets.UTF_8);
        assertThat(child).contains("https://example.test/site/", "window.executed")
            .doesNotContain(token);
        assertThat(exchange.getResponse().getHeaders().getFirst("Content-Security-Policy"))
            .contains("base-uri https://example.test/site/", "frame-src data:");
        assertThat(exchange.getResponse().getHeaders().getContentLength())
            .isEqualTo(hostHtml.getBytes(StandardCharsets.UTF_8).length);
    }
}
