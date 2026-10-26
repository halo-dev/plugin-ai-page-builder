package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.Theme;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.SystemSetting;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;
import run.halo.aipagebuilder.theme.ThemeContextService;
import run.halo.aipagebuilder.theme.ThemeStyleContext;

class ThemeReferenceRecoveryTest {
    @TempDir Path root;
    private Theme theme;
    private PageWorkingCopy copy;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve("templates"));
        Files.writeString(root.resolve("templates/page.html"), "<header>Site header</header>");
        var client = mock(ReactiveExtensionClient.class);
        var config = new ConfigMap();
        config.setData(Map.of("theme", "{\"active\":\"theme-a\"}"));
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG)).thenReturn(Mono.just(config));
        theme = new Theme();
        theme.setMetadata(new Metadata());
        theme.getMetadata().setName("theme-a");
        theme.setStatus(new Theme.ThemeStatus());
        theme.getStatus().setLocation(root.toString());
        when(client.fetch(Theme.class, "theme-a")).thenReturn(Mono.just(theme));
        var tools = new PageReferenceTools(client,
            new ThemeContextService(client, new ContentPolicy()), mock(SiteReferenceTools.class),
            mock(ContentReferenceTools.class), mock(WebReferenceTools.class),
            mock(ApiReferenceTools.class));
        copy = new PageWorkingCopy(
            new PageDocument(1, "code", null, null, "<p>Original</p>", ""),
            new ContentPolicy(), Mono.just(ThemeStyleContext.unavailable()));
        copy.references(tools.session("theme-a", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"read_theme_file", "search_theme_file", "list_theme_files"})
    void missingSourceAllowsDiscoveryReadingAndEditingInTheSameWorkingCopy(String tool) {
        var path = "list_theme_files".equals(tool) ? "templates/modules" : "templates/header.html";
        Map<String, Object> input = switch (tool) {
            case "list_theme_files" -> Map.of("directory", path);
            case "search_theme_file" -> Map.of("themeName", "theme-a", "path", path,
                "query", "header");
            default -> Map.of("themeName", "theme-a", "path", path);
        };

        var result = copy.reference(tool, input).block();

        assertThat(result).containsKey("error");
        var error = (String) result.get("error");
        assertThat(error).contains(path, "主题源码路径不存在", "查找主题文件")
            .doesNotContain(root.toString());
        assertThat(copy.summaries()).containsExactly(new ChatApi.Tool(tool, "error", error));
        assertThat(copy.document().html()).isEqualTo("<p>Original</p>");
        assertThat(copy.reference("list_theme_files", Map.of()).block())
            .containsEntry("items", List.of("templates/page.html"));
        assertThat(copy.reference("read_theme_file", Map.of("themeName", "theme-a",
            "path", "templates/page.html")).block())
            .containsEntry("content", "<header>Site header</header>");
        assertThat(copy.update(Map.of("html", "<p>Edited</p>")).block())
            .containsEntry("valid", true).containsEntry("staged", true);
        assertThat(copy.document().html()).isEqualTo("<p>Edited</p>");
        assertThat(copy.hasInvalidUpdate()).isFalse();
        assertThat(copy.exhausted()).isFalse();
    }

    @Test
    void missingThemeInstallationStillPropagatesAsStorageFailure() {
        theme.getStatus().setLocation(root.resolve("missing-theme").toString());

        StepVerifier.create(copy.reference("read_theme_file", Map.of("themeName", "theme-a",
                "path", "templates/page.html")))
            .expectError(NoSuchFileException.class)
            .verify();
        assertThat(copy.summaries()).isEmpty();
    }
}
