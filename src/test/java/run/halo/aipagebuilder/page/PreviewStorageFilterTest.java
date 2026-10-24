package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

class PreviewStorageFilterTest {
    private static final String PAGE =
        "/apis/console.api.aipagebuilder.halo.run/v1alpha1/pages/about/preview";
    private static final String REVISION =
        "/apis/console.api.aipagebuilder.halo.run/v1alpha1/pages/about/revisions/one/preview";
    private static final String SHIM = "<script data-ai-page-builder=\"preview-storage\">";

    @Test
    void injectsIntoPageAndRevisionPreviews() {
        for (var path : List.of(PAGE, REVISION)) {
            client(path, MediaType.TEXT_HTML, "<html><head></head><body>ok</body></html>")
                .get().uri(path).exchange().expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body)
                    .contains("<head>" + SHIM, "Object.defineProperty", "</head>"));
        }
    }

    @Test
    void leavesPostAndOtherRoutesUntouched() {
        var routes = RouterFunctions.route()
            .POST(PAGE, request -> html("<p>post</p>"))
            .GET("/apis/console.api.aipagebuilder.halo.run/v1alpha1/pages/about",
                request -> html("<p>other</p>"))
            .build();
        var client = WebTestClient.bindToRouterFunction(routes)
            .webFilter(new PreviewStorageFilter()).build();
        client.post().uri(PAGE).exchange().expectBody(String.class)
            .value(body -> assertThat(body).doesNotContain(SHIM).contains("post"));
        client.get().uri("/apis/console.api.aipagebuilder.halo.run/v1alpha1/pages/about")
            .exchange().expectBody(String.class)
            .value(body -> assertThat(body).doesNotContain(SHIM).contains("other"));
    }

    @Test
    void leavesNonHtmlResponsesUntouched() {
        client(PAGE, MediaType.TEXT_PLAIN, "not html").get().uri(PAGE).exchange()
            .expectBody(String.class)
            .value(body -> assertThat(body).isEqualTo("not html"));
    }

    private static WebTestClient client(String path, MediaType type, String body) {
        RouterFunction<ServerResponse> routes = RouterFunctions.route()
            .GET(path, request -> ServerResponse.ok().contentType(type).bodyValue(body))
            .build();
        return WebTestClient.bindToRouterFunction(routes)
            .webFilter(new PreviewStorageFilter()).build();
    }

    private static Mono<ServerResponse> html(String body) {
        return ServerResponse.ok().contentType(MediaType.TEXT_HTML).bodyValue(body);
    }
}
