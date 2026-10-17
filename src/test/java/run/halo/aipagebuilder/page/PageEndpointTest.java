package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.RenderingResponse;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.infra.utils.JsonUtils;
import run.halo.app.theme.TemplateNameResolver;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;
import run.halo.aipagebuilder.page.PageApi.PageDetail;

class PageEndpointTest {
    private final PageService service = mock(PageService.class);
    private final PageRenderer renderer = mock(PageRenderer.class);
    private final PageEndpoint endpoint = new PageEndpoint(service, renderer);

    private static PageDetail detail(PageDocument document) {
        return new PageDetail(new PageApi.PageSummary("about", "company/about-us", "About", "",
            1L, true, "draft", null, java.time.Instant.now(), true), document);
    }

    private static ServerRequest request(String path) {
        return ServerRequest.create(MockServerWebExchange.from(MockServerHttpRequest.get(path)),
            HandlerStrategies.withDefaults().messageReaders());
    }

    private static ServerRequest previewRequest(String json) {
        return ServerRequest.create(MockServerWebExchange.from(MockServerHttpRequest
                .post("/pages/about/preview").contentType(MediaType.APPLICATION_JSON).body(json)),
            HandlerStrategies.withDefaults().messageReaders());
    }

    private static Mono<ServerResponse> asAdmin(PageEndpoint endpoint, ServerRequest request) {
        var authentication = new UsernamePasswordAuthenticationToken("admin", "",
            List.of(new SimpleGrantedAuthority("ROLE_super-role")));
        return endpoint.endpoint().route(request).flatMap(handler -> handler.handle(request))
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ROLE_editor"})
    void candidatePreviewRequiresSuperAdministrator(String role) {
        var request = previewRequest("{}");
        var result = endpoint.endpoint().route(request).flatMap(handler -> handler.handle(request));
        if (!role.isEmpty()) {
            var authentication = new UsernamePasswordAuthenticationToken("editor", "",
                List.of(new SimpleGrantedAuthority(role)));
            result = result.contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
        }
        assertThatThrownBy(result::block).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(service, renderer);
    }

    @Test
    void candidatePreviewUsesSameThemeAndCssAsGetWithoutSaving() {
        var resolver = mock(TemplateNameResolver.class);
        var renderingEndpoint = new PageEndpoint(service, new PageRenderer(resolver, new ContentPolicy()));
        var stored = new PageDocument(1, "code", null, null, "<p>Stored</p>", "", "Stored", null);
        var candidate = new PageDocument(2, "code", null, null, "<p>Unsaved</p>", """
            :root {color: red}
            html.dark p {color: white}
            @media (min-width: 600px) {p {margin: 1rem}}
            @supports (display: grid) {p {display: grid}}
            """, "Unsaved title", "Unsaved description", "console.log('preview');");
        when(service.get("about")).thenReturn(Mono.just(detail(stored)))
            .thenReturn(Mono.just(detail(candidate)));
        when(resolver.resolveTemplateNameOrDefault(any(), eq("ai-page-builder")))
            .thenReturn(Mono.just("theme:custom-ai-page-builder"));

        var response = (RenderingResponse) asAdmin(renderingEndpoint,
            previewRequest(JsonUtils.objectToJson(candidate))).block();
        var getResponse = (RenderingResponse) asAdmin(renderingEndpoint,
            request("/pages/about/preview")).block();

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().getContentType()).isEqualTo(MediaType.TEXT_HTML);
        assertThat(response.headers().getCacheControl()).isEqualTo("no-store");
        assertThat(response.name()).isEqualTo("theme:custom-ai-page-builder");
        assertThat(response.model()).isEqualTo(getResponse.model());
        var view = (PageRenderer.PageView) response.model().get("aiPageBuilder");
        assertThat(view.title()).isEqualTo(candidate.title());
        assertThat(view.description()).isEqualTo(candidate.description());
        assertThat(view.html()).startsWith(candidate.html()).doesNotContain(stored.html());
        assertThat(view.css()).contains("#ai-page-builder-about", "html.dark #ai-page-builder-about p",
            "@media", "@supports");
        verify(service, times(2)).get("about");
        verifyNoMoreInteractions(service);
    }

    @Test
    void slugUpdateUsesStableIdentifierAndVersionedBody() {
        var input = new PageApi.UpdatePageSlugRequest(4L, "company/about-us");
        var document = new PageDocument(1, "code", null, null, "", "", "About", "");
        when(service.updateSlug("about", input)).thenReturn(Mono.just(detail(document)));
        var request = ServerRequest.create(MockServerWebExchange.from(MockServerHttpRequest
                .put("/pages/about/slug").contentType(MediaType.APPLICATION_JSON)
                .body(JsonUtils.objectToJson(input))), HandlerStrategies.withDefaults().messageReaders());
        assertThat(asAdmin(endpoint, request).block().statusCode()).isEqualTo(HttpStatus.OK);
        verify(service).updateSlug("about", input);
        verifyNoMoreInteractions(service);
    }

