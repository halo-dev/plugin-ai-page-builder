package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.aipagebuilder.model.PageDocument;
import run.halo.aipagebuilder.model.ThemePage;

class PageRouterTest {
    private final PageService service = mock(PageService.class);
    private final PageRenderer renderer = mock(PageRenderer.class);
    private final org.springframework.web.reactive.function.server.RouterFunction<ServerResponse> router =
        new PageRouter().aiPageBuilderPublicRoutes(service, renderer);

    @Test
    void matchesOnlyAnExistingFullPathAndRendersWithStableName() {
        var document = new PageDocument(2, "code", null, null, "<p>Company</p>", "");
        var page = new PageApi.PageSummary("stable-id", "about/company", "Company", "", 1L, true, false, null,
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
        var page = new PageApi.PageSummary("stable-id", "about/company", "Company", "", 1L, true, false, null,
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
    void onlyAnonymousVisitorsReceiveCacheValidators() {
        var document = new PageDocument(2, "code", null, null, "<p>Company</p>", "");
        var page = new PageApi.PageSummary("stable-id", "about/company", "Company", "", 1L, true, false, null,
            "draft", "published", Instant.now(), true);
        when(service.findPublished("about/company"))
            .thenReturn(Mono.just(new PageApi.PageDetail(page, document)));
        when(renderer.render(any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean()))
            .thenReturn(RenderingResponse.create("test").build());
        var request = request("/about/company");
        var handler = router.route(request).block();
        assertThat(handler).isNotNull();

        assertThat(handler.handle(request).block()).isInstanceOf(PageCacheResponse.class);

        var admin = new UsernamePasswordAuthenticationToken("admin", "password",
            AuthorityUtils.createAuthorityList("ROLE_super-role"));
        assertThat(handler.handle(request)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(admin)).block())
            .isNotInstanceOf(PageCacheResponse.class);

        var member = new UsernamePasswordAuthenticationToken("member", "password",
            AuthorityUtils.createAuthorityList("ROLE_authenticated"));
        assertThat(handler.handle(request)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(member)).block())
            .isNotInstanceOf(PageCacheResponse.class);
    }

    @Test
    void unpublishedAndMissingPagesFallThroughButBusinessPathsCanMatch() {
        when(service.findPublished("about")).thenReturn(Mono.empty());
        assertThat(router.route(request("/about")).block()).isNull();
        var page = new PageApi.PageSummary("stable", "categories", "Categories", "", 1L, true, false, null,
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

    @Test
    void restrictedPagesAreOnlyReadableByTheSuperAdministrator() {
        var document = new PageDocument(2, "code", null, null, "<p>Company</p>", "");
        when(service.findPublished("about/company"))
            .thenReturn(Mono.just(new PageApi.PageDetail(restrictedPage(List.of()), document)));
        when(renderer.render(any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean()))
            .thenReturn(RenderingResponse.create("test").build());
        var request = request("/about/company");
        var handler = router.route(request).block();
        assertThat(handler).isNotNull();

        assertNotFound(handler.handle(request));
        var member = new UsernamePasswordAuthenticationToken("member", "password",
            AuthorityUtils.createAuthorityList("ROLE_authenticated"));
        assertNotFound(handler.handle(request)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(member)));
        verifyNoInteractions(renderer);

        var admin = new UsernamePasswordAuthenticationToken("admin", "password",
            AuthorityUtils.createAuthorityList("ROLE_super-role"));
        handler.handle(request)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(admin)).block();
        verify(renderer).render(request, "stable-id", "about/company", true, document, "Company", "",
            true);
    }

    @Test
    void restrictedPagesAllowListedUsersAndRoles() {
        var document = new PageDocument(2, "code", null, null, "<p>Company</p>", "");
        var members = List.of(
            new ThemePage.PageMember(ThemePage.MemberType.USER, "alice"),
            new ThemePage.PageMember(ThemePage.MemberType.ROLE, "editors"));
        when(service.findPublished("about/company"))
            .thenReturn(Mono.just(new PageApi.PageDetail(restrictedPage(members), document)));
        when(renderer.render(any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean()))
            .thenReturn(RenderingResponse.create("test").build());
        var request = request("/about/company");
        var handler = router.route(request).block();
        assertThat(handler).isNotNull();

        var alice = new UsernamePasswordAuthenticationToken("alice", "password",
            AuthorityUtils.createAuthorityList("ROLE_authenticated"));
        var aliceResponse = handler.handle(request)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(alice)).block();
        assertThat(aliceResponse).isNotNull();
        assertThat(aliceResponse).isNotInstanceOf(PageCacheResponse.class);
        verify(renderer).render(request, "stable-id", "about/company", true, document, "Company", "",
            false);

        var editor = new UsernamePasswordAuthenticationToken("bob", "password",
            AuthorityUtils.createAuthorityList("ROLE_authenticated", "ROLE_editors"));
        handler.handle(request)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(editor)).block();
        verify(renderer, times(2)).render(request, "stable-id", "about/company", true, document,
            "Company", "", false);

