package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
import reactor.core.publisher.Mono;

class PageCacheResponseTest {
    private static final Instant LAST_MODIFIED = Instant.parse("2026-01-02T03:04:05Z");

    @Test
    void addsBodyDerivedValidatorsAndKeepsTheBody() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/about"));
        write(exchange, "<p>About</p>");
        var headers = exchange.getResponse().getHeaders();
        assertThat(headers.getETag()).matches("\"[0-9a-f]{64}\"");
        assertThat(headers.getCacheControl()).isEqualTo("no-cache");
        assertThat(headers.getLastModified()).isEqualTo(LAST_MODIFIED.toEpochMilli());
        assertThat(headers.getContentLength()).isEqualTo("<p>About</p>".length());
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("<p>About</p>");
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
    void changingTheBodyChangesTheEtagAndIfModifiedSinceIsIgnoredWhenItDoesNotMatch() {
        var stale = etagOf("<p>Old</p>");
        assertThat(etagOf("<p>New</p>")).isNotEqualTo(stale);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/about")
            .header(HttpHeaders.IF_NONE_MATCH, stale)
            .header(HttpHeaders.IF_MODIFIED_SINCE, httpDate()));
        write(exchange, "<p>New</p>");
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("<p>New</p>");
    }

    @Test
    void ifModifiedSinceRevalidatesOnlyWithoutIfNoneMatch() {
        var fresh = MockServerWebExchange.from(MockServerHttpRequest.get("/about")
            .header(HttpHeaders.IF_MODIFIED_SINCE, httpDate()));
        write(fresh, "<p>About</p>");
        assertThat(fresh.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);

        var stale = MockServerWebExchange.from(MockServerHttpRequest.get("/about")
            .header(HttpHeaders.IF_MODIFIED_SINCE,
                DateTimeFormatter.RFC_1123_DATE_TIME.format(
                    LAST_MODIFIED.minusSeconds(1).atZone(ZoneOffset.UTC))));
        write(stale, "<p>About</p>");
        assertThat(stale.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String etagOf(String html) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/about"));
        write(exchange, html);
        return exchange.getResponse().getHeaders().getETag();
    }

    private static String httpDate() {
        return DateTimeFormatter.RFC_1123_DATE_TIME
            .format(LAST_MODIFIED.atZone(ZoneOffset.UTC));
    }

    private void write(MockServerWebExchange exchange, String html) {
        new PageCacheResponse(html(html), LAST_MODIFIED).writeTo(exchange, context()).block();
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
    private static ServerResponse html(String body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        headers.setCacheControl("no-store");
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
                var buffer = exchange.getResponse().bufferFactory()
                    .wrap(body.getBytes(StandardCharsets.UTF_8));
                return exchange.getResponse().writeWith(Mono.just(buffer));
            }
        };
    }
}
