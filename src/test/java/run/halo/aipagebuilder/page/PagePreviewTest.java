package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jsoup.Jsoup;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.RenderingResponse;
import org.springframework.web.reactive.result.view.View;
import org.springframework.web.server.ServerWebExchange;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.aipagebuilder.ai.PublicApiCatalog;
import reactor.core.publisher.Mono;
import run.halo.aipagebuilder.model.PageDocument;

class PagePreviewTest {
    private final MutableClock clock = new MutableClock();
    private final PagePreviewStore store = new PagePreviewStore(clock);
    private final PageService pages = mock(PageService.class);
    private final PageRenderer renderer = mock(PageRenderer.class);
    private final PagePreview previews = new PagePreview(renderer, mock(PublicApiCatalog.class),
        mock(ExternalUrlSupplier.class));
    private final WebTestClient client = WebTestClient.bindToRouterFunction(
        new PagePreviewRoutes().aiPageBuilderTokenPreview(pages, previews, store))
        .handlerStrategies(HandlerStrategies.builder().viewResolver((name, locale) ->
            Mono.just(new View() {
                @Override
                public Mono<Void> render(Map<String, ?> model, MediaType type,
                    ServerWebExchange exchange) {
                    var bytes = model.get("html").toString().getBytes(StandardCharsets.UTF_8);
                    return exchange.getResponse().writeWith(Mono.just(
                        exchange.getResponse().bufferFactory().wrap(bytes)));
                }
            })).build())
        .webFilter(new PagePreviewIdentityFilter()).build();

    @Test
    void tokensExpireAtBoundaryAreReplacedAndClearOnStop() {
        var first = store.issue(page("a", 1L), "alice");
        var bob = store.issue(page("a", 1L), "bob");
        var replacement = store.issue(page("a", 1L), "alice");
        assertThat(store.get(first.token())).isNull();
        assertThat(store.get(bob.token())).isNotNull();
        assertThat(store.get(replacement.token())).isNotNull();
        assertThat(replacement.toString()).doesNotContain(replacement.token());
        clock.now = replacement.expiresAt();
        assertThat(store.get(replacement.token())).isNull();
        var fresh = store.issue(page("a", 1L), "alice");
        store.clear();
        assertThat(store.get(fresh.token())).isNull();
    }

    @Test
    void capacityIsBoundedAndEarliestExpiryIsEvicted() {
        var first = store.issue(page("first", 1L), "alice");
        clock.now = clock.now.plusSeconds(1);
        PagePreviewStore.Ticket latest = null;
        for (int i = 0; i < PagePreviewStore.CAPACITY; i++) {
            latest = store.issue(page("page-" + i, 1L), "alice");
        }
        assertThat(store.get(first.token())).isNull();
        assertThat(store.get(latest.token())).isNotNull();
    }

    @Test
    void validPreviewUsesSavedContentAndIsolationHeaders() {
        var document = new PageDocument(1, "code", null, null, "<p>Saved</p>", "");
        var ticket = store.issue(page("a", 1L), "alice");
        when(pages.get("a")).thenReturn(Mono.just(new PageApi.PageDetail(page("a", 1L), document)));
        when(renderer.render(any(), eq("a"), eq("about"), eq(true), eq(document), any(), any()))
            .thenReturn(RenderingResponse.create("test").header("Content-Type", MediaType.TEXT_HTML_VALUE)
                .modelAttribute("html", "saved").build());
        client.get().uri(PagePreviewStore.PATH + ticket.token()).exchange().expectStatus().isOk()
            .expectHeader().valueEquals("Cache-Control", "no-store")
            .expectHeader().valueEquals("Referrer-Policy", "no-referrer")
            .expectHeader().valueEquals("X-Robots-Tag", "noindex, nofollow, noarchive")
            .expectHeader().value("Content-Security-Policy", value -> assertThat(value)
                .contains("sandbox allow-scripts", "frame-src data:", "connect-src 'none'"))
            .expectBody(String.class).value(body -> assertThat(body)
                .contains("<iframe", "sandbox=\"allow-scripts\"", "data:text/html;base64,"));
    }

