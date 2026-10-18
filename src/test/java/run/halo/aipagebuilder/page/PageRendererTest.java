package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.RenderingResponse;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.app.theme.TemplateNameResolver;
import run.halo.aipagebuilder.ai.PublicApiCatalog;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;

class PageRendererTest {
    private final TemplateNameResolver resolver = mock(TemplateNameResolver.class);
    private final PublicApiCatalog publicApiCatalog = mock(PublicApiCatalog.class);
    private final ContentPolicy policy = mock(ContentPolicy.class);
    private final ExternalUrlSupplier externalUrlSupplier = requestBasedExternalUrl();
    private final PageRenderer renderer =
        new PageRenderer(resolver, policy, publicApiCatalog, externalUrlSupplier);
    private final ServerRequest request = ServerRequest.create(
        MockServerWebExchange.from(MockServerHttpRequest.get("http://localhost/p/about")),
        HandlerStrategies.withDefaults().messageReaders());
    private final PageDocument document = new PageDocument(1, "code", "", Map.of(),
        "<p>About</p>", "p { color: red }", "About", "Description");

    @Test
    void usesThemeResolverAndPassesOnlyPublicViewWithScopedCss() {
        when(resolver.resolveTemplateNameOrDefault(any(), eq("ai-page-builder")))
            .thenReturn(Mono.just("plugin:ai-page-builder:ai-page-builder"));
        when(policy.renderCss(document.css(), "ai-page-builder-about"))
            .thenReturn("#ai-page-builder-about p { color: red }");
        when(policy.sanitizeHtml(document.html())).thenReturn(document.html());
        var response = (RenderingResponse) renderer.render(request, "about", "about/company", true, document).block();
        assertThat(response.name()).isEqualTo("plugin:ai-page-builder:ai-page-builder");
        assertThat(response.headers().getCacheControl()).isEqualTo("no-store");
        assertThat(response.model()).containsOnlyKeys("aiPageBuilder", "_templateId");
        var view = (PageRenderer.PageView) response.model().get("aiPageBuilder");
        assertThat(view.rootId()).isEqualTo("ai-page-builder-about");
        assertThat(view.slug()).isEqualTo("about/company");
        assertThat(view.title()).isEqualTo("About");
        assertThat(view.html()).isEqualTo(document.html());
        assertThat(view.css()).startsWith("#ai-page-builder-about");
    }

    @Test
    void interactiveRenderingKeepsBodyAndScopedCssInCustomThemeContract() {
        when(resolver.resolveTemplateNameOrDefault(any(), eq("ai-page-builder")))
            .thenReturn(Mono.just("theme:custom-ai-page-builder"));
        var interactive = new PageDocument(2, "code", null, null, "<p>Interactive</p>",
            "p {color: red} html.dark p {color: white}", "Title", null,
            "document.querySelector('#ai-page-builder-about p').textContent = 'Ready';");
        var response = (RenderingResponse) new PageRenderer(resolver, new ContentPolicy(),
            publicApiCatalog, externalUrlSupplier)
            .render(request, "about", "about/company", true, interactive).block();
        var view = (PageRenderer.PageView) response.model().get("aiPageBuilder");
        assertThat(response.name()).isEqualTo("theme:custom-ai-page-builder");
        assertThat(response.model()).containsOnlyKeys("aiPageBuilder", "_templateId");
        assertThat(view.css()).contains("#ai-page-builder-about p", "html.dark #ai-page-builder-about p");
        assertThat(view.html()).startsWith(interactive.html());
        var body = Jsoup.parseBodyFragment(view.html()).body();
        assertThat(body.children().stream().map(element -> element.normalName()).toList()).containsExactly("p", "script");
        assertThat(body.select("iframe, style")).isEmpty();
        assertThat(body.selectFirst("script").data()).doesNotContain("postMessage", "MutationObserver");
    }

