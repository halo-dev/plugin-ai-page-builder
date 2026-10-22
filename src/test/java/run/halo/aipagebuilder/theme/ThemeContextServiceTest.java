package run.halo.aipagebuilder.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.SystemSetting;
import run.halo.aipagebuilder.content.ContentPolicy;

class ThemeContextServiceTest {
    @TempDir Path root;

    @Test
    void readsOnlyBoundedTemplateFilesInsideRealRoot() throws Exception {
        var templates = Files.createDirectories(root.resolve("theme/templates"));
        var theme = templates.getParent().toRealPath();
        Files.writeString(templates.resolve("layout.html"), "<main>safe</main>");
        Files.writeString(root.resolve("outside.html"), "private");
        Files.createSymbolicLink(templates.resolve("linked.html"), root.resolve("outside.html"));
        var rules = "a{b:c}".repeat((ThemeContextService.MAX_SOURCE_BYTES - 80) / 6);
        Files.writeString(templates.resolve("large.css"),
            rules + "a{b:c}".repeat(10) + "/*" + "x".repeat(100));
        Files.writeString(templates.resolve("string.css"),
            rules + "a{b:c}".repeat(10) + "a{content:'}" + "x".repeat(100));
        Files.writeString(templates.resolve("media.css"),
            rules + "a{b:c}".repeat(10) + "@media x{a{b:c}" + "x".repeat(100));
        var escaped = ".brace\\{name{color:red}";
        var escapeRules = "a{b:c}".repeat(
            (ThemeContextService.MAX_SOURCE_BYTES - escaped.length()) / 6);
        Files.writeString(templates.resolve("escape.css"), escaped + escapeRules + "a{b:c}");
        assertThat(ThemeContextService.read(theme, "templates/layout.html").text()).isEqualTo("<main>safe</main>");
        // A hard byte cut inside a comment, string or nested block would make the parser reject
        // the prefix; truncation is reported separately from the usable prefix length.
        assertThat(ThemeContextService.read(theme, "templates/large.css").text()).startsWith(rules)
            .doesNotContain("/*").hasSizeLessThanOrEqualTo(ThemeContextService.MAX_SOURCE_BYTES);
        assertThat(ThemeContextService.read(theme, "templates/string.css").text()).startsWith(rules)
            .doesNotContain("content").hasSizeLessThanOrEqualTo(ThemeContextService.MAX_SOURCE_BYTES);
        assertThat(ThemeContextService.read(theme, "templates/media.css").text()).startsWith(rules)
            .doesNotContain("@media").hasSizeLessThanOrEqualTo(ThemeContextService.MAX_SOURCE_BYTES);
        for (var name : new String[] {"large.css", "string.css", "media.css", "escape.css"}) {
            assertThat(ThemeContextService.read(theme, "templates/" + name).byteTruncated()).isTrue();
        }
        assertThat(ThemeContextService.read(theme, "templates/layout.html").byteTruncated()).isFalse();
        // An escaped brace in a selector must not be counted as a block opener.
        assertThat(ThemeContextService.read(theme, "templates/escape.css").text())
            .startsWith(escaped + escapeRules).hasSizeLessThanOrEqualTo(ThemeContextService.MAX_SOURCE_BYTES);
        for (var name : new String[] {"../outside.html", "templates/../../outside.html", "templates/linked.html",
            "/outside.html", "templates/theme.yaml", "templates/missing.css"}) {
            assertThat(ThemeContextService.read(theme, name)).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "中"})
    void reportsByteTruncationForHtmlAndCssWithoutGuessingFromCharacterCount(String filler)
        throws Exception {
        Files.createDirectories(root.resolve("templates/assets"));
        var themeRoot = root.toRealPath();
        var extractor = new ThemeContextExtractor(new ContentPolicy());
        for (var extension : new String[] {"html", "css"}) {
            var path = extension.equals("html") ? "templates/layout.html" : "templates/assets/large.css";
            if (extension.equals("css")) {
                Files.writeString(root.resolve("templates/layout.html"),
                    "<link href='/assets/large.css'><main th:insert='${content}'></main>");
            }
            var prefix = extension.equals("html")
                ? "<html th:fragment='html(head, content)'><body><main th:insert='${content}'></main><!--"
                : "body{color:red}/*";
            var bytes = filler.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            var ending = extension.equals("html") ? "--></body></html>" : "*/";
            var remaining = ThemeContextService.MAX_SOURCE_BYTES - prefix.length() - ending.length();
            var exact = prefix + filler.repeat(remaining / bytes) + "x".repeat(remaining % bytes)
                + ending;
            for (var suffix : new String[] {"", "tail"}) {
                Files.writeString(root.resolve(path), exact + suffix);
                var source = ThemeContextService.read(themeRoot, path);
                assertThat(source.byteTruncated()).isEqualTo(!suffix.isEmpty());
                var result = extractor.extractSources(file -> ThemeContextService.read(themeRoot, file),
                    null, null);
                assertThat(result.coverage().gaps().stream()
                    .anyMatch(gap -> gap.reason().equals("file_truncated")))
                    .as("%s / %s / suffix=%s", extension, filler, suffix)
                    .isEqualTo(!suffix.isEmpty());
                if (extension.equals("css")) assertThat(result.previewCss()).contains("color:red");
            }
        }
    }

