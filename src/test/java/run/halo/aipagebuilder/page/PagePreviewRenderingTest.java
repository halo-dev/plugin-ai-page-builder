package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.RenderingResponse;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.app.theme.TemplateNameResolver;
import run.halo.aipagebuilder.ai.PublicApiCatalog;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;

class PagePreviewRenderingTest {
    private final TemplateNameResolver resolver = mock(TemplateNameResolver.class);
    private final PublicApiCatalog publicApiCatalog = mock(PublicApiCatalog.class);
    private final ContentPolicy policy = mock(ContentPolicy.class);
    private final ExternalUrlSupplier externalUrlSupplier = requestBasedExternalUrl();
    private final PageRenderer renderer =
        new PageRenderer(resolver, policy, externalUrlSupplier);
    private final PagePreview previews =
        new PagePreview(renderer, publicApiCatalog, externalUrlSupplier);
    private final ServerRequest request = ServerRequest.create(
        MockServerWebExchange.from(MockServerHttpRequest.get("http://localhost/p/about")),
        HandlerStrategies.withDefaults().messageReaders());
    private final PageDocument document = new PageDocument(1, "code", "", Map.of(),
        "<p>About</p>", "p { color: red }", "About", "Description");

    @org.junit.jupiter.api.BeforeEach
    void defaultCompiledContent() {
        when(policy.renderCss(any(), any())).thenReturn("");
        when(policy.sanitizeHtml(any())).thenReturn("");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void previewsIsolateScriptsButPublicPagesRetainTheirRuntime(boolean themeLayout) {
        var template = themeLayout ? "ai-page-builder" : "ai-page-builder-standalone";
        when(resolver.resolveTemplateNameOrDefault(any(), eq(template)))
            .thenReturn(Mono.just("plugin:ai-page-builder:" + template));
        when(publicApiCatalog.publicPathPrefixes()).thenReturn(List.of(
            "/apis/api.content.halo.run/v1alpha1/", "/apis/api.photo.halo.run/v1alpha1/"));
        var scripted = new PageDocument(2, "code", null, null, "<p>Body</p>", "", "Title", null,
            "document.querySelector('p').textContent = 'Ready';");
        when(policy.sanitizeHtml(scripted.html())).thenReturn(scripted.html());
        var preview = (RenderingResponse) previews.render(request, "about", "about", themeLayout,
            scripted).block();
        assertThat(preview.headers().getFirst("Content-Security-Policy"))
            .isEqualTo("sandbox allow-scripts; default-src 'none'; "
                + "script-src http://localhost/ 'unsafe-inline' 'unsafe-eval'; "
                + "style-src http://localhost/ 'unsafe-inline'; "
                + "img-src * data: blob:; font-src * data:; media-src *; connect-src "
                + "http://localhost/apis/api.content.halo.run/v1alpha1/ "
                + "http://localhost/apis/api.photo.halo.run/v1alpha1/; "
                + "base-uri 'none'; frame-src 'none'; object-src 'none'; worker-src 'none'; "
                + "form-action 'none'");
        var published = (RenderingResponse) renderer.render(request, "about", "about", themeLayout,
            scripted).block();
        assertThat(published.headers().getFirst("Content-Security-Policy")).isNull();
        assertThat(preview.model()).isEqualTo(published.model());
        assertThat(((PageRenderer.PageView) preview.model().get("aiPageBuilder")).html())
            .contains("<script>");
    }

    @Test
    void previewsWithoutPublicApisBlockEveryConnection() {
        when(publicApiCatalog.publicPathPrefixes()).thenReturn(List.of());
        when(resolver.resolveTemplateNameOrDefault(any(), eq("ai-page-builder")))
            .thenReturn(Mono.just("plugin:ai-page-builder:ai-page-builder"));
        when(policy.sanitizeHtml(document.html())).thenReturn(document.html());
        var preview = (RenderingResponse) previews.render(request, "about", "about", true,
            document).block();
        assertThat(preview.headers().getFirst("Content-Security-Policy"))
            .contains("connect-src 'none'").doesNotContain("allow-same-origin");
    }

    @Test
    void rejectsHostValuesThatCouldInjectCspDirectives() {
        when(publicApiCatalog.publicPathPrefixes()).thenReturn(List.of(
            "/apis/api.content.halo.run/v1alpha1/"));
        when(resolver.resolveTemplateNameOrDefault(any(), eq("ai-page-builder")))
            .thenReturn(Mono.just("plugin:ai-page-builder:ai-page-builder"));
        when(policy.sanitizeHtml(document.html())).thenReturn(document.html());
        var injected = ServerRequest.create(MockServerWebExchange.from(
            MockServerHttpRequest.get("/p/about").header("Host", "a; connect-src *").build()),
            HandlerStrategies.withDefaults().messageReaders());
        var preview = (RenderingResponse) previews.render(injected, "about", "about", true,
            document).block();
        assertThat(preview.headers().getFirst("Content-Security-Policy"))
            .contains("connect-src 'none'").doesNotContain("connect-src *");
    }

    @Test
    void rejectsExternalUrlPathsThatCouldInjectCspDirectives() {
        when(publicApiCatalog.publicPathPrefixes()).thenReturn(List.of(
            "/apis/api.content.halo.run/v1alpha1/"));
        when(resolver.resolveTemplateNameOrDefault(any(), eq("ai-page-builder")))
            .thenReturn(Mono.just("plugin:ai-page-builder:ai-page-builder"));
        when(policy.sanitizeHtml(document.html())).thenReturn(document.html());
        var relative = ServerRequest.create(MockServerWebExchange.from(
            MockServerHttpRequest.get("/p/about").build()),
            HandlerStrategies.withDefaults().messageReaders());
        var preview = (RenderingResponse) new PagePreview(renderer, publicApiCatalog,
            fixedExternalUrl("https://pages.example.com/site;x"))
            .render(relative, "about", "about", true, document).block();
        assertThat(preview.headers().getFirst("Content-Security-Policy"))
            .contains("script-src 'unsafe-inline' 'unsafe-eval'")
            .contains("connect-src 'none'").doesNotContain("site;x");
    }

    @Test
    void previewUsesServingOriginWhilePublicLinksPreferTheConfiguredUrl() {
        when(publicApiCatalog.publicPathPrefixes()).thenReturn(List.of(
            "/apis/api.content.halo.run/v1alpha1/"));
        when(resolver.resolveTemplateNameOrDefault(any(), eq("ai-page-builder")))
            .thenReturn(Mono.just("plugin:ai-page-builder:ai-page-builder"));
        when(policy.sanitizeHtml(document.html())).thenReturn(document.html());
        var external = fixedExternalUrl("https://pages.example.com/site");
        var configuredRenderer = new PageRenderer(resolver, policy, external);
        var preview = (RenderingResponse) new PagePreview(configuredRenderer, publicApiCatalog,
            external).render(request, "about", "about", true, document).block();
        var policy = preview.headers().getFirst("Content-Security-Policy");
        assertThat(policy)
            .contains("connect-src http://localhost/apis/api.content.halo.run/v1alpha1/;")
            .contains("script-src http://localhost/ 'unsafe-inline' 'unsafe-eval'")
            .contains("style-src http://localhost/ 'unsafe-inline'")
            .doesNotContain("pages.example.com");
        assertThat(configuredRenderer.origin(request)).isEqualTo("https://pages.example.com/site");
    }

    @Test
    void previewPolicyFallsBackToTheExternalUrlWhenTheRequestOriginIsUnknown() {
        when(publicApiCatalog.publicPathPrefixes()).thenReturn(List.of(
            "/apis/api.content.halo.run/v1alpha1/"));
        when(resolver.resolveTemplateNameOrDefault(any(), eq("ai-page-builder")))
            .thenReturn(Mono.just("plugin:ai-page-builder:ai-page-builder"));
        when(policy.sanitizeHtml(document.html())).thenReturn(document.html());
        var relative = ServerRequest.create(MockServerWebExchange.from(
            MockServerHttpRequest.get("/p/about").build()),
            HandlerStrategies.withDefaults().messageReaders());
        var preview = (RenderingResponse) new PagePreview(renderer, publicApiCatalog,
            fixedExternalUrl("https://pages.example.com/site"))
            .render(relative, "about", "about", true, document).block();
        var policy = preview.headers().getFirst("Content-Security-Policy");
        assertThat(policy)
            .contains("connect-src "
                + "https://pages.example.com/site/apis/api.content.halo.run/v1alpha1/;")
            .contains("script-src https://pages.example.com/site/ 'unsafe-inline' 'unsafe-eval'");
    }

    @Test
    void previewsBlockApisWhenTheCatalogIsUnavailable() {
        when(publicApiCatalog.publicPathPrefixes())
            .thenThrow(new IllegalStateException("接口目录正在变化，请重试"));
        when(resolver.resolveTemplateNameOrDefault(any(), eq("ai-page-builder")))
            .thenReturn(Mono.just("plugin:ai-page-builder:ai-page-builder"));
        when(policy.sanitizeHtml(document.html())).thenReturn(document.html());
        var preview = (RenderingResponse) previews.render(request, "about", "about", true,
            document).block();
        assertThat(preview.headers().getFirst("Content-Security-Policy"))
            .contains("connect-src 'none'");
    }

    private static ExternalUrlSupplier requestBasedExternalUrl() {
        return new ExternalUrlSupplier() {
            @Override
            public URI get() {
                return URI.create("/");
            }

            @Override
            public URL getURL(HttpRequest request) {
                try {
                    // Halo resolves the configured base path against the request URI, so the
                    // page path is not part of the returned external URL.
                    return request.getURI().resolve("/").toURL();
                } catch (MalformedURLException | IllegalArgumentException error) {
                    throw new IllegalStateException(error);
                }
            }

            @Override
            public URL getRaw() {
                return null;
            }
        };
    }

    private static ExternalUrlSupplier fixedExternalUrl(String value) {
        var uri = URI.create(value);
        return new ExternalUrlSupplier() {
            @Override
            public URI get() {
                return uri;
            }

            @Override
            public URL getURL(HttpRequest request) {
                try {
                    return uri.toURL();
                } catch (MalformedURLException error) {
                    throw new IllegalStateException(error);
                }
            }

            @Override
            public URL getRaw() {
                return null;
            }
        };
    }
}
