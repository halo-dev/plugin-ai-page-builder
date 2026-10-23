package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpRequest;
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
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.aipagebuilder.Json;
import run.halo.app.theme.TemplateNameResolver;
import run.halo.aipagebuilder.ai.PublicApiCatalog;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;
import run.halo.aipagebuilder.page.PageApi.PageDetail;

class PageEndpointTest {
    private static final ExternalUrlSupplier EXTERNAL_URLS = new ExternalUrlSupplier() {
        @Override
        public URI get() {
            return URI.create("/");
        }

        @Override
        public URL getURL(HttpRequest request) {
            try {
                return URI.create("http://localhost/").toURL();
            } catch (MalformedURLException error) {
                throw new IllegalStateException(error);
            }
        }

        @Override
        public URL getRaw() {
            return null;
        }
    };
    private final PageService service = mock(PageService.class);
    private final PageRenderer renderer = mock(PageRenderer.class);
    private final PagePreview preview = mock(PagePreview.class);
    private final PageImportReviewService reviewService = mock(PageImportReviewService.class);
    private final PageEndpoint endpoint = new PageEndpoint(service, renderer, preview, reviewService);

    private static PageDetail detail(PageDocument document) {
        return new PageDetail(new PageApi.PageSummary("about", "company/about-us", "About", "",
            1L, true, false, null, "draft", null, java.time.Instant.now(), true), document);
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
        verifyNoInteractions(service, renderer, preview);
    }

    private static ServerRequest binaryRequest(String path, byte[] bytes) {
        var buffer = org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance.wrap(bytes);
        return ServerRequest.create(MockServerWebExchange.from(MockServerHttpRequest
                .post(path).contentType(MediaType.parseMediaType("application/zip"))
                .body(reactor.core.publisher.Flux.just(buffer))),
            HandlerStrategies.withDefaults().messageReaders());
    }

    private static ServerRequest importRequest(byte[] bytes) {
        return binaryRequest("/pages/import?reviewId=review-1", bytes);
    }

    private static ServerRequest importRequest(byte[] bytes, String query) {
        return binaryRequest("/pages/import" + (query == null ? "" : "?" + query), bytes);
    }

    @Test
    void reviewsArchiveBeforeImport() throws Exception {
        var bytes = PageArchiveTest.zip(PageArchiveTest.files());
        var review = new PageApi.ImportReview("review-1", "safe", "AI 未发现明显风险。", List.of(),
            "About", "", true, java.time.Instant.now().plusSeconds(600));
        when(reviewService.review(any(byte[].class), eq("admin"), any())).thenReturn(Mono.just(review));
        var response = asAdmin(endpoint, binaryRequest("/pages/import/review", bytes)).block();
        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        verify(reviewService).review(eq(bytes), eq("admin"), isNull());
        verifyNoInteractions(service, renderer, preview);
    }

    @Test
    void reviewPassesSelectedModel() throws Exception {
        var bytes = PageArchiveTest.zip(PageArchiveTest.files());
        var review = new PageApi.ImportReview("review-1", "safe", "AI 未发现明显风险。", List.of(),
            "About", "", true, java.time.Instant.now().plusSeconds(600));
        when(reviewService.review(any(byte[].class), eq("admin"), eq("model-a")))
            .thenReturn(Mono.just(review));

        var response = asAdmin(endpoint,
            binaryRequest("/pages/import/review?model=model-a", bytes)).block();

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
        verify(reviewService).review(eq(bytes), eq("admin"), eq("model-a"));
        verifyNoInteractions(service, renderer, preview);
    }

    @Test
    void importsBinaryUploadWithAuthenticatedActor() throws Exception {
        var bytes = PageArchiveTest.zip(PageArchiveTest.files());
        var document = PageArchive.read(bytes).document();
        when(service.importPage(eq("review-1"), any(byte[].class), eq("admin"), eq(false)))
            .thenReturn(Mono.just(detail(document)));
        assertThat(asAdmin(endpoint, importRequest(bytes)).block().statusCode()).isEqualTo(HttpStatus.OK);
        verify(service).importPage(eq("review-1"), eq(bytes), eq("admin"), eq(false));
        verifyNoInteractions(reviewService);
        verifyNoMoreInteractions(service);
    }

    @Test
    void importRequiresReviewId() throws Exception {
        var bytes = PageArchiveTest.zip(PageArchiveTest.files());
        var result = asAdmin(endpoint, importRequest(bytes, null));
        assertThatThrownBy(result::block).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(service, reviewService, renderer, preview);
    }

