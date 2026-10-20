package run.halo.aipagebuilder.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import run.halo.aipagebuilder.model.PageDocument;
import run.halo.aipagebuilder.theme.ThemeStyleContext;

class PageSourceEditsTest {
    private final PageSourceEdits edits = new PageSourceEdits(new ContentPolicy());

    @ParameterizedTest
    @ValueSource(strings = {"html", "css", "js"})
    void sourceChangesConvertVisualContentAndPreserveMetadata(String field) {
        var base = visual("", 1);
        var html = field.equals("html") ? "<p>New</p>" : base.html();
        var css = field.equals("css") ? "p{color:blue}" : base.css();
        var js = field.equals("js") ? "console.log('ok')" : base.js();
        var prepared = edits.prepare(base, html, css, js);
        assertThat(prepared.sourceChanged()).isTrue();
        assertThat(prepared.cssChanged()).isEqualTo(field.equals("css"));
        assertThat(prepared.finish(null)).isEqualTo(new PageDocument(
            js.isEmpty() ? 1 : 2, "code", null, null, html, css,
            base.title(), base.description(), js));
        assertThat(base.mode()).isEqualTo("visual");
        assertThat(base.projectData()).isNotNull();
    }

    @Test
    void noOpPreservesOriginalDocumentIncludingLegacySchemaAndEditorData() {
        var base = visual("", 2);
        var prepared = edits.prepare(base, base.html(), base.css(), base.js());
        assertThat(prepared.sourceChanged()).isFalse();
        assertThat(prepared.cssChanged()).isFalse();
        assertThat(prepared.finish(explicitTheme())).isSameAs(base);
    }

    @Test
    void removingLastScriptReturnsToSchemaOne() {
        var base = visual("console.log('ok')", 2);
        assertThat(edits.prepare(base, base.html(), base.css(), "").finish(null).schemaVersion())
            .isEqualTo(1);
    }

    @Test
    void noOpStillValidatesWholeDocument() {
        var base = new PageDocument(1, "code", null, null,
            "<script>bad</script>", "", "Title", "");
        assertThatThrownBy(() -> edits.prepare(base, base.html(), base.css(), base.js()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("不支持的 HTML 元素");
    }

    @Test
    void generalValidationRejectsCandidateBeforeThemeCompletion() {
        var base = new PageDocument(1, "code", null, null, "<p>Old</p>", "", "", "");
        assertThatThrownBy(() -> edits.prepare(base, "<p>New</p>",
            "@media(prefers-color-scheme:dark){p{color:red}}", ""))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("页面标题");
    }

    @Test
    void changedCssRequiresThemeValidationButUnchangedLegacyCssCanAccompanyTextEdits() {
        var css = "@media(prefers-color-scheme:dark){p{color:red}}";
        var base = new PageDocument(1, "code", null, null, "<p>Old</p>", css, "Title", "");
        var textEdit = edits.prepare(base, "<p>New</p>", css, "");
        assertThat(textEdit.cssChanged()).isFalse();
        assertThat(textEdit.finish(explicitTheme()).html()).isEqualTo("<p>New</p>");

        var cssEdit = edits.prepare(base, "<p>New</p>", css.replace("red", "blue"), "");
        assertThat(cssEdit.cssChanged()).isTrue();
        assertThatThrownBy(() -> cssEdit.finish(explicitTheme()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("显式根状态");
        assertThat(base.html()).isEqualTo("<p>Old</p>");
        assertThat(base.css()).isEqualTo(css);
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "unavailable", "standalone"})
    void missingThemeContractDoesNotRejectChangedCss(String context) {
        var base = visual("", 1);
        var theme = switch (context) {
            case "unavailable" -> ThemeStyleContext.unavailable();
            case "standalone" -> ThemeStyleContext.standalone();
            default -> null;
        };
        var css = "@media(prefers-color-scheme:dark){p{color:red}}";
        assertThat(edits.prepare(base, base.html(), css, "").finish(theme).css()).isEqualTo(css);
    }

    private static PageDocument visual(String js, int schema) {
        return new PageDocument(schema, "visual", "editor", Map.of("components", List.of()),
            "<p>Old</p>", "p{color:red}", "Title", "Description", js);
    }

    private static ThemeStyleContext explicitTheme() {
        return new ThemeStyleContext("theme", "Theme", "SUPPORTED", List.of(), List.of(), "",
            new ThemeStyleContext.ColorMode("explicit", List.of(), List.of()));
    }
}
