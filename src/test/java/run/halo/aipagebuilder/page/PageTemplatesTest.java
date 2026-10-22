package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PageTemplatesTest {
    @Test
    void standalonePagesLoadTheNormalizeBaselineWithoutTheThemeLayout() throws IOException {
        assertThat(resource("templates/ai-page-builder-standalone.html"))
            .contains("/plugins/ai-page-builder/assets/modern-normalize-3.0.1.css");
        assertThat(resource("templates/ai-page-builder.html")).doesNotContain("modern-normalize");
    }

    @Test
    void assetsProxyExposesTheNormalizeBaseline() throws IOException {
        assertThat(resource("extensions/assets-proxy.yaml"))
            .containsPattern(Pattern.compile("- path: /modern-normalize-3\\.0\\.1\\.css\\s+"
                + "file:\\s+directory: static\\s+filename: modern-normalize-3\\.0\\.1\\.css"));
    }

    @Test
    void bundledNormalizeRemovesTheDocumentMargin() throws IOException {
        assertThat(resource("static/modern-normalize-3.0.1.css"))
            .containsPattern(Pattern.compile("body\\s*\\{[^}]*margin:\\s*0;", Pattern.DOTALL));
    }

    private static String resource(String path) throws IOException {
        try (InputStream input =
            PageTemplatesTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
