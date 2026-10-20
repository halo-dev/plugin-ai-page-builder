package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.RenderingResponse;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.aipagebuilder.model.PageDocument;

class PageRouterTest {
    private final PageService service = mock(PageService.class);
    private final PageRenderer renderer = mock(PageRenderer.class);
    private final org.springframework.web.reactive.function.server.RouterFunction<ServerResponse> router =
        new PageRouter().aiPageBuilderPublicRoutes(service, renderer);

    @Test
    void matchesOnlyAnExistingFullPathAndRendersWithStableName() {
        var document = new PageDocument(2, "code", null, null, "<p>Company</p>", "");
        var page = new PageApi.PageSummary("stable-id", "about/company", "Company", "", 1L, true,
            "draft", "published", Instant.now(), true);
        when(service.findPublished("about/company")).thenReturn(Mono.just(new PageApi.PageDetail(page, document)));
        when(renderer.render(any(), eq("stable-id"), eq("about/company"), eq(true), eq(document), any(), any(),
            eq(false))).thenReturn(RenderingResponse.create("test").build());
        var request = request("/about/company");
        var handler = router.route(request).block();
        assertThat(handler).isNotNull();
        assertThat(handler.handle(request).block().statusCode().value()).isEqualTo(200);
        verify(renderer).render(request, "stable-id", "about/company", true, document, "Company", "", false);
        when(service.findPublished("about")).thenReturn(Mono.empty());
        when(service.findPublished("about/company/child")).thenReturn(Mono.empty());
        assertThat(router.route(request("/about")).block()).isNull();
        assertThat(router.route(request("/about/company/child")).block()).isNull();
    }

    @Test
    void neverClaimsReservedMalformedOrNonHtmlRequests() {
        for (var path : new String[] {"/", "/console", "/console/settings", "/apis/foo", "/upload/a",
            "/login", "/login/oauth2/code/provider", "/captcha/altcha", "/password-reset/email",
            "/challenges/totp", "/%63onsole", "/login;test", "/about/../login", "/about%2fcompany"}) {
            assertThat(router.route(request(path)).block()).as(path).isNull();
        }
        assertThat(router.route(ServerRequest.create(MockServerWebExchange.from(
            MockServerHttpRequest.post("/about")), HandlerStrategies.withDefaults().messageReaders())).block()).isNull();
        assertThat(router.route(ServerRequest.create(MockServerWebExchange.from(
            MockServerHttpRequest.get("/about").accept(MediaType.APPLICATION_JSON)),
            HandlerStrategies.withDefaults().messageReaders())).block()).isNull();
        verifyNoInteractions(service, renderer);
    }

    @Test
    void onlySuperAdministratorsGetPageScriptsDisabled() {
        var document = new PageDocument(2, "code", null, null, "<p>Company</p>", "", "document.title = 'changed';");
        var page = new PageApi.PageSummary("stable-id", "about/company", "Company", "", 1L, true,
            "draft", "published", Instant.now(), true);
        when(service.findPublished("about/company"))
            .thenReturn(Mono.just(new PageApi.PageDetail(page, document)));
        when(renderer.render(any(), eq("stable-id"), eq("about/company"), eq(true), eq(document), any(), any(),
            anyBoolean())).thenReturn(RenderingResponse.create("test").build());
        var request = request("/about/company");
        var handler = router.route(request).block();
        assertThat(handler).isNotNull();

        var admin = new UsernamePasswordAuthenticationToken("admin", "password",
            AuthorityUtils.createAuthorityList("ROLE_super-role"));
        handler.handle(request)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(admin)).block();
        verify(renderer).render(request, "stable-id", "about/company", true, document, "Company", "", true);

        var impersonating = new UsernamePasswordAuthenticationToken("member", "password",
            AuthorityUtils.createAuthorityList("ROLE_authenticated",
                "ROLE_PREVIOUS_ADMINISTRATOR"));
        handler.handle(request)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(impersonating)).block();
        verify(renderer, times(2))
            .render(request, "stable-id", "about/company", true, document, "Company", "", true);

        var member = new UsernamePasswordAuthenticationToken("member", "password",
            AuthorityUtils.createAuthorityList("ROLE_authenticated"));
        handler.handle(request)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(member)).block();
        verify(renderer).render(request, "stable-id", "about/company", true, document, "Company", "", false);

        handler.handle(request).block();
        verify(renderer, times(2))
            .render(request, "stable-id", "about/company", true, document, "Company", "", false);
    }

    @Test
    void unpublishedAndMissingPagesFallThroughButBusinessPathsCanMatch() {
        when(service.findPublished("about")).thenReturn(Mono.empty());
        assertThat(router.route(request("/about")).block()).isNull();
        var page = new PageApi.PageSummary("stable", "categories", "Categories", "", 1L, true,
            "rev", "rev", Instant.now(), false);
        when(service.findPublished("categories")).thenReturn(Mono.just(new PageApi.PageDetail(page, null)));
        assertThat(router.route(request("/categories")).block()).isNotNull();
        verifyNoInteractions(renderer);
    }

    @Test
    void protectsCorePathsBehindAnApplicationBasePath() {
        for (var path : new String[] {"/site/console/settings", "/site/login", "/site/captcha/altcha"}) {
            var request = ServerRequest.create(MockServerWebExchange.from(
                MockServerHttpRequest.get(path).contextPath("/site").accept(MediaType.TEXT_HTML)),
                HandlerStrategies.withDefaults().messageReaders());
            assertThat(router.route(request).block()).as(path).isNull();
        }
        verifyNoInteractions(service, renderer);
    }

    private static ServerRequest request(String path) {
        return ServerRequest.create(MockServerWebExchange.from(MockServerHttpRequest.get(path)
            .accept(MediaType.TEXT_HTML)), HandlerStrategies.withDefaults().messageReaders());
    }
}