    @Test
    void previewsPassStableNameAndSeparateSlugToRenderer() {
        var document = new PageDocument(1, "code", null, null, "", "", "About", "");
        when(service.get("about")).thenReturn(Mono.just(detail(document)));
        when(renderer.render(any(), eq("about"), eq("company/about-us"), eq(true), eq(document)))
            .thenReturn(ServerResponse.ok().build());
        assertThat(asAdmin(endpoint, request("/pages/about/preview")).block().statusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(asAdmin(endpoint, previewRequest(JsonUtils.objectToJson(document))).block().statusCode())
            .isEqualTo(HttpStatus.OK);
        verify(renderer, times(2)).render(any(), eq("about"), eq("company/about-us"), eq(true), eq(document));
    }

    @Test
    void revisionPreviewRendersRevisionDocumentWithPageTheme() {
        var document = new PageDocument(1, "code", null, null, "", "", "About", "");
        var revisionDocument = new PageDocument(2, "code", null, null, "<p>Old</p>", "", "Old title", "");
        when(service.get("about")).thenReturn(Mono.just(detail(document)));
        when(service.revisionDetail("about", "rev-1"))
            .thenReturn(Mono.just(new PageApi.RevisionDetail(
                new PageApi.RevisionSummary("rev-1", java.time.Instant.now(), "admin", "ai",
                    "Old title", null),
                revisionDocument)));
        when(renderer.render(any(), eq("about"), eq("company/about-us"), eq(true),
            eq(revisionDocument))).thenReturn(ServerResponse.ok().build());

        assertThat(asAdmin(endpoint, request("/pages/about/revisions/rev-1/preview")).block()
            .statusCode()).isEqualTo(HttpStatus.OK);
        verify(renderer).render(any(), eq("about"), eq("company/about-us"), eq(true),
            eq(revisionDocument));
    }

    @Test
    void candidatePreviewRequiresExistingPage() {
        var candidate = new PageDocument(1, "code", null, null, "<p>Unsaved</p>", "", "Title", null);
        when(service.get("about")).thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
        var result = asAdmin(endpoint, previewRequest(JsonUtils.objectToJson(candidate)));
        assertThatThrownBy(result::block).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(service).get("about");
        verifyNoMoreInteractions(service);
        verifyNoInteractions(renderer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"html", "css", "schema"})
    void invalidCandidateIsRejectedBeforeThemeRendering(String invalidField) {
        var resolver = mock(TemplateNameResolver.class);
        var renderingEndpoint = new PageEndpoint(service, new PageRenderer(resolver, new ContentPolicy()));
        var stored = new PageDocument(1, "code", null, null, "<p>Stored</p>", "", "Stored", null);
        var candidate = new PageDocument("schema".equals(invalidField) ? 99 : 1, "code", null, null,
            "html".equals(invalidField) ? "<script>alert(1)</script>" : "<p>Unsaved</p>",
            "css".equals(invalidField) ? "@import url('https://example.com/style.css');" : "",
            "Title", null);
        when(service.get("about")).thenReturn(Mono.just(detail(stored)));
        var result = asAdmin(renderingEndpoint, previewRequest(JsonUtils.objectToJson(candidate)));
        assertThatThrownBy(result::block).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(service).get("about");
        verifyNoMoreInteractions(service);
        verifyNoInteractions(resolver);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{"})
    void candidatePreviewRequiresValidJsonBody(String json) {
        var result = asAdmin(endpoint, previewRequest(json));
        assertThatThrownBy(result::block).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(service, renderer);
    }

    @Test
    void anonymousCannotReadDraftPreview() {
        var request = request("/pages/about/preview");
        var result = endpoint.endpoint().route(request).flatMap(handler -> handler.handle(request));
        assertThatThrownBy(result::block).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(service, renderer);
    }

    @Test
    void ordinaryUserCannotReadDraft() {
        var request = request("/pages/about");
        var authentication = new UsernamePasswordAuthenticationToken("editor", "",
            List.of(new SimpleGrantedAuthority("ROLE_editor")));
        var result = endpoint.endpoint().route(request).flatMap(handler -> handler.handle(request))
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
        assertThatThrownBy(result::block).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(service);
    }

    @Test
    void superAdministratorCanReadDraft() {
        var request = request("/pages/about");
        var document = new PageDocument(1, "code", "", Map.of(), "", "", "About", "");
        when(service.get("about")).thenReturn(Mono.just(new PageDetail(null, document)));
        var authentication = new UsernamePasswordAuthenticationToken("admin", "",
            List.of(new SimpleGrantedAuthority("ROLE_super-role")));
        var response = endpoint.endpoint().route(request).flatMap(handler -> handler.handle(request))
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)).block();
        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
    }
}