    @Test
    void confirmedSuspiciousReviewIsPassedToImport() throws Exception {
        var bytes = PageArchiveTest.zip(PageArchiveTest.files());
        var document = PageArchive.read(bytes).document();
        when(service.importPage(eq("review-1"), any(byte[].class), eq("admin"), eq(true)))
            .thenReturn(Mono.just(detail(document)));
        assertThat(asAdmin(endpoint, importRequest(bytes, "reviewId=review-1&confirmed=true"))
            .block().statusCode()).isEqualTo(HttpStatus.OK);
        verify(service).importPage(eq("review-1"), eq(bytes), eq("admin"), eq(true));
        verifyNoInteractions(reviewService);
    }

    @Test
    void importRejectionPreservesStatus() throws Exception {
        var bytes = PageArchiveTest.zip(PageArchiveTest.files());
        when(service.importPage(eq("review-1"), any(byte[].class), eq("admin"), eq(false)))
            .thenReturn(Mono.error(new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_CONTENT, "blocked")));
        var result = asAdmin(endpoint, importRequest(bytes));
        assertThatThrownBy(result::block).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
        verify(service).importPage(eq("review-1"), eq(bytes), eq("admin"), eq(false));
        verifyNoInteractions(reviewService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ROLE_editor"})
    void importRequiresSuperAdministrator(String role) {
        var request = importRequest(new byte[] {1});
        var result = endpoint.endpoint().route(request).flatMap(handler -> handler.handle(request));
        if (!role.isEmpty()) {
            var authentication = new UsernamePasswordAuthenticationToken("editor", "",
                List.of(new SimpleGrantedAuthority(role)));
            result = result.contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
        }
        assertThatThrownBy(result::block).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(service, renderer, preview);
    }

    @Test
    void oversizedUploadIsRejectedBeforeImport() {
        var result = asAdmin(endpoint, importRequest(new byte[PageArchive.MAX_BYTES + 1]));
        assertThatThrownBy(result::block).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE));
        verifyNoInteractions(service, renderer, preview);
    }

    @Test
    void emptyUploadIsRejectedBeforeImport() {
        var request = ServerRequest.create(MockServerWebExchange.from(MockServerHttpRequest
                .post("/pages/import").contentType(MediaType.parseMediaType("application/zip"))),
            HandlerStrategies.withDefaults().messageReaders());
        assertThatThrownBy(() -> asAdmin(endpoint, request).block())
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(service, renderer, preview);
    }

    @Test
    void candidatePreviewUsesSameThemeAndCssAsGetWithoutSaving() {
        var resolver = mock(TemplateNameResolver.class);
        var pageRenderer = new PageRenderer(resolver, new ContentPolicy(), EXTERNAL_URLS);
        var renderingEndpoint = new PageEndpoint(service, pageRenderer,
            new PagePreview(pageRenderer, mock(PublicApiCatalog.class), EXTERNAL_URLS), reviewService);
        var stored = new PageDocument(1, "code", null, null, "<p>Stored</p>", "");
        var candidate = new PageDocument(2, "code", null, null, "<p>Unsaved</p>", """
            :root {color: red}
            html.dark p {color: white}
            @media (min-width: 600px) {p {margin: 1rem}}
            @supports (display: grid) {p {display: grid}}
            """, "console.log('preview');");
        when(service.get("about")).thenReturn(Mono.just(detail(stored)))
            .thenReturn(Mono.just(detail(candidate)));
        when(resolver.resolveTemplateNameOrDefault(any(), eq("ai-page-builder")))
            .thenReturn(Mono.just("theme:custom-ai-page-builder"));

        var response = (RenderingResponse) asAdmin(renderingEndpoint,
            previewRequest(Json.toJson(candidate))).block();
        var getResponse = (RenderingResponse) asAdmin(renderingEndpoint,
            request("/pages/about/preview")).block();

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().getContentType()).isEqualTo(MediaType.TEXT_HTML);
        assertThat(response.headers().getCacheControl()).isEqualTo("no-store");
        assertThat(response.name()).isEqualTo("theme:custom-ai-page-builder");
        assertThat(response.model()).isEqualTo(getResponse.model());
        assertThat(response.headers().getFirst("Content-Security-Policy"))
            .contains("sandbox allow-scripts",
                "connect-src http://localhost/rss.xml http://localhost/feed.xml "
                    + "http://localhost/sitemap.xml http://localhost/feed/")
            .doesNotContain("allow-same-origin");
        assertThat(getResponse.headers().getFirst("Content-Security-Policy"))
            .isEqualTo(response.headers().getFirst("Content-Security-Policy"));
        var view = (PageRenderer.PageView) response.model().get("aiPageBuilder");
        assertThat(view.title()).isEqualTo("About");
        assertThat(view.description()).isEqualTo("");
        assertThat(view.html()).startsWith(candidate.html()).doesNotContain(stored.html());
        assertThat(view.css()).contains("#ai-page-builder-about", "html.dark #ai-page-builder-about p",
            "@media", "@supports");
        verify(service, times(2)).get("about");
        verifyNoMoreInteractions(service);
    }

    @Test
    void slugUpdateUsesStableIdentifierAndVersionedBody() {
        var input = new PageApi.UpdatePagePropertiesRequest(4L, null, null, "company/about-us", null, null, null);
        var document = new PageDocument(1, "code", null, null, "", "");
        when(service.updateProperties("about", input)).thenReturn(Mono.just(detail(document)));
        var request = ServerRequest.create(MockServerWebExchange.from(MockServerHttpRequest
                .put("/pages/about/properties").contentType(MediaType.APPLICATION_JSON)
                .body(Json.toJson(input))), HandlerStrategies.withDefaults().messageReaders());
        assertThat(asAdmin(endpoint, request).block().statusCode()).isEqualTo(HttpStatus.OK);
        verify(service).updateProperties("about", input);
        verifyNoMoreInteractions(service);
    }

    @Test
    void previewsPassStableNameAndSeparateSlugToPreview() {
        var document = new PageDocument(1, "code", null, null, "", "");
        when(service.get("about")).thenReturn(Mono.just(detail(document)));
        when(preview.render(any(), eq("about"), eq("company/about-us"), eq(true), eq(document), any(), any()))
            .thenReturn(ServerResponse.ok().build());
        assertThat(asAdmin(endpoint, request("/pages/about/preview")).block().statusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(asAdmin(endpoint, previewRequest(Json.toJson(document))).block().statusCode())
            .isEqualTo(HttpStatus.OK);
        verify(preview, times(2)).render(any(), eq("about"), eq("company/about-us"), eq(true), eq(document), any(), any());
    }

    @Test
    void revisionPreviewRendersRevisionDocumentWithPageTheme() {
        var document = new PageDocument(1, "code", null, null, "", "");
        var revisionDocument = new PageDocument(2, "code", null, null, "<p>Old</p>", "");
        when(service.get("about")).thenReturn(Mono.just(detail(document)));
        when(service.revisionDetail("about", "rev-1"))
            .thenReturn(Mono.just(new PageApi.RevisionDetail(
                new PageApi.RevisionSummary("rev-1", java.time.Instant.now(), "admin", "ai", null),
                revisionDocument)));
        when(preview.render(any(), eq("about"), eq("company/about-us"), eq(true),
            eq(revisionDocument), any(), any())).thenReturn(ServerResponse.ok().build());

        assertThat(asAdmin(endpoint, request("/pages/about/revisions/rev-1/preview")).block()
            .statusCode()).isEqualTo(HttpStatus.OK);
        verify(preview).render(any(), eq("about"), eq("company/about-us"), eq(true),
            eq(revisionDocument), any(), any());
    }

    @Test
    void candidatePreviewRequiresExistingPage() {
        var candidate = new PageDocument(1, "code", null, null, "<p>Unsaved</p>", "");
        when(service.get("about")).thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
        var result = asAdmin(endpoint, previewRequest(Json.toJson(candidate)));
        assertThatThrownBy(result::block).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(service).get("about");
        verifyNoMoreInteractions(service);
        verifyNoInteractions(renderer, preview);
    }

    @ParameterizedTest
    @ValueSource(strings = {"html", "css", "schema"})
    void invalidCandidateIsRejectedBeforeThemeRendering(String invalidField) {
        var resolver = mock(TemplateNameResolver.class);
        var pageRenderer = new PageRenderer(resolver, new ContentPolicy(), EXTERNAL_URLS);
        var renderingEndpoint = new PageEndpoint(service, pageRenderer,
            new PagePreview(pageRenderer, mock(PublicApiCatalog.class), EXTERNAL_URLS), reviewService);
        var stored = new PageDocument(1, "code", null, null, "<p>Stored</p>", "");
        var candidate = new PageDocument("schema".equals(invalidField) ? 99 : 1, "code", null, null,
            "html".equals(invalidField) ? "<script>alert(1)</script>" : "<p>Unsaved</p>",
            "css".equals(invalidField) ? "@import url('https://example.com/style.css');" : "");
        when(service.get("about")).thenReturn(Mono.just(detail(stored)));
        var result = asAdmin(renderingEndpoint, previewRequest(Json.toJson(candidate)));
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
        verifyNoInteractions(service, renderer, preview);
    }

    @Test
    void anonymousCannotReadDraftPreview() {
        var request = request("/pages/about/preview");
        var result = endpoint.endpoint().route(request).flatMap(handler -> handler.handle(request));
        assertThatThrownBy(result::block).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(service, renderer, preview);
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
        var document = new PageDocument(1, "code", "", Map.of(), "", "");
        when(service.get("about")).thenReturn(Mono.just(new PageDetail(null, document)));
        var authentication = new UsernamePasswordAuthenticationToken("admin", "",
            List.of(new SimpleGrantedAuthority("ROLE_super-role")));
        var response = endpoint.endpoint().route(request).flatMap(handler -> handler.handle(request))
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)).block();
        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
    }
}