        var outsider = new UsernamePasswordAuthenticationToken("carol", "password",
            AuthorityUtils.createAuthorityList("ROLE_authenticated"));
        assertNotFound(handler.handle(request)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(outsider)));
        verify(renderer, never()).render(request, "stable-id", "about/company", true, document,
            "Company", "", true);
    }

    @Test
    void unrestrictedPagesStayPublicEvenWithMembers() {
        var document = new PageDocument(2, "code", null, null, "<p>Company</p>", "");
        var page = new PageApi.PageSummary("stable-id", "about/company", "Company", "", 1L, true,
            false, List.of(new ThemePage.PageMember(ThemePage.MemberType.USER, "alice")), "draft",
            "published", Instant.now(), true);
        when(service.findPublished("about/company"))
            .thenReturn(Mono.just(new PageApi.PageDetail(page, document)));
        when(renderer.render(any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean()))
            .thenReturn(RenderingResponse.create("test").build());
        var request = request("/about/company");
        var handler = router.route(request).block();
        assertThat(handler).isNotNull();
        assertThat(handler.handle(request).block()).isInstanceOf(PageCacheResponse.class);
        verify(renderer).render(request, "stable-id", "about/company", true, document, "Company", "",
            false);
    }

    @Test
    void restrictedPagesWithoutMembersRejectSignedInUsers() {
        var document = new PageDocument(2, "code", null, null, "<p>Company</p>", "");
        when(service.findPublished("about/company"))
            .thenReturn(Mono.just(new PageApi.PageDetail(restrictedPage(null), document)));
        var request = request("/about/company");
        var handler = router.route(request).block();
        assertThat(handler).isNotNull();

        var member = new UsernamePasswordAuthenticationToken("member", "password",
            AuthorityUtils.createAuthorityList("ROLE_authenticated"));
        assertNotFound(handler.handle(request)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(member)));
        assertNotFound(handler.handle(request));
        verifyNoInteractions(renderer);
    }

    @Test
    void impersonatingAdministratorsReadRestrictedPages() {
        var document = new PageDocument(2, "code", null, null, "<p>Company</p>", "");
        when(service.findPublished("about/company"))
            .thenReturn(Mono.just(new PageApi.PageDetail(restrictedPage(null), document)));
        when(renderer.render(any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean()))
            .thenReturn(RenderingResponse.create("test").build());
        var request = request("/about/company");
        var handler = router.route(request).block();
        assertThat(handler).isNotNull();

        var impersonating = new UsernamePasswordAuthenticationToken("member", "password",
            AuthorityUtils.createAuthorityList("ROLE_authenticated", "ROLE_PREVIOUS_ADMINISTRATOR"));
        var response = handler.handle(request)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(impersonating)).block();
        assertThat(response).isNotNull();
        assertThat(response).isNotInstanceOf(PageCacheResponse.class);
        verify(renderer).render(request, "stable-id", "about/company", true, document, "Company", "",
            true);
    }

    private static PageApi.PageSummary restrictedPage(List<ThemePage.PageMember> members) {
        return new PageApi.PageSummary("stable-id", "about/company", "Company", "", 1L, true, true,
            members, "draft", "published", Instant.now(), true);
    }

    private static void assertNotFound(Mono<ServerResponse> response) {
        assertThatThrownBy(response::block).isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    private static ServerRequest request(String path) {
        return ServerRequest.create(MockServerWebExchange.from(MockServerHttpRequest.get(path)
            .accept(MediaType.TEXT_HTML)), HandlerStrategies.withDefaults().messageReaders());
    }
}
