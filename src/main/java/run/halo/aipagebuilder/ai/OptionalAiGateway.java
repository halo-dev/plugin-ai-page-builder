package run.halo.aipagebuilder.ai;

import org.springframework.stereotype.Component;
import org.pf4j.DefaultVersionManager;
import run.halo.app.core.extension.Plugin;
import run.halo.app.extension.ReactiveExtensionClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

@Component
public class OptionalAiGateway implements AiGateway {
    private final ExtensionGetter extensions;
    private final ClassLoader classLoader;
    private final ReactiveExtensionClient client;
    private static final String REQUIRED_VERSION = ">=1.1.1 & <2.0.0";

    @org.springframework.beans.factory.annotation.Autowired
    public OptionalAiGateway(ExtensionGetter extensions, ReactiveExtensionClient client) {
        this.extensions = extensions;
        this.client = client;
        this.classLoader = OptionalAiGateway.class.getClassLoader();
    }

    private Mono<Boolean> compatible() {
        return Mono.defer(() -> client.fetch(Plugin.class, "ai-foundation"))
            .map(plugin -> plugin.getSpec() != null && plugin.getSpec().getVersion() != null
                && new DefaultVersionManager().checkVersionConstraint(
                    plugin.getSpec().getVersion(), REQUIRED_VERSION))
            .defaultIfEmpty(false).onErrorReturn(false);
    }

    private AiGateway bridge() {
        try {
            Class.forName("run.halo.aifoundation.AiModelService", false, classLoader);
            // No SDK type, bridge class literal or SDK-bearing Spring component at startup.
            var type = Class.forName("run.halo.aipagebuilder.ai.FoundationBridge", true, classLoader);
            return (AiGateway) type.getConstructor(ExtensionGetter.class).newInstance(extensions);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    @Override
    public Mono<Boolean> available() {
        return status().map(AiEndpoint.AiStatus::available);
    }

    @Override
    public Mono<AiEndpoint.AiStatus> status() {
        return compatible().flatMap(compatible -> {
            if (!compatible) {
                return Mono.just(new AiEndpoint.AiStatus(false,
                    "请安装或升级 AI Foundation 至 1.1.1 或以上的 1.x 版本。"));
            }
            var bridge = bridge();
            return bridge == null ? Mono.just(new AiEndpoint.AiStatus(false,
                "AI Foundation API 不可用，请重新启用 AI Page Builder。")) : bridge.status();
        }).onErrorReturn(new AiEndpoint.AiStatus(false, "AI Foundation 暂不可用。"));
    }

    @Override
    public Flux<java.util.Map<String, Object>> chat(String modelName,
        java.util.List<ChatApi.Turn> history, String prompt, PageWorkingCopy copy) {
        return compatible().flatMapMany(compatible -> {
            var gateway = compatible ? bridge() : null;
            return gateway == null ? Flux.error(new AiGenerationException(
                AiGenerationException.Reason.MODEL_UNAVAILABLE))
                : gateway.chat(modelName, history, prompt, copy);
        });
    }

}
