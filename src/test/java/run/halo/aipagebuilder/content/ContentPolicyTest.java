package run.halo.aipagebuilder.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import run.halo.aipagebuilder.model.PageDocument;
import run.halo.aipagebuilder.theme.ThemeStyleContext;

class ContentPolicyTest {
    private final ContentPolicy policy = new ContentPolicy();

    @Test
    void capabilitiesDescribeAttributePrefixesAcceptedByValidation() {
        assertThat(policy.capabilities().get("rules").toString())
            .contains("aria-*", "data-*", "data-gjs-*");
        assertThatCode(() -> policy.validateHtml("<p aria-label='a' data-section='b'>Text</p>"))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validateHtml("<p data-gjs-script='x'>Text</p>"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void acceptsStaticContentAndEmptyDraft() {
        assertThatCode(() -> policy.validateHtml("")).doesNotThrowAnyException();
        assertThatCode(() -> policy.validateCss("")).doesNotThrowAnyException();
        assertThatCode(() -> policy.validateHtml("""
            <section class="hero" data-section="intro" aria-label="简介">
              <h1 style="color: #123; margin: 1rem">Hello</h1>
              <img src="/upload/banner.png" alt="封面" loading="lazy">
              <a href="https://example.com" target="_blank" rel="noopener">详情</a>
            </section>
            """)).doesNotThrowAnyException();
        assertThatCode(() -> policy.validateHtml(
            "<address>杭州市西湖区文三路 120 号<br><a href=\"tel:057188886666\">0571-88886666</a></address>"))
            .doesNotThrowAnyException();
        assertThatCode(() -> policy.validateHtml("<label>邮箱</label>"))
            .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<script>alert(1)</script>", "<img src=x onerror=alert(1)>",
        "<a href='java&#x73;cript:alert(1)'>click</a>", "<iframe src='/console'></iframe>",
        "<svg onload='alert(1)'></svg>", "<div th:replace='x'>x</div>",
        "<div data-gjs-script='alert(1)'>x</div>", "<img src='data:image/svg+xml,test'>",
        "<form action='/api'>form</form>", "<body><p>full document</p></body>"
    })
    void rejectsExecutableHtml(String html) {
        assertThatThrownBy(() -> policy.validateHtml(html)).isInstanceOf(ResponseStatusException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "@import url('https://example.com/a.css');", "@font-face {font-family:x;src:url('/x')}",
        "@keyframes fade {from{opacity:0}to{opacity:1}}", ".x{background:url(javascript:alert(1))}",
        ".x{color:red}</style><script>alert(1)</script>", ".x{width:expression(alert(1))}",
        ".x{background:url(\\6a avascript:x)}", ".x{background:image-set('javascript:x' 1x)}",
        ".x{behavior:url('/x')}", ".x { & .y {color:red} }"
    })
    void rejectsUnsupportedCss(String css) {
        assertThatThrownBy(() -> policy.validateCss(css)).isInstanceOf(ResponseStatusException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"opener", "OPENER", "nofollow Opener", "noopener opener"})
    void neutralizesOpenerRelInsteadOfRejectingLegacyPages(String rel) {
        var html = "<a href='https://example.com' target='_blank' rel='" + rel + "'>x</a>";
        assertThatCode(() -> policy.validateHtml(html)).doesNotThrowAnyException();
        assertThat(policy.sanitizeHtml(html).toLowerCase(Locale.ROOT)).contains("noopener");
    }

    @Test
    void sanitizeHtmlOutputsTheParsedDomAndAddsNoopenerToBlankLinks() {
        var output = policy.sanitizeHtml(
            "<DIV class=hero><a href='https://example.com' target='_blank'>go</a><p>Body");
        assertThat(output)
            .startsWith("<div class=\"hero\">")
            .contains("rel=\"noopener\"")
            .contains("<p>Body</p>");
        assertThat(policy.sanitizeHtml(
            "<a href='/x' target='_blank' rel='nofollow noopener'>x</a>"))
            .contains("rel=\"nofollow noopener\"");
        assertThatThrownBy(() -> policy.sanitizeHtml("<script>alert(1)</script>"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void sanitizeHtmlPreservesTextWhitespace() {
        assertThat(policy.sanitizeHtml(
            "<div style='white-space:pre'>A  B\n C</div><span>a</span><span>b</span>"))
            .isEqualTo("<div style=\"white-space:pre\">A  B\n C</div><span>a</span><span>b</span>");
    }

    @Test
    void sanitizeHtmlPreservesLeadingBlankLinesInPreformattedText() {
        var output = policy.sanitizeHtml("<pre>\n\nA</pre>");
        assertThat(output).isEqualTo("<pre>\n\nA</pre>");
        // HTML5 parsers drop one leading LF inside <pre>; the output must survive that.
        assertThat(org.jsoup.Jsoup.parseBodyFragment(output).body().selectFirst("pre").wholeText())
            .isEqualTo("\nA");
    }

    @ParameterizedTest
    @ValueSource(strings = {"&#10;&#10;", "&#xA;&#xA;", "&NewLine;&NewLine;"})
    void sanitizeHtmlDoesNotAddAnExtraLineForEncodedLeadingNewlines(String newlines) {
        // A browser drops one leading newline even when it comes from a character
        // reference; Jsoup does not, so the compensation must not add another one.
        var output = policy.sanitizeHtml("<pre>" + newlines + "A</pre>");
        assertThat(output).isEqualTo("<pre>\n\nA</pre>");
        assertThat(org.jsoup.Jsoup.parseBodyFragment(output).body().selectFirst("pre").wholeText())
            .isEqualTo("\nA");
    }

    @ParameterizedTest
    @ValueSource(strings = {"<pre class=x\"y>\n\nA</pre>", "<pre title=don't>\n\nA</pre>"})
    void sanitizeHtmlPreservesLeadingLinesWithUnquotedQuotedAttributes(String html) {
        var output = policy.sanitizeHtml(html);
        assertThat(org.jsoup.Jsoup.parseBodyFragment(output).body().selectFirst("pre").wholeText())
            .isEqualTo("\nA");
    }

    @Test
    void reportsParseLocationForBrokenCss() {
        assertThatThrownBy(() -> policy.validateCss(".a { color: red"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("CSS 语法无效：");
    }

    @Test
    void scopesEverySelectorIncludingNestedMediaAndSupports() {
        var result = policy.renderCss("""
            body { margin: 0; }
            .hero, h1 { color: red; }
            @media (max-width: 600px) { .hero { display: block; } }
            @supports (display: grid) { .cards { display: grid; } }
            """, "ai-page-builder-test");
        assertThat(result).contains("#ai-page-builder-test {", "#ai-page-builder-test .hero",
            "#ai-page-builder-test h1", "#ai-page-builder-test .cards", "@media", "@supports");
        assertThat(result).doesNotContain("#ai-page-builder-test body");
    }

    @ParameterizedTest
    @ValueSource(strings = {"html.dark", "html.light", ":root.dark", ":root.light",
        "html[data-theme=\"dark\"]", "html[data-theme='light']",
        ":root[data-theme=dark]", ":root[data-theme=\"light\"]"})
    void scopesExplicitRootConditionsAsAncestors(String condition) {
        var result = policy.renderCss(condition + " {color:red} " + condition
            + " .card, .other {color:blue}", "ai-page-builder-test");
        var sheet = com.helger.css.reader.CSSReader.readFromString(result);
        for (var rule : sheet.getAllStyleRules()) {
            for (var selector : rule.getAllSelectors()) {
                var text = selector.getAsCSSString(new com.helger.css.writer.CSSWriterSettings(true), 0);
                assertThat(text).contains("#ai-page-builder-test");
                assertThat(text).doesNotStartWith("#ai-page-builder-test html")
                    .doesNotStartWith("#ai-page-builder-test :root");
            }
        }
        assertThat(result).contains("#ai-page-builder-test .card", "#ai-page-builder-test .other");
    }

    @Test
    void scopesRootConditionsRecursivelyWithoutMovingNormalDescendantSiblingsOutsideRoot() {
        var result = policy.renderCss("""
            @supports (display: grid) {
              @media (min-width: 600px) {
                html.dark .card + .card, :root.light .card ~ .other {color: red}
              }
            }
            """, "ai-page-builder-test");
        assertThat(result).contains("html.dark #ai-page-builder-test .card+.card",
            ":root.light #ai-page-builder-test .card~.other");
    }

    @ParameterizedTest
    @ValueSource(strings = {"html.dark + .outside", "html.light ~ footer", ":root.dark > .card",
        "html.dark:hover .card", "html[data-theme=dark i] .card", "html.dark.light .card",
        ":root:is(.dark) .card", "html[data-theme=system] .card"})
    void rejectsUnsupportedRootConditionsAndRootSiblingEscapes(String selector) {
        assertThatThrownBy(() -> policy.validateCss(selector + "{color:red}"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void returnsRepairFeedbackForSystemColorConditionsOnlyWithExplicitThemeControl() {
        var explicit = new ThemeStyleContext("theme", "Theme", "SUPPORTED", List.of(), List.of(), "",
            new ThemeStyleContext.ColorMode("explicit", List.of(), List.of()));
        var legacy = new ThemeStyleContext("theme", "Theme", "SUPPORTED", List.of(), List.of(), "");
        var css = "@supports (display:grid) {@media screen and (prefers-color-scheme: dark) {.x{color:red}}}";
        assertThatThrownBy(() -> policy.validateThemeColorMode(css, explicit))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("请移除 prefers-color-scheme");
        assertThatCode(() -> policy.validateThemeColorMode(css, legacy)).doesNotThrowAnyException();
        assertThatCode(() -> policy.validateThemeColorMode(
            "/* prefers-color-scheme */ .x {content:'prefers-color-scheme'; color:red}", explicit))
            .doesNotThrowAnyException();
        assertThatCode(() -> policy.validateThemeColorMode("html.dark .x {color:white}", explicit))
            .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not all and (prefers-color-scheme: dark)",
        "(max-width: 600px), (prefers-color-scheme: light)",
        "not (prefers-color-scheme: dark)",
        "(prefers-color-scheme: dark) or (max-width: 600px)"})
    void rejectsSystemColorMediaAlternativesForExplicitThemes(String condition) {
        var explicit = new ThemeStyleContext("theme", "Theme", "SUPPORTED", List.of(), List.of(), "",
            new ThemeStyleContext.ColorMode("explicit", List.of(), List.of()));
        assertThatThrownBy(() -> policy.validateThemeColorMode(
            "@media " + condition + " {.x{color:red}}", explicit))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void supportsLegacyDocumentsAndVersionTwoScripts() {
        var legacy = new PageDocument(1, "code", null, null, "<p>Hi</p>", "", "Title", null);
        var nullable = new PageDocument(1, "code", null, null, "", "", "Title", null, null);
        assertThat(legacy.js()).isEmpty();
        assertThat(nullable.js()).isEmpty();
        assertThatCode(() -> policy.validate(legacy)).doesNotThrowAnyException();
        assertThatCode(() -> policy.validate(new PageDocument(2, "code", null, null,
            "<button>Run</button>", "", "Title", null, "document.body.dataset.ready = 'yes';")))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validate(new PageDocument(1, "code", null, null,
            "", "", "Title", null, "void 0;"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> policy.validate(new PageDocument(3, "code", null, null,
            "", "", "Title", null))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void validatesJavaScriptTransportWithoutClaimingSyntaxOrSecurityValidation() {
        assertThatCode(() -> policy.validateJs("not valid JavaScript </script> fetch('/api')"))
            .doesNotThrowAnyException();
        assertThatCode(() -> policy.validateJs("x".repeat(100_000))).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validateJs("x".repeat(100_001)))
            .isInstanceOf(ResponseStatusException.class);
        assertThatCode(() -> policy.validateJs("alert('\\u0000')")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validateJs("alert('" + (char) 0 + "')"))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> policy.validateJs(null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> policy.validate(new PageDocument(2, "code", null, null,
            "<script>void 0</script>", "", "Title", null, "void 0")))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void sourceModeDoesNotRequireEditorData() {
        var document = new PageDocument(1, "code", null, null, "<h1>Hello</h1>", "", "Title", null);
        assertThatCode(() -> policy.validate(document)).doesNotThrowAnyException();
    }

    @Test
    void preservesGrapesDocumentShellMetadata() {
        var project = Map.<String, Object>of("dataSources", List.of(), "pages", List.of(
            Map.of("frames", List.of(Map.of("component", Map.of("type", "wrapper", "tagName", "div",
                "head", Map.of("type", "head"), "docEl", Map.of("tagName", "html"),
                "components", List.of(Map.of("tagName", "h1", "content", "Hello"))))))));
        var document = new PageDocument(1, "visual", "0.22.16", project,
            "<div><h1>Hello</h1></div>", "", "Title", "");
        assertThatCode(() -> policy.validate(document)).doesNotThrowAnyException();
    }

    @Test
    void rejectsScriptsHiddenOnlyInEditorData() {
        var document = new PageDocument(1, "visual", "1", Map.of("pages", List.of(
            Map.of("component", Map.of("script", "alert(1)", "tagName", "div")))),
            "<div>safe export</div>", "", "Title", "");
        assertThatThrownBy(() -> policy.validate(document))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("脚本");
    }

    @Test
    void rejectsEventAttributesHiddenOnlyInEditorData() {
        var document = new PageDocument(1, "visual", "1", Map.of("pages", List.of(
            Map.of("component", Map.of("tagName", "img", "attributes",
                Map.of("src", "/x.png", "onerror", "alert(1)"))))),
            "<img src='/x.png'>", "", "Title", "");
        assertThatThrownBy(() -> policy.validate(document))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("属性");
    }
}
