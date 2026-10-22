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

    @Test
    @SuppressWarnings("unchecked")
    void searchesMinifiedSourcesWithLiteralQueriesAndUtf16Offsets() throws Exception {
        var source = "/*中文😀*/" + ".btn{color:red}".repeat(80) + ".BTN{color:blue}";
        Files.writeString(root.resolve("templates/assets/main.css"), source);
        var first = service.searchFile("theme-a", "templates/assets/main.css", ".btn", 0, 2).block();
        var matches = (java.util.List<Map<String, Object>>) first.get("matches");
        assertThat(matches).hasSize(2);
        assertThat(matches.getFirst().get("offset")).isEqualTo(source.indexOf(".btn"));
        assertThat(first).containsEntry("hasMore", true).containsEntry("byteTruncated", false);
        var next = service.searchFile("theme-a", "templates/assets/main.css", ".btn",
            (int) first.get("nextOffset"), 20).block();
        var nextMatches = (java.util.List<Map<String, Object>>) next.get("matches");
        assertThat(nextMatches.getFirst().get("offset"))
            .isEqualTo(source.indexOf(".btn", (int) first.get("nextOffset")));
        for (var match : nextMatches) {
            var text = (String) match.get("text");
            assertThat(text.length()).isLessThanOrEqualTo(512);
            assertThat(text).isEqualTo(source.substring((int) match.get("start"), (int) match.get("end")));
            var read = service.readFile("theme-a", "templates/assets/main.css",
                (int) match.get("start"), (int) match.get("end") - (int) match.get("start")).block();
            assertThat(read.get("content")).isEqualTo(text);
        }
        var absent = service.searchFile("theme-a", "templates/assets/main.css", ".btn.*", 0, 10).block();
        assertThat(absent).containsEntry("matches", java.util.List.of()).containsEntry("hasMore", false)
            .containsEntry("byteTruncated", false);
        var upper = service.searchFile("theme-a", "templates/assets/main.css", ".BTN", 0, 10).block();
        assertThat((java.util.List<?>) upper.get("matches")).hasSize(1);
    }

    @Test
    void searchReportsUnsearchedTailAndEnforcesTheSameFileBoundary() throws Exception {
        Files.writeString(root.resolve("templates/large.js"),
            "x".repeat(ThemeContextService.MAX_SOURCE_BYTES) + "needle");
        var result = service.searchFile("theme-a", "templates/large.js", "needle", 0, 10).block();
        assertThat(result).containsEntry("matches", java.util.List.of()).containsEntry("hasMore", false)
            .containsEntry("byteTruncated", true)
            .containsEntry("scannedCharacters", ThemeContextService.MAX_SOURCE_BYTES);
        Files.writeString(root.resolve("outside.js"), "needle");
        Files.writeString(root.resolve("templates/config.yaml"), "needle");
        Files.createSymbolicLink(root.resolve("templates/linked.js"), root.resolve("outside.js"));
        for (var path : java.util.List.of("../outside.js", "/outside.js", "templates/../outside.js",
            "templates/linked.js", "templates/config.yaml", "templates/.secret.js")) {
            assertThatThrownBy(() -> service.searchFile("theme-a", path, "needle", 0, 10).block())
                .isInstanceOf(IllegalArgumentException.class);
        }
        for (var query : java.util.List.of("", " ", "x".repeat(201))) {
            assertThatThrownBy(() -> service.searchFile("theme-a", "templates/large.js", query, 0, 10).block())
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.searchFile("theme-a", "templates/large.js", "x", -1, 10).block())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.searchFile("theme-a", "templates/large.js", "x", 0, 21).block())
            .isInstanceOf(IllegalArgumentException.class);
        config.setData(Map.of("theme", "{\"active\":\"theme-b\"}"));
        assertThatThrownBy(() -> service.searchFile("theme-a", "templates/large.js", "x", 0, 10).block())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("changed");
    }
}
