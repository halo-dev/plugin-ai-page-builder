package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import run.halo.aipagebuilder.model.PageDocument;

class PageExportTest {
    @Test
    void exportsExactSourceAndEscapedShellWithoutEditorOrChatState() throws Exception {
        var document = new PageDocument(2, "code", null, null,
            "<button id=\"next\">下一项</button>", "button { color: red; }",
            "<unsafe & title>", "", "document.querySelector('#next').textContent = '</script>'; ");
        var files = new LinkedHashMap<String, String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(PageExport.zip(document)), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(files).containsOnlyKeys("index.html", "styles.css", "script.js", "README.txt");
        assertThat(files.get("styles.css")).isEqualTo(document.css());
        assertThat(files.get("script.js")).isEqualTo(document.js());
        assertThat(files.get("index.html")).contains("&lt;unsafe &amp; title&gt;", document.html(), "href=\"styles.css\"", "src=\"script.js\"")
            .doesNotContain("<unsafe & title>", document.js());
    }
}
