package run.halo.aipagebuilder.ai;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** SDK-free boundary. The stream completes only after a successful model finish. */
public interface AiGateway {
    Mono<Boolean> available();

    default Mono<AiEndpoint.AiStatus> status() {
        return available().map(available -> new AiEndpoint.AiStatus(available,
            available ? "AI Foundation 已启用。" : "请安装并启用 AI Foundation，配置可用语言模型。"));
    }

    /** Emits SDK UIMessage transport maps; completion means a verified normal STOP. */
    default Flux<java.util.Map<String, Object>> chat(String modelName,
        java.util.List<ChatApi.Turn> history, String prompt, PageWorkingCopy copy) {
        return Flux.error(new AiGenerationException(AiGenerationException.Reason.MODEL_UNAVAILABLE));
    }
}
