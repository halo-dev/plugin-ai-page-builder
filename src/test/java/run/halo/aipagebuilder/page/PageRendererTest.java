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
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;

class PageRendererTest {
    private final TemplateNameResolver resolver = mock(TemplateNameResolver.class);
    private final ContentPolicy policy = mock(ContentPolicy.class);
    private final ExternalUrlSupplier externalUrlSupplier = requestBasedExternalUrl();
    private final PageRenderer renderer =
        new PageRenderer(resolver, policy, externalUrlSupplier);
    private final ServerRequest request = ServerRequest.create(
        MockServerWebExchange.from(MockServerHttpRequest.get("http://localhost/p/about")),
        HandlerStrategies.withDefaults().messageReaders());
    private final PageDocument document = new PageDocument(1, "code", "", Map.of(),
        "<p>About</p>", "p { color: red }");

    @org.junit.jupiter.api.BeforeEach
    void defaultCompiledContent() {
        when(policy.renderCss(any(), any())).thenReturn("");
        when(policy.sanitizeHtml(any())).thenReturn("");
    }

    @Test
    void usesThemeResolverAndPassesOnlyPublicViewWithScopedCss() {
        when(resolver.resolveTemplateNameOrDefault(any(), eq("ai-page-builder")))
            .thenReturn(Mono.just("plugin:ai-page-builder:ai-page-builder"));
        when(policy.renderCss(document.css(), "ai-page-builder-about"))
            .thenReturn("#ai-page-builder-about p { color: red }");
        when(policy.sanitizeHtml(document.html())).thenReturn(document.html());
        var response = (RenderingResponse) renderer.render(request, "about", "about/company", true, document, "About", "Description").block();
        assertThat(response.name()).isEqualTo("plugin:ai-page-builder:ai-page-builder");
        assertThat(response.headers().getCacheControl()).isEqualTo("no-store");
        assertThat(response.model()).containsOnlyKeys("aiPageBuilder", "seoMetaDescription",
            "_templateId");
        assertThat(response.model().get("seoMetaDescription")).isEqualTo("Description");
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
            "p {color: red} html.dark p {color: white}",
            "document.querySelector('#ai-page-builder-about p').textContent = 'Ready';");
        var response = (RenderingResponse) new PageRenderer(resolver, new ContentPolicy(),
            externalUrlSupplier)
            .render(request, "about", "about/company", true, interactive, "About", "Description").block();
        var view = (PageRenderer.PageView) response.model().get("aiPageBuilder");
        assertThat(response.name()).isEqualTo("theme:custom-ai-page-builder");
        assertThat(response.model()).containsOnlyKeys("aiPageBuilder", "seoMetaDescription",
            "_templateId");
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
        var interactive = new PageDocument(2, "code", null, null, "<p>Body</p>", "", js);
        when(resolver.resolveTemplateNameOrDefault(any(), eq("ai-page-builder")))
            .thenReturn(Mono.just("plugin:ai-page-builder:ai-page-builder"));
        var response = (RenderingResponse) new PageRenderer(resolver, new ContentPolicy(),
            externalUrlSupplier)
            .render(request, "about", "about/company", true, interactive, "About", "Description").block();
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
            false, document, "About", "Description").block();
        assertThat(response.name()).isEqualTo("plugin:ai-page-builder:ai-page-builder-standalone");
        assertThat(response.model().get("_templateId"))
            .isEqualTo("plugin:ai-page-builder:ai-page-builder-standalone");
    }

    @Test
    void importedPagesWithoutADescriptionOmitTheSeoVariable() {
        when(resolver.resolveTemplateNameOrDefault(any(), eq("ai-page-builder-standalone")))
            .thenReturn(Mono.just("plugin:ai-page-builder:ai-page-builder-standalone"));
        var response = (RenderingResponse) renderer.render(request, "about", "about/company",
            false, document, "About", null).block();
        assertThat(response.model()).doesNotContainKey("seoMetaDescription");
    }

    @Test
    void superAdministratorViewsStripScriptsAndFlagTheTemplate() {
        when(resolver.resolveTemplateNameOrDefault(any(), eq("ai-page-builder")))
            .thenReturn(Mono.just("plugin:ai-page-builder:ai-page-builder"));
        var scripted = new PageDocument(2, "code", null, null, "<p>Body</p>", "",
            "document.querySelector('p').textContent = 'Ready';");
        when(policy.sanitizeHtml(scripted.html())).thenReturn(scripted.html());
        var response = (RenderingResponse) renderer.render(request, "about", "about", true,
            scripted, "About", "Description", true).block();
        var view = (PageRenderer.PageView) response.model().get("aiPageBuilder");
        assertThat(view.scriptBlocked()).isTrue();
        assertThat(view.html()).isEqualTo(scripted.html()).doesNotContain("<script");

        var staticPage = new PageDocument(2, "code", null, null, "<p>Body</p>", "",
            "");
        when(policy.sanitizeHtml(staticPage.html())).thenReturn(staticPage.html());
        var staticResponse = (RenderingResponse) renderer.render(request, "about", "about", true,
            staticPage, "About", "Description", true).block();
        var staticView = (PageRenderer.PageView) staticResponse.model().get("aiPageBuilder");
        assertThat(staticView.scriptBlocked()).isFalse();
    }

    @Test
    void rendersTheSanitizedDomInsteadOfTheRawSource() {
        when(resolver.resolveTemplateNameOrDefault(any(), eq("ai-page-builder")))
            .thenReturn(Mono.just("plugin:ai-page-builder:ai-page-builder"));
        var unclosed = new PageDocument(1, "code", null, null,
            "<DIV class=hero><a href=\"https://example.com\" target=_blank>go</a><p>Body", "");
        var response = (RenderingResponse) new PageRenderer(resolver, new ContentPolicy(),
            externalUrlSupplier).render(request, "about", "about", true, unclosed, "About", "Description")
            .block();
        var view = (PageRenderer.PageView) response.model().get("aiPageBuilder");
        assertThat(view.html())
            .startsWith("<div class=\"hero\">")
            .contains("rel=\"noopener\"")
            .contains("<p>Body</p>");
    }

    @Test
    void invalidPersistedContentIsRejectedBeforeTemplateRendering() {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsafe content"))
            .when(policy).validate(document);
        assertThatThrownBy(() -> renderer.render(request, "about", "about/company", true, document, "About", "Description").block())
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

}
