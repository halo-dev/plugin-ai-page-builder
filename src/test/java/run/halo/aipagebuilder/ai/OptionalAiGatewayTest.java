package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.AiModelService;
import run.halo.app.core.extension.Plugin;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

class OptionalAiGatewayTest {
    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final ExtensionGetter extensions = mock(ExtensionGetter.class);
    private final OptionalAiGateway gateway = new OptionalAiGateway(extensions, client);

    @ParameterizedTest
    @ValueSource(strings = {"1.0.0", "1.1.0", "2.0.0", "2.1.0", "invalid", "", "1.1.1-beta.1"})
    void incompatibleVersionBlocksStatusAndGenerationBeforeLoadingProvider(String version) {
        when(client.fetch(Plugin.class, "ai-foundation")).thenReturn(Mono.just(plugin(version)));
        assertThat(gateway.status().block().available()).isFalse();
        assertThat(gateway.status().block().message()).contains("升级", "1.1.1");
        assertThatThrownBy(() -> gateway.chat(null, java.util.List.of(), "prompt", null).blockLast())
            .isInstanceOfSatisfying(AiGenerationException.class, error ->
                assertThat(error.reason()).isEqualTo(AiGenerationException.Reason.MODEL_UNAVAILABLE));
        verifyNoInteractions(extensions);
    }

    @Test
    void checksResourceVersionAgainAfterUpgradeOrDowngrade() {
        when(client.fetch(Plugin.class, "ai-foundation")).thenReturn(
            Mono.just(plugin("1.0.0")), Mono.just(plugin("1.1.1")), Mono.just(plugin("2.0.0")));
        when(extensions.getEnabledExtension(AiModelService.class))
            .thenReturn(Mono.just(mock(AiModelService.class)));
        assertThat(gateway.available().block()).isFalse();
        assertThat(gateway.available().block()).isTrue();
        assertThat(gateway.available().block()).isFalse();
    }

    @Test
    void missingPluginOrVersionFailsClosed() {
        when(client.fetch(Plugin.class, "ai-foundation"))
            .thenReturn(Mono.empty(), Mono.just(plugin(null)));
        assertThat(gateway.available().block()).isFalse();
        assertThat(gateway.available().block()).isFalse();
        verifyNoInteractions(extensions);
    }

    private Plugin plugin(String version) {
        var plugin = new Plugin();
        plugin.setSpec(new Plugin.PluginSpec());
        plugin.getSpec().setVersion(version);
        return plugin;
    }
}