    @Test
    void scriptEndTagsRemainEncodedAndPreserveUtf8Source() {
        var js = "const text = '</script><script>window.compromised = true</script>';\r\n"
            + "document.body.dataset.text = '中文 < & > </ScRiPt> <!--';";
        var interactive = new PageDocument(2, "code", null, null, "<p>Body</p>", "",
            "Title", null, js);
        when(resolver.resolveTemplateNameOrDefault(any(), eq("ai-page-builder")))
            .thenReturn(Mono.just("plugin:ai-page-builder:ai-page-builder"));
        var response = (RenderingResponse) new PageRenderer(resolver, new ContentPolicy(),
            publicApiCatalog, externalUrlSupplier)
            .render(request, "about", "about/company", true, interactive).block();
        var view = (PageRenderer.PageView) response.model().get("aiPageBuilder");
        var body = Jsoup.parseBodyFragment(view.html()).body();
        assertThat(body.children().stream().map(element -> element.normalName()).toList()).containsExactly("p", "script");
        assertThat(body.select("script")).hasSize(1);
        var bootstrap = body.selectFirst("script").data();
        assertThat(bootstrap).doesNotContain(js, "compromised", "</script>", "</ScRiPt>");
        var encoded = bootstrap.substring(bootstrap.indexOf("atob('") + 6,
            bootstrap.indexOf("'), char"));
        assertThat(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8))
            .isEqualTo(js);
        assertThat(bootstrap).contains("new TextDecoder().decode(",
            "document.currentScript.parentNode.appendChild(script)");
    }

    @Test
    void standalonePagesResolveTheStandaloneTemplate() {
        when(resolver.resolveTemplateNameOrDefault(any(), eq("ai-page-builder-standalone")))
            .thenReturn(Mono.just("plugin:ai-page-builder:ai-page-builder-standalone"));
        when(policy.renderCss(document.css(), "ai-page-builder-about"))
            .thenReturn("#ai-page-builder-about p { color: red }");
        when(policy.sanitizeHtml(document.html())).thenReturn(document.html());
        var response = (RenderingResponse) renderer.render(request, "about", "about/company",
            false, document).block();
        assertThat(response.name()).isEqualTo("plugin:ai-page-builder:ai-page-builder-standalone");
        assertThat(response.model().get("_templateId"))
            .isEqualTo("plugin:ai-page-builder:ai-page-builder-standalone");
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
        var preview = (RenderingResponse) renderer.preview(request, "about", "about", themeLayout,
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
        var preview = (RenderingResponse) renderer.preview(request, "about", "about", true,
            document).block();
        assertThat(preview.headers().getFirst("Content-Security-Policy"))
            .contains("connect-src 'none'").doesNotContain("allow-same-origin");
    }

    @Test
    void superAdministratorViewsStripScriptsAndFlagTheTemplate() {
        when(resolver.resolveTemplateNameOrDefault(any(), eq("ai-page-builder")))
            .thenReturn(Mono.just("plugin:ai-page-builder:ai-page-builder"));
        var scripted = new PageDocument(2, "code", null, null, "<p>Body</p>", "", "Title", null,
            "document.querySelector('p').textContent = 'Ready';");
        when(policy.sanitizeHtml(scripted.html())).thenReturn(scripted.html());
        var response = (RenderingResponse) renderer.render(request, "about", "about", true,
            scripted, true).block();
        var view = (PageRenderer.PageView) response.model().get("aiPageBuilder");
        assertThat(view.scriptBlocked()).isTrue();
        assertThat(view.html()).isEqualTo(scripted.html()).doesNotContain("<script");

        var staticPage = new PageDocument(2, "code", null, null, "<p>Body</p>", "", "Title", null,
            "");
        when(policy.sanitizeHtml(staticPage.html())).thenReturn(staticPage.html());
        var staticResponse = (RenderingResponse) renderer.render(request, "about", "about", true,
            staticPage, true).block();
        var staticView = (PageRenderer.PageView) staticResponse.model().get("aiPageBuilder");
        assertThat(staticView.scriptBlocked()).isFalse();
    }

    @Test
    void rendersTheSanitizedDomInsteadOfTheRawSource() {
        when(resolver.resolveTemplateNameOrDefault(any(), eq("ai-page-builder")))
            .thenReturn(Mono.just("plugin:ai-page-builder:ai-page-builder"));
        var unclosed = new PageDocument(1, "code", null, null,
            "<DIV class=hero><a href=\"https://example.com\" target=_blank>go</a><p>Body", "",
            "Title", null);
        var response = (RenderingResponse) new PageRenderer(resolver, new ContentPolicy(),
            publicApiCatalog, externalUrlSupplier).render(request, "about", "about", true, unclosed)
            .block();
        var view = (PageRenderer.PageView) response.model().get("aiPageBuilder");
        assertThat(view.html())
            .startsWith("<div class=\"hero\">")
            .contains("rel=\"noopener\"")
            .contains("<p>Body</p>");
    }

    @Test
    void previewPolicyUsesTheExternalUrlIncludingItsBasePath() {
        when(publicApiCatalog.publicPathPrefixes()).thenReturn(List.of(
            "/apis/api.content.halo.run/v1alpha1/"));
        when(resolver.resolveTemplateNameOrDefault(any(), eq("ai-page-builder")))
            .thenReturn(Mono.just("plugin:ai-page-builder:ai-page-builder"));
        when(policy.sanitizeHtml(document.html())).thenReturn(document.html());
        var preview = (RenderingResponse) new PageRenderer(resolver, policy, publicApiCatalog,
            fixedExternalUrl("https://pages.example.com/site"))
            .preview(request, "about", "about", true, document).block();
        var policy = preview.headers().getFirst("Content-Security-Policy");
        assertThat(policy)
            .contains("connect-src "
                + "https://pages.example.com/site/apis/api.content.halo.run/v1alpha1/;")
            .doesNotContain("http://localhost")
            .contains("script-src https://pages.example.com/site/ "
                + "'unsafe-inline' 'unsafe-eval'")
            .contains("style-src https://pages.example.com/site/ 'unsafe-inline'");
    }

    @Test
    void previewsBlockApisWhenTheCatalogIsUnavailable() {
        when(publicApiCatalog.publicPathPrefixes())
            .thenThrow(new IllegalStateException("接口目录正在变化，请重试"));
        when(resolver.resolveTemplateNameOrDefault(any(), eq("ai-page-builder")))
            .thenReturn(Mono.just("plugin:ai-page-builder:ai-page-builder"));
        when(policy.sanitizeHtml(document.html())).thenReturn(document.html());
        var preview = (RenderingResponse) renderer.preview(request, "about", "about", true,
            document).block();
        assertThat(preview.headers().getFirst("Content-Security-Policy"))
            .contains("connect-src 'none'");
    }

    @Test
    void invalidPersistedContentIsRejectedBeforeTemplateRendering() {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsafe content"))
            .when(policy).validate(document);
        assertThatThrownBy(() -> renderer.render(request, "about", "about/company", true, document).block())
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(resolver);
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