    @Test
    void invalidExpiredChangedDeletedAndClearedPreviewsReturn404() {
        client.get().uri(PagePreviewStore.PATH + "0".repeat(43)).exchange().expectStatus()
            .isNotFound();
        verifyNoInteractions(pages);
        var ticket = store.issue(page("a", 1L), "alice");
        when(pages.get("a")).thenReturn(Mono.just(new PageApi.PageDetail(page("a", 2L), null)));
        client.get().uri(PagePreviewStore.PATH + ticket.token()).exchange().expectStatus()
            .isNotFound();
        var old = page("a", 1L);
        var differentRevision = new PageApi.PageSummary(old.name(), old.slug(), old.title(), "", 1L,
            true, false, null, "other", null, old.updatedAt(), true);
        when(pages.get("a")).thenReturn(Mono.just(new PageApi.PageDetail(differentRevision, null)));
        client.get().uri(PagePreviewStore.PATH + ticket.token()).exchange().expectStatus()
            .isNotFound();
        when(pages.get("a"))
            .thenReturn(Mono.error(new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.NOT_FOUND)));
        client.get().uri(PagePreviewStore.PATH + ticket.token()).exchange().expectStatus()
            .isNotFound();
        clock.now = ticket.expiresAt();
        clearInvocations(pages);
        client.get().uri(PagePreviewStore.PATH + ticket.token()).exchange().expectStatus()
            .isNotFound();
        store.clear();
        client.get().uri(PagePreviewStore.PATH + ticket.token()).exchange().expectStatus()
            .isNotFound();
        verifyNoInteractions(pages, renderer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/preview/ai-page-builder/", "/preview/%61i-page-builder/",
        "/%70review/ai-page-builder/"})
    void previewIdentityOverridesAdminForEntireChainIncludingBasePath(String prefix) {
        var admin = new UsernamePasswordAuthenticationToken("administrator", "unused",
            AuthorityUtils.createAuthorityList("ROLE_super-role", "ROLE_PREVIOUS_ADMINISTRATOR"));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest
            .method(org.springframework.http.HttpMethod.GET,
                URI.create("/site" + prefix + "token")).contextPath("/site"));
        var observed = new AtomicReference<List<String>>();
        new PagePreviewIdentityFilter().filter(exchange.mutate().principal(Mono.just(admin))
            .build(),
            visitorExchange -> Mono.zip(visitorExchange.<java.security.Principal>getPrincipal(),
                    ReactiveSecurityContextHolder.getContext())
                .doOnNext(pair -> observed.set(List.of(pair.getT1().getName(),
                    pair.getT2().getAuthentication().getName(),
                    pair.getT2().getAuthentication().getAuthorities().iterator().next()
                        .getAuthority())))
                .then()).contextWrite(ReactiveSecurityContextHolder.withAuthentication(admin))
                    .block();
        assertThat(observed.get()).containsExactly("anonymousUser", "anonymousUser",
            "ROLE_anonymous");
        assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void ordinaryRoutesKeepIdentityAndHeaders() {
        var admin = new UsernamePasswordAuthenticationToken("administrator", "unused",
            AuthorityUtils.createAuthorityList("ROLE_super-role"));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/about"));
        var observed = new AtomicReference<String>();
        new PagePreviewIdentityFilter().filter(exchange, ignored ->
            ReactiveSecurityContextHolder.getContext()
                .doOnNext(context -> observed.set(context.getAuthentication().getName())).then())
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(admin)).block();
        assertThat(observed.get()).isEqualTo("administrator");
        assertThat(exchange.getResponse().getHeaders().containsHeader("X-Robots-Tag")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/preview/ai-page-builder/", "/preview/%61i-page-builder/",
        "/%70review/ai-page-builder/"})
    void themeSessionLookupCannotRecoverAdminButOriginalSessionRemainsIntact(String prefix) {
        var admin = new UsernamePasswordAuthenticationToken("administrator", "unused",
            AuthorityUtils.createAuthorityList("ROLE_super-role"));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest
            .method(org.springframework.http.HttpMethod.GET, URI.create(prefix + "token"))
            .header("Authorization", "Basic test").header("Cookie", "SESSION=test"));
        var repository = new org.springframework.security.web.server.context
            .WebSessionServerSecurityContextRepository();
        repository.save(exchange,
            new org.springframework.security.core.context.SecurityContextImpl(admin))
            .block();
        new PagePreviewIdentityFilter().filter(exchange, visitor -> {
            assertThat(visitor.getRequest().getHeaders().getFirst("Authorization")).isNull();
            assertThat(visitor.getRequest().getHeaders().getFirst("Cookie")).isNull();
            return repository.load(visitor).hasElement().doOnNext(found -> assertThat(found)
                .isFalse())
                .then(visitor.getSession()
                    .doOnNext(session -> assertThat(session.getAttributes()).isEmpty()))
                .then();
        }).contextWrite(ReactiveSecurityContextHolder.withAuthentication(admin)).block();
        assertThat(repository.load(exchange).block().getAuthentication().getName())
            .isEqualTo("administrator");
    }

    @Test
    void credentialUrlNeverReachesThemeOrExecutableDocument() {
        var ticket = store.issue(page("a", 1L), "alice");
        var document = new PageDocument(1, "code", null, null, "<p>Saved</p>", "");
        when(pages.get("a")).thenReturn(Mono.just(new PageApi.PageDetail(page("a", 1L), document)));
        when(renderer.render(any(), any(), any(), anyBoolean(), any(), any(), any())).thenAnswer(call -> {
            var request = call.<org.springframework.web.reactive.function.server.ServerRequest>
                getArgument(0);
            assertThat(request.uri().toString()).isEqualTo("https://example.test/");
            assertThat(request.exchange().getRequest().getURI()).isEqualTo(request.uri());
            assertThat(request.pathVariables()).isEmpty();
            return RenderingResponse.create("test").header("Content-Type", MediaType.TEXT_HTML_VALUE)
                .modelAttribute("html","<html><head><base href='https://wrong.test/'></head><body>"
                    + "<script>window.probe = location.href</script><p>Saved</p></body></html>").build();
        });
        var result = client.get().uri(URI.create("https://example.test/preview/"
                + "%61i-page-builder/" + ticket.token() + "?copy=" + ticket.token()))
            .exchange().expectStatus().isOk()
            .expectHeader().valueEquals("Referrer-Policy", "no-referrer")
            .expectBody(String.class).returnResult().getResponseBody();
        assertThat(result).doesNotContain(ticket.token());
        var host = Jsoup.parse(result);
        assertThat(host.select("script")).isEmpty();
        var frame = host.selectFirst("iframe");
        assertThat(frame.attr("sandbox")).isEqualTo("allow-scripts");
        assertThat(frame.attr("referrerpolicy")).isEqualTo("no-referrer");
        assertThat(frame.hasAttr("srcdoc")).isFalse();
        var source = frame.attr("src");
        assertThat(source).startsWith("data:text/html;base64,");
        var inner = Jsoup.parse(new String(Base64.getDecoder().decode(
            source.substring(source.indexOf(',') + 1)), StandardCharsets.UTF_8));
        assertThat(inner.select("base")).hasSize(1);
        assertThat(inner.selectFirst("base").attr("href")).isEqualTo("https://example.test/");
        assertThat(inner.selectFirst("script").data()).contains("window.probe");
        assertThat(inner.selectFirst("meta[http-equiv=Content-Security-Policy]").attr("content"))
            .contains("frame-src 'none'");
    }

    private static PageApi.PageSummary page(String name, Long version) {
        return new PageApi.PageSummary(name, "about", "Title", "", version, true, false, null,
            "draft", null, Instant.EPOCH, true);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-19T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
