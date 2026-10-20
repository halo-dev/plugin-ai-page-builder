package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.View;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.app.theme.TemplateNameResolver;
import run.halo.aipagebuilder.ai.PublicApiCatalog;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;

class PagePreviewIsolationTest {
    private final TemplateNameResolver templates = mock(TemplateNameResolver.class);
    private final PublicApiCatalog catalog = mock(PublicApiCatalog.class);
    private final PageRenderer renderer =
        new PageRenderer(templates, new ContentPolicy(), mock(ExternalUrlSupplier.class));
    private final PagePreview previews =
        new PagePreview(renderer, catalog, mock(ExternalUrlSupplier.class));
    private final HandlerStrategies strategies = HandlerStrategies.withDefaults();
    private final PageDocument document = new PageDocument(2, "code", null, null,
        "<p>中文页面</p>", "p {color: red}", "Title", "", "window.executed = true");

    @ParameterizedTest
    @ValueSource(strings = {"", "/site"})
    void tokenPreviewIsolatesRealRenderingAndIncrementalWrites(String prefix) {
        var token = "secret-preview-token";
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET,
                URI.create("https://example.test" + prefix + "/preview/ai-page-builder/" + token
                    + "?token=" + token))
            .contextPath(prefix).header("Referer", "https://example.test/" + token));
        var request = ServerRequest.create(exchange, strategies.messageReaders());
        exchange.getAttributes().put(RouterFunctions.REQUEST_ATTRIBUTE, request);
        exchange.getAttributes().put(RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
            Map.of("token", token));
        exchange.getAttributes().put(RouterFunctions.MATCHING_PATTERN_ATTRIBUTE, token);
        exchange.getAttributes().put(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
            Map.of("token", token));
        var safeUri = URI.create("https://example.test" + prefix + "/");
        when(templates.resolveTemplateNameOrDefault(any(), eq("ai-page-builder")))
            .thenAnswer(call -> {
                assertClean(call.getArgument(0), safeUri);
                return Mono.just("test");
            });
        when(catalog.publicPathPrefixes()).thenReturn(List.of("/apis/api.content.halo.run/"));

        var response = previews.token(request, "one", "about", true, document).block();
        var expectedPolicy = response.headers().getFirst("Content-Security-Policy");
        response.writeTo(exchange, context(new View() {
            @Override
            public Mono<Void> render(Map<String, ?> model, MediaType type,
                ServerWebExchange incoming) {
                assertClean(incoming, safeUri);
                var page = (PageRenderer.PageView) model.get("aiPageBuilder");
                assertThat(page.html()).contains("中文页面", "<script>");
                assertThat(page.css()).contains("#ai-page-builder-one p");
                var html = "<html><head><base href='https://wrong.test/'></head><body>"
                    + incoming.getRequest().getURI() + page.html()
                    + "<script>window.themeExecuted = true</script></body></html>";
                var buffer = incoming.getResponse().bufferFactory()
                    .wrap(html.getBytes(StandardCharsets.UTF_8));
                // Deferred template rendering may flush incrementally.
                return incoming.getResponse().writeAndFlushWith(Mono.just(Mono.just(buffer)));
            }
        })).block();

        var hostHtml = exchange.getResponse().getBodyAsString().block();
        assertThat(hostHtml).doesNotContain(token);
        var host = Jsoup.parse(hostHtml);
        assertThat(host.select("script")).isEmpty();
        var frame = host.selectFirst("iframe");
        assertThat(frame.attr("sandbox")).isEqualTo("allow-scripts");
        assertThat(frame.attr("referrerpolicy")).isEqualTo("no-referrer");
        assertThat(frame.hasAttr("srcdoc")).isFalse();
        var childHtml = new String(Base64.getDecoder().decode(frame.attr("src").split(",", 2)[1]),
            StandardCharsets.UTF_8);
        assertThat(childHtml).contains(safeUri.toString(), "中文页面", "window.themeExecuted")
            .doesNotContain(token, "wrong.test");
        var child = Jsoup.parse(childHtml);
        assertThat(child.select("base")).hasSize(1);
        var childPolicy = child.selectFirst("meta[http-equiv=Content-Security-Policy]")
            .attr("content");
        assertThat(childPolicy).contains("base-uri " + safeUri, "frame-src 'none'",
            "connect-src https://example.test/apis/api.content.halo.run/")
            .doesNotContain("sandbox", "frame-src data:", "allow-same-origin");
        assertThat(exchange.getResponse().getHeaders().getFirst("Content-Security-Policy"))
            .isEqualTo(expectedPolicy)
            .contains("sandbox allow-scripts", "base-uri " + safeUri, "frame-src data:");
        assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(exchange.getResponse().getHeaders().getContentLength())
            .isEqualTo(hostHtml.getBytes(StandardCharsets.UTF_8).length);
        assertThat(request.uri().toString()).contains(token);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ordinaryPreviewWritesItsPolicyWithoutChangingTheRenderingRequest(boolean layout) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(
            "https://example.test/pages/one/preview"));
        var request = ServerRequest.create(exchange, strategies.messageReaders());
        var template = layout ? "ai-page-builder" : "ai-page-builder-standalone";
        when(templates.resolveTemplateNameOrDefault(exchange, template))
            .thenReturn(Mono.just("test"));
        // A changing public catalog must still render with all connections blocked.
        when(catalog.publicPathPrefixes()).thenThrow(new IllegalStateException("refreshing"));
        var response = previews.render(request, "one", "about", layout, document).block();
        response.writeTo(exchange, context(new View() {
            @Override
            public Mono<Void> render(Map<String, ?> model, MediaType type,
                ServerWebExchange incoming) {
                assertThat(incoming).isSameAs(exchange);
                var page = (PageRenderer.PageView) model.get("aiPageBuilder");
                return incoming.getResponse().writeWith(Mono.just(incoming.getResponse()
                    .bufferFactory().wrap(page.html().getBytes(StandardCharsets.UTF_8))));
            }
        })).block();
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("中文页面", "<script>")
            .doesNotContain("<iframe");
        assertThat(exchange.getResponse().getHeaders().getFirst("Content-Security-Policy"))
            .isEqualTo(response.headers().getFirst("Content-Security-Policy"))
            .contains("sandbox allow-scripts", "connect-src 'none'", "base-uri 'none'",
                "frame-src 'none'").doesNotContain("allow-same-origin");
    }

    private static void assertClean(ServerWebExchange exchange, URI uri) {
        assertThat(exchange.getRequest().getURI()).isEqualTo(uri);
        assertThat(exchange.getRequest().getHeaders().getFirst("Referer")).isNull();
        assertThat(exchange.getAttributes()).doesNotContainKeys(
            RouterFunctions.REQUEST_ATTRIBUTE, RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
            RouterFunctions.MATCHING_PATTERN_ATTRIBUTE,
            HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    }

    private ServerResponse.Context context(View view) {
        return new ServerResponse.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return strategies.messageWriters();
            }

            @Override
            public List<ViewResolver> viewResolvers() {
                return List.of((name, locale) -> Mono.just(view));
            }
        };
    }
}
