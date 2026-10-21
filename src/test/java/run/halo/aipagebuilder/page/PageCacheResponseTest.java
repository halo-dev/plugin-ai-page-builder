package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class PageCacheResponseTest {
    @Test
    void addsBodyDerivedValidatorsAndKeepsTheBody() {
        var body = "<p>中文</p>";
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/about"));
        write(exchange, body);
        var headers = exchange.getResponse().getHeaders();
        assertThat(headers.getETag()).matches("\"[0-9a-f]{64}\"");
        assertThat(headers.getCacheControl()).isEqualTo("no-cache");
        assertThat(headers.getLastModified()).isEqualTo(-1);
        assertThat(headers.getContentLength())
            .isEqualTo(body.getBytes(StandardCharsets.UTF_8).length);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo(body);
    }

    @Test
    void matchingIfNoneMatchReturns304WithoutBody() {
        var etag = etagOf("<p>About</p>");
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/about")
            .header(HttpHeaders.IF_NONE_MATCH, etag));
        write(exchange, "<p>About</p>");
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(exchange.getResponse().getHeaders().getETag()).isEqualTo(etag);
        assertThat(exchange.getResponse().getHeaders().getContentLength()).isEqualTo(-1);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEmpty();
    }

    @Test
    void weakAndListedIfNoneMatchValuesMatch() {
        var etag = etagOf("<p>About</p>");
        for (var value : List.of("W/" + etag, "\"other\", " + etag, "*")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/about")
                .header(HttpHeaders.IF_NONE_MATCH, value));
            write(exchange, "<p>About</p>");
            assertThat(exchange.getResponse().getStatusCode()).as(value)
                .isEqualTo(HttpStatus.NOT_MODIFIED);
        }
    }

    @Test
    void malformedIfNoneMatchIsIgnored() {
        for (var value : List.of("abc", "\"\"")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/about")
                .header(HttpHeaders.IF_NONE_MATCH, value));
            write(exchange, "<p>About</p>");
            assertThat(exchange.getResponse().getStatusCode()).as(value)
                .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void commasInsideAnOpaqueTagDoNotBecomeWildcards() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/about")
            .header(HttpHeaders.IF_NONE_MATCH, "\"x,*,y\""));
        write(exchange, "<p>About</p>");
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void changingTheBodyChangesTheEtag() {
        var stale = etagOf("<p>Old</p>");
        assertThat(etagOf("<p>New</p>")).isNotEqualTo(stale);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/about")
            .header(HttpHeaders.IF_NONE_MATCH, stale));
        write(exchange, "<p>New</p>");
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("<p>New</p>");
    }

    @Test
    void incrementalRenderingStillGetsValidators() {
        var body = "<p>中文</p>";
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/about"));
        write(exchange, body, true);
        assertThat(exchange.getResponse().getHeaders().getETag()).matches("\"[0-9a-f]{64}\"");
        assertThat(exchange.getResponse().getHeaders().getContentLength())
            .isEqualTo(body.getBytes(StandardCharsets.UTF_8).length);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo(body);
    }

    private String etagOf(String html) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/about"));
        write(exchange, html);
        return exchange.getResponse().getHeaders().getETag();
    }

    private void write(MockServerWebExchange exchange, String html) {
        write(exchange, html, false);
    }

    private void write(MockServerWebExchange exchange, String html, boolean flush) {
        new PageCacheResponse(html(html, flush)).writeTo(exchange, context()).block();
    }

    private ServerResponse.Context context() {
        return new ServerResponse.Context() {
            @Override
            public List<org.springframework.http.codec.HttpMessageWriter<?>> messageWriters() {
                return List.of();
            }

            @Override
            public List<ViewResolver> viewResolvers() {
                return List.of();
            }
        };
    }

    /** The renderer's own no-store header must be replaced for visitor responses. */
    private static ServerResponse html(String body, boolean flush) {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        headers.setCacheControl("no-store");
        // Date validation cannot track theme changes, so it must be dropped.
        headers.set(HttpHeaders.LAST_MODIFIED, "Wed, 21 Oct 2015 07:28:00 GMT");
        // The renderer may set a length; a 304 must drop it.
        headers.setContentLength(bytes.length);
        return new ServerResponse() {
            @Override
            public HttpStatusCode statusCode() {
                return HttpStatus.OK;
            }

            @Override
            public HttpHeaders headers() {
                return headers;
            }

            @Override
            public MultiValueMap<String, ResponseCookie> cookies() {
                return new LinkedMultiValueMap<>();
            }

            @Override
            public Mono<Void> writeTo(ServerWebExchange exchange, Context context) {
                exchange.getResponse().setStatusCode(statusCode());
                exchange.getResponse().getHeaders().putAll(headers);
                var factory = exchange.getResponse().bufferFactory();
                if (!flush) {
                    return exchange.getResponse().writeWith(Mono.just(factory.wrap(bytes)));
                }
                var half = bytes.length / 2;
                return exchange.getResponse().writeAndFlushWith(Flux.just(
                    Mono.just(factory.wrap(Arrays.copyOfRange(bytes, 0, half))),
                    Mono.just(factory.wrap(Arrays.copyOfRange(bytes, half, bytes.length)))));
            }
        };
    }
}
