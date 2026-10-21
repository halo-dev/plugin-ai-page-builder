package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import run.halo.aipagebuilder.Json;
import run.halo.aipagebuilder.model.PageDocument;

class PageExportTest {
    static PageApi.PageDetail detail(PageDocument document, boolean themeLayout) {
        return new PageApi.PageDetail(new PageApi.PageSummary("original-id", "original-path",
            "<unsafe & title>", "Description", 8L, themeLayout, false, null, "draft", "published",
            java.time.Instant.now(), true), document);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void sourceAndMetadataSurviveRoundTrip(boolean themeLayout) {
        for (var script : java.util.List.of("", "console.log('中文 </script>');\n")) {
            var document = new PageDocument(2, "code", null, null,
                "\n<section><p>中文 &amp; 空白</p></section>\n", "p { color: red; }\n", script);
            var imported = PageArchive.read(PageExport.zip(detail(document, themeLayout)));
            assertThat(imported.document()).isEqualTo(document);
            assertThat(imported.themeLayout()).isEqualTo(themeLayout);
        }
    }

    @Test
    void exportsExactSourceAndEscapedShellWithoutEditorOrChatState() throws Exception {
        var document = new PageDocument(2, "code", null, null,
            "<button id=\"next\">下一项</button>", "button { color: red; }", "document.querySelector('#next').textContent = '</script>'; ");
        var files = new LinkedHashMap<String, String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(PageExport.zip(detail(document, false))), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(files).containsOnlyKeys("page.json", "page.html", "index.html", "styles.css",
            "script.js", "README.txt");
        assertThat(files.get("page.html")).isEqualTo(document.html());
        var metadata = Json.fromJson(files.get("page.json"),
            tools.jackson.databind.JsonNode.class);
        assertThat(metadata.size()).isEqualTo(5);
        assertThat(metadata.get("format").asString()).isEqualTo("ai-page-builder");
        assertThat(metadata.get("version").asInt()).isEqualTo(1);
        assertThat(metadata.get("title").asString()).isEqualTo(detail(document, false).page().title());
        assertThat(metadata.get("themeLayout").asBoolean()).isFalse();
        assertThat(files.get("styles.css")).isEqualTo(document.css());
        assertThat(files.get("script.js")).isEqualTo(document.js());
        assertThat(files.get("index.html")).contains("&lt;unsafe &amp; title&gt;", document.html(), "href=\"styles.css\"", "src=\"script.js\"")
            .doesNotContain("<unsafe & title>", document.js());
    }
}
