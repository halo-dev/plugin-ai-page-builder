package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class WebReferenceToolsTest {
    private WebReferenceTools tools(ExchangeFunction exchange) {
        return new WebReferenceTools(WebClient.builder().exchangeFunction(exchange)
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(WebReferenceTools.MAX_BODY_BYTES))
            .build());
    }

    @Test
    void extractsMetadataAndMainContentWithoutScriptsOrHiddenText() {
        var tool = tools(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", "text/html; charset=UTF-8")
            .body("""
                <title>Example &amp; page</title><meta name="description" content="About us">
                <nav>Navigation</nav><main><h1>Hello</h1><p>World</p>
                <script>secret()</script><style>secret{}</style><p hidden>Hidden</p>
                <span aria-hidden="true">Invisible</span><iframe src="http://localhost">No</iframe>
                </main><footer>Footer</footer>
                """).build()));
        var result = tool.execute(Map.of("url", "https://example.com/page#section")).block();
        assertThat(result).containsEntry("url", "https://example.com/page")
            .containsEntry("title", "Example & page").containsEntry("description", "About us")
            .containsEntry("text", "Hello World").containsEntry("truncated", false)
            .containsEntry("sourceTrust", "untrusted_external_content");
    }

    @Test
    void detectsHtmlCharsetAndLimitsReturnedText() {
        byte[] bytes = "<meta charset=GBK><main>中文正文</main>".getBytes(Charset.forName("GBK"));
        var tool = tools(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", "text/html")
            .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes))).build()));
        assertThat(tool.execute(Map.of("url", "https://example.com")).block())
            .containsEntry("text", "中文正文");
        var plain = tools(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", "text/plain").body("x".repeat(17000)).build()));
        var result = plain.execute(Map.of("url", "https://example.com")).block();
        assertThat(result).containsEntry("truncated", true);
        assertThat((String) result.get("text")).hasSize(WebReferenceTools.MAX_TEXT_CHARS);
    }

    @Test
    void followsRelativeRedirectsAndReturnsFinalSource() {
        var urls = new ArrayList<URI>();
        var tool = tools(request -> {
            urls.add(request.url());
            assertThat(request.headers().containsHeader("Cookie")).isFalse();
            assertThat(request.headers().containsHeader("Authorization")).isFalse();
            return Mono.just(urls.size() == 1
                ? ClientResponse.create(HttpStatus.FOUND).header("Location", "/final").build()
                : ClientResponse.create(HttpStatus.OK).header("Content-Type", "text/plain")
                    .body("Final content").build());
        });
        assertThat(tool.execute(Map.of("url", "https://example.com/start")).block())
            .containsEntry("url", "https://example.com/final").containsEntry("text", "Final content");
        assertThat(urls).containsExactly(URI.create("https://example.com/start"), URI.create("https://example.com/final"));
    }

    @Test
    void rejectsUnsafeRedirectBeforeAnotherRequestAndCapsRedirectLoops() {
        var requests = new AtomicInteger();
        var tool = tools(request -> {
            requests.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.FOUND)
                .header("Location", "http://localhost/secret").build());
        });
        assertThatThrownBy(() -> tool.execute(Map.of("url", "https://example.com")).block())
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("private");
        assertThat(requests.get()).isEqualTo(1);
        requests.set(0);
        var loop = tools(request -> {
            requests.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.FOUND).header("Location", "/loop").build());
        });
        assertThatThrownBy(() -> loop.execute(Map.of("url", "https://example.com")).block())
            .hasMessageContaining("redirects");
        assertThat(requests.get()).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "ftp://example.com", "https://user:pass@example.com",
        "http://localhost", "http://service.local", "http://example.com:8080", "/relative", "https://[fe80::1%25eth0]"})
    void rejectsInvalidUrls(String url) {
        assertThatThrownBy(() -> WebReferenceTools.validateUrl(url)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1", "http://[::1]", "http://10.0.0.1",
        "http://169.254.169.254", "http://198.18.0.1", "http://[fc00::1]"})
    void haloClientRejectsNonPublicTargets(String url) {
        var tool = new WebReferenceTools();
        assertThatThrownBy(() -> tool.execute(Map.of("url", url)).block(Duration.ofSeconds(3)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsupportedTypesAndErrorStatus() {
        var image = tools(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", "image/png").body("binary").build()));
        assertThatThrownBy(() -> image.execute(Map.of("url", "https://example.com")).block())
            .hasMessageContaining("Only HTML");
        var error = tools(request -> Mono.just(ClientResponse.create(HttpStatus.UNAUTHORIZED).build()));
        assertThatThrownBy(() -> error.execute(Map.of("url", "https://example.com")).block())
            .hasMessageContaining("HTTP 401");
    }

    @Test
    void reportsPagesWithoutReadableText() {
        var tool = tools(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", "text/html").body("<script>renderApp()</script>").build()));
        assertThatThrownBy(() -> tool.execute(Map.of("url", "https://example.com")).block())
            .hasMessageContaining("No readable text");
    }

    @Test
    void capsStreamedResponseBodyWithoutContentLength() {
        var tool = tools(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", "text/plain")
            .body(Flux.range(0, 3).map(index -> DefaultDataBufferFactory.sharedInstance.wrap(new byte[1024 * 1024])))
            .build()));
        assertThatThrownBy(() -> tool.execute(Map.of("url", "https://example.com")).block())
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("size limit");
    }

    @Test
    void timesOutAndConvertsNetworkErrorsToRecoverableMessages() {
        var tool = tools(request -> Mono.never());
        StepVerifier.withVirtualTime(() -> tool.execute(Map.of("url", "https://example.com")))
            .thenAwait(Duration.ofSeconds(16)).expectError(IllegalArgumentException.class).verify();
        var failure = tools(request -> Mono.error(new IllegalStateException("internal connection details")));
        assertThatThrownBy(() -> failure.execute(Map.of("url", "https://example.com")).block())
            .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("internal connection details");
    }
}
