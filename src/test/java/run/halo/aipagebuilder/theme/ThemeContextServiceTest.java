package run.halo.aipagebuilder.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
        Files.writeString(templates.resolve("large.css"), "x".repeat(256 * 1024 + 1));
        assertThat(ThemeContextService.read(theme, "templates/layout.html")).isEqualTo("<main>safe</main>");
        for (var name : new String[] {"../outside.html", "templates/../../outside.html", "templates/linked.html",
            "templates/large.css", "/outside.html", "templates/theme.yaml", "templates/missing.css"}) {
            assertThat(ThemeContextService.read(theme, name)).isNull();
        }
    }

    @Test
    void resolvesDefaultOnlyForMissingActiveAndUsesSupportedLayout() throws Exception {
        var templates = Files.createDirectories(root.resolve("templates"));
        Files.writeString(templates.resolve("layout.html"),
            "<html><head><style>:root{--accent:#123456}html.dark{--accent:#eee}"
                + "body{color:var(--accent)}</style></head><body></body></html>");
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
