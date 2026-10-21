package run.halo.aipagebuilder.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Theme;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.SystemSetting;
import run.halo.aipagebuilder.content.ContentPolicy;

class ThemeReferenceFilesTest {
    @TempDir Path root;
    private ThemeContextService service;
    private ConfigMap config;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve("templates/assets"));
        var client = mock(ReactiveExtensionClient.class);
        config = new ConfigMap();
        config.setData(Map.of("theme", "{\"active\":\"theme-a\"}"));
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG)).thenReturn(Mono.just(config));
        var theme = new Theme();
        theme.setMetadata(new Metadata());
        theme.getMetadata().setName("theme-a");
        theme.setStatus(new Theme.ThemeStatus());
        theme.getStatus().setLocation(root.toString());
        when(client.fetch(Theme.class, "theme-a")).thenReturn(Mono.just(theme));
        service = new ThemeContextService(client, new ContentPolicy());
    }

    @Test
    void listsOnlySourceFilesWithStablePaginationAndReadsWithoutExecuting() throws Exception {
        Files.writeString(root.resolve("templates/a.html"), "<script>throw 'not executed'</script>");
        Files.writeString(root.resolve("templates/assets/b.css"), "html.dark{color:white}");
        Files.writeString(root.resolve("templates/c.js"), "window.alert('source');");
        Files.writeString(root.resolve("templates/config.yaml"), "secret: value");
        Files.writeString(root.resolve("templates/.secret.js"), "private");
        var first = service.listFiles("theme-a", null, 0, 2).block();
        assertThat(first).containsEntry("themeName", "theme-a").containsEntry("truncated", true)
            .containsEntry("summary", "templates · 共 3 个文件");
        assertThat(first.get("items")).isEqualTo(java.util.List.of("templates/a.html", "templates/assets/b.css"));
        var last = service.listFiles("theme-a", "templates", 2, 2).block();
        assertThat(last.get("items")).isEqualTo(java.util.List.of("templates/c.js"));
        assertThat(last).containsEntry("truncated", false);
        var read = service.readFile("theme-a", "templates/c.js", 7, 5).block();
        assertThat(read).containsEntry("content", "alert").containsEntry("nextOffset", 12)
            .containsEntry("path", "templates/c.js").containsEntry("truncated", true)
            .containsEntry("summary", "templates/c.js · 已读取 5 / 23 字符");
    }

    @Test
    void rejectsTraversalAbsolutePathsConfigFilesAndSymbolicLinks() throws Exception {
        Files.writeString(root.resolve("outside.html"), "private");
        Files.writeString(root.resolve("templates/config.yaml"), "secret: value");
        Files.createSymbolicLink(root.resolve("templates/escape.html"), root.resolve("outside.html"));
        Files.createSymbolicLink(root.resolve("templates/alias.js"), root.resolve("templates/config.yaml"));
        Files.createSymbolicLink(root.resolve("templates/linked"), root);
        for (var path : java.util.List.of("../outside.html", "templates/../outside.html",
            "/tmp/file.html", "templates\\outside.html", "templates/config.yaml",
            "templates/escape.html", "templates/alias.js", "templates/linked/outside.html")) {
            assertThatThrownBy(() -> service.readFile("theme-a", path, 0, 100).block())
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(service.listFiles("theme-a", "templates", 0, 100).block().get("items"))
            .isEqualTo(java.util.List.of());
        assertThatThrownBy(() -> service.listFiles("theme-a", "templates/linked", 0, 100).block())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTemplatesRootSymlink() throws Exception {
        Files.createDirectories(root.resolve("another/templates"));
        Files.writeString(root.resolve("another/templates/test.html"), "private");
        Files.createSymbolicLink(root.resolve("templates/another"), root.resolve("another/templates"));
        assertThatThrownBy(() -> service.readFile("theme-a", "templates/another/test.html", 0, 100).block())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesReadByteAndOutputBudgetsAndRejectsInvalidPaginationAndThemeSwitch() throws Exception {
        Files.writeString(root.resolve("templates/large.js"),
            "x".repeat(ThemeContextService.MAX_SOURCE_BYTES + 1024));
        var result = service.readFile("theme-a", "templates/large.js", 0, 16 * 1024).block();
        assertThat((String) result.get("content")).hasSize(16 * 1024);
        assertThat(result).containsEntry("truncated", true).containsEntry("byteTruncated", true);
        assertThatThrownBy(() -> service.readFile("theme-a", "templates/large.js", -1, 10).block())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.readFile("theme-a", "templates/large.js", 0, 16385).block())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.listFiles("theme-a", "templates", 0, 0).block())
            .isInstanceOf(IllegalArgumentException.class);
        config.setData(Map.of("theme", "{\"active\":\"theme-b\"}"));
        assertThatThrownBy(() -> service.readFile("theme-a", "templates/large.js", 0, 10).block())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("changed");
    }
}
