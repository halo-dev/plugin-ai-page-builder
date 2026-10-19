package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.aipagebuilder.content.ContentPolicy;

class PageChatEndpointTest {
    private final PageChatService service = mock(PageChatService.class);
    private final run.halo.aipagebuilder.theme.ThemeContextService themes = mock(run.halo.aipagebuilder.theme.ThemeContextService.class);
    private final PageChatEndpoint endpoint = new PageChatEndpoint(service, new ContentPolicy(), themes);

    @Test
    void streamsSdkChunksWithProtocolHeaderAndDoneMarker() {
        when(service.start(eq("page"), any(), eq("admin"))).thenReturn(Mono.just(Flux.just(
            Map.of("type", "start", "messageId", "r"),
            Map.of("type", "text-start", "id", "text"),
            Map.of("type", "text-delta", "id", "text", "delta", "hello\revent:injection"),
            Map.of("type", "text-end", "id", "text"),
            Map.of("type", "finish", "finishReason", "stop"))));
        var body = admin().post().uri("/pages/page/chat")
            .bodyValue(Map.of("runId", "r", "version", 1, "modelName", "model", "prompt", "Hi"))
            .exchange().expectStatus().isOk()
            .expectHeader().valueEquals("X-Halo-AI-UI-Message-Stream", "v1")
            .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("data:{", "\"type\":\"start\"", "data:[DONE]")
            .doesNotContain("\nevent:injection", "\r");
    }

    @Test
    void validatesContentWithoutSavingAndRequiresAdmin() {
        admin().post().uri("/content/validate").bodyValue(Map.of("html", "<p>Hi</p>", "css", ""))
            .exchange().expectStatus().isNoContent();
        admin().post().uri("/content/validate")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue("{\"html\":\"<p>Hi</p>\",\"css\":\"\",\"js\":null}")
            .exchange().expectStatus().isNoContent();
        admin().post().uri("/content/validate")
            .bodyValue(Map.of("html", "<p>Hi</p>", "css", "", "js", "console.log('Hi')"))
            .exchange().expectStatus().isNoContent();
        admin().post().uri("/content/validate")
            .bodyValue(Map.of("html", "<p>Hi</p>", "css", "", "js", "x".repeat(100_001)))
            .exchange().expectStatus().isBadRequest();
        admin().post().uri("/content/validate").bodyValue(Map.of("html", "<script>x</script>", "css", ""))
            .exchange().expectStatus().isBadRequest();
        WebTestClient.bindToRouterFunction(endpoint.endpoint()).build()
            .post().uri("/content/validate").bodyValue(Map.of("html", "", "css", ""))
            .exchange().expectStatus().isForbidden();
        WebTestClient.bindToRouterFunction(endpoint.endpoint()).build()
            .get().uri("/pages/page/chat").exchange().expectStatus().isForbidden();
    }

    @Test
    void themeContextRequiresAdminAndCannotBeCached() {
        when(themes.current()).thenReturn(Mono.just(run.halo.aipagebuilder.theme.ThemeStyleContext.unavailable()));
        admin().get().uri("/theme-context").exchange().expectStatus().isOk()
            .expectHeader().valueEquals("Cache-Control", "no-store")
            .expectBody().jsonPath("$.layoutState").isEqualTo("UNAVAILABLE");
        WebTestClient.bindToRouterFunction(endpoint.endpoint()).build()
            .get().uri("/theme-context").exchange().expectStatus().isForbidden();
    }

    @Test
    void routesSessionCrudAndHistoryCursor() {
        var summary = new ChatApi.SessionSummary("session", "page", "讨论", 2L,
            java.time.Instant.now(), java.time.Instant.now(), null);
        when(service.sessions("page")).thenReturn(Mono.just(new ChatApi.SessionList(List.of(summary))));
        when(service.createSession(eq("page"), any())).thenReturn(Mono.just(summary));
        when(service.renameSession(eq("page"), eq("session"), any())).thenReturn(Mono.just(summary));
        when(service.deleteSession("page", "session", 2L)).thenReturn(Mono.empty());
        when(service.session("page", "session", "cursor", 5)).thenReturn(Mono.just(
            new ChatApi.Session("page", 2L, List.of(), null, new ChatApi.ModelContextWindow(20, 262144),
                "session", "讨论", summary.createdAt(), summary.updatedAt(), false, null)));
        admin().get().uri("/pages/page/sessions").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.items[0].sessionId").isEqualTo("session");
        admin().post().uri("/pages/page/sessions").bodyValue(Map.of("title", "讨论"))
            .exchange().expectStatus().isOk();
        admin().patch().uri("/pages/page/sessions/session").bodyValue(Map.of("title", "讨论", "version", 2))
            .exchange().expectStatus().isOk();
        admin().get().uri("/pages/page/chat?sessionId=session&before=cursor&size=5")
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.sessionId").isEqualTo("session");
        admin().delete().uri("/pages/page/sessions/session?version=2")
            .exchange().expectStatus().isNoContent();
        admin().delete().uri("/pages/page/sessions/session").exchange().expectStatus().isBadRequest();
        admin().get().uri("/pages/page/chat?size=wrong").exchange().expectStatus().isBadRequest();
        WebTestClient.bindToRouterFunction(endpoint.endpoint()).build()
            .get().uri("/pages/page/sessions").exchange().expectStatus().isForbidden();
    }

    @Test
    void answersRequireAdminAndBindPageRunQuestionAndActor() {
        when(service.answer(eq("page"), eq("run"), eq("question"), any(), eq("admin")))
            .thenReturn(Mono.just(new ChatApi.Run("run", "running", null, null, null)));
        var payload = Map.of("answers", List.of(Map.of("questionId", "style", "text", "simple")));
        admin().post().uri("/pages/page/chat/runs/run/questions/question/answer").bodyValue(payload)
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("running");
        WebTestClient.bindToRouterFunction(endpoint.endpoint()).build().post()
            .uri("/pages/page/chat/runs/run/questions/question/answer").bodyValue(payload)
            .exchange().expectStatus().isForbidden();
    }

    private WebTestClient admin() {
        var authentication = new UsernamePasswordAuthenticationToken("admin", "unused",
            List.of(new SimpleGrantedAuthority("ROLE_super-role")));
        return WebTestClient.bindToRouterFunction(endpoint.endpoint())
            .webFilter((exchange, chain) -> chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))).build();
    }
}