    @Test
    void resolvesDefaultOnlyForMissingActiveAndUsesSupportedLayout() throws Exception {
        var templates = Files.createDirectories(root.resolve("templates"));
        Files.writeString(templates.resolve("layout.html"),
            "<html><head><style>:root{--accent:#123456}html.dark{--accent:#eee}"
                + "body{color:var(--accent)}</style></head><body></body></html>");
        Files.writeString(templates.resolve("page.html"),
            "<main class='shell'><div class='content-wrap'><article class='prose' "
                + "th:utext='${singlePage.content.content}'></article></div></main>");
        var client = mock(ReactiveExtensionClient.class);
        var service = new ThemeContextService(client, new ContentPolicy());
        var config = new ConfigMap();
        config.setData(Map.of("theme", "{}"));
        var defaults = new ConfigMap();
        defaults.setData(Map.of("theme", "{\"active\":\"sample\"}"));
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG)).thenReturn(Mono.just(config));
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG_DEFAULT)).thenReturn(Mono.just(defaults));
        var theme = new run.halo.app.core.extension.Theme();
        theme.setMetadata(new run.halo.app.extension.Metadata());
        theme.getMetadata().setName("sample");
        var status = new run.halo.app.core.extension.Theme.ThemeStatus();
        var layout = new run.halo.app.core.extension.Theme.ThemeStatus.PageLayout();
        layout.setState(run.halo.app.core.extension.Theme.PageLayoutState.SUPPORTED);
        status.setPageLayout(layout);
        status.setLocation(root.toString());
        theme.setStatus(status);
        when(client.fetch(run.halo.app.core.extension.Theme.class, "sample")).thenReturn(Mono.just(theme));
        var context = service.current().block();
        assertThat(context.themeName()).isEqualTo("sample");
        assertThat(context.previewCss()).contains("--accent", "#123456");
        assertThat(context.colorMode().strategy()).isEqualTo("explicit");
        assertThat(context.colorMode().rootStates()).extracting(ThemeStyleContext.RootState::selector)
            .contains("html.dark");
        assertThat(context.contentSlot().template()).isEqualTo("templates/page.html");
        assertThat(context.contentSlot().wrapper()).extracting(
                ThemeStyleContext.ContentSlot.Wrapper::selector)
            .containsExactly("main.shell", "div.content-wrap");
        assertThat(context.contentSlot().contentClasses()).containsExactly("prose");
        assertThat(context.notes()).anyMatch(note -> note.contains("仅用于文章页参考"))
            .noneMatch(note -> note.contains("补齐"));
        assertThat(context.brief()).contains("main.shell", "div.content-wrap", ".prose");
        for (var state : java.util.List.of(run.halo.app.core.extension.Theme.PageLayoutState.MISSING,
            run.halo.app.core.extension.Theme.PageLayoutState.INVALID)) {
            layout.setState(state);
            assertThat(service.current().block().layoutState()).isEqualTo(state.name());
            assertThat(service.current().block().previewCss()).isEmpty();
        }
        for (var json : java.util.List.of("{\"active\":null}", "{\"active\":\"\"}", "null")) {
            config.setData(Map.of("theme", json));
            assertThat(service.current().block().layoutState()).isEqualTo("FALLBACK");
        }
        config.setData(Map.of("theme", "{\"active\":\"missing\"}"));
        when(client.fetch(run.halo.app.core.extension.Theme.class, "missing")).thenReturn(Mono.empty());
        assertThat(service.current().block().layoutState()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void missingThemeUsesFallbackAndBrokenConfigurationDoesNotBreakEditing() {
        var client = mock(ReactiveExtensionClient.class);
        var service = new ThemeContextService(client, new ContentPolicy());
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG)).thenReturn(Mono.empty());
        assertThat(service.current().block().layoutState()).isEqualTo("FALLBACK");
        var config = new ConfigMap();
        config.setData(Map.of("theme", "not-json"));
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG)).thenReturn(Mono.just(config));
        assertThat(service.current().block().layoutState()).isEqualTo("UNAVAILABLE");
    }
}
