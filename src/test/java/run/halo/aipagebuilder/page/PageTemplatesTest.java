package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PageTemplatesTest {
    @Test
    void standalonePagesLoadTheNormalizeBaselineWithoutTheThemeLayout() throws IOException {
        assertThat(resource("templates/ai-page-builder-standalone.html"))
            .contains("/plugins/ai-page-builder/assets/modern-normalize.css");
        assertThat(resource("templates/ai-page-builder.html")).doesNotContain("modern-normalize");
    }

    @Test
    void assetsProxyExposesTheNormalizeBaseline() throws IOException {
        assertThat(resource("extensions/assets-proxy.yaml"))
            .contains("path: /modern-normalize.css", "filename: modern-normalize.css");
    }

    @Test
    void bundledNormalizeRemovesTheDocumentMargin() throws IOException {
        assertThat(resource("static/modern-normalize.css")).contains("body {", "margin: 0");
    }

    private static String resource(String path) throws IOException {
        try (InputStream input =
            PageTemplatesTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
