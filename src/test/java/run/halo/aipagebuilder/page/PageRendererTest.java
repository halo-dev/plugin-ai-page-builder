package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.RenderingResponse;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.theme.TemplateNameResolver;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;

class PageRendererTest {
    private final TemplateNameResolver resolver = mock(TemplateNameResolver.class);
    private final ContentPolicy policy = mock(ContentPolicy.class);
    private final PageRenderer renderer = new PageRenderer(resolver, policy);
    private final ServerRequest request = ServerRequest.create(
        MockServerWebExchange.from(MockServerHttpRequest.get("/p/about")),
        HandlerStrategies.withDefaults().messageReaders());
    private final PageDocument document = new PageDocument(1, "code", "", Map.of(),
        "<p>About</p>", "p { color: red }", "About", "Description");

    @Test
    void usesThemeResolverAndPassesOnlyPublicViewWithScopedCss() {
        when(resolver.resolveTemplateNameOrDefault(any(), eq("ai-page-builder")))
            .thenReturn(Mono.just("plugin:ai-page-builder:ai-page-builder"));
        when(policy.renderCss(document.css(), "ai-page-builder-about"))
            .thenReturn("#ai-page-builder-about p { color: red }");
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
        var response = (RenderingResponse) new PageRenderer(resolver, new ContentPolicy())
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
        var response = (RenderingResponse) new PageRenderer(resolver, new ContentPolicy())
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
        var response = (RenderingResponse) renderer.render(request, "about", "about/company",
            false, document).block();
        assertThat(response.name()).isEqualTo("plugin:ai-page-builder:ai-page-builder-standalone");
        assertThat(response.model().get("_templateId"))
            .isEqualTo("plugin:ai-page-builder:ai-page-builder-standalone");
    }

    @Test
    void invalidPersistedContentIsRejectedBeforeTemplateRendering() {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsafe content"))
            .when(policy).validate(document);
        assertThatThrownBy(() -> renderer.render(request, "about", "about/company", true, document).block())
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(resolver);
    }
}
