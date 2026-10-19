package run.halo.aipagebuilder.ai;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;
import run.halo.aipagebuilder.model.PageChatCheckpoint;
import run.halo.aipagebuilder.page.PageApi.PageDetail;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.aipagebuilder.Json;

/** One bounded durable snapshot per session; writes run under the page-wide reservation. */
final class PageCheckpoints {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final ReactiveExtensionClient client;

    PageCheckpoints(ReactiveExtensionClient client) { this.client = client; }

    Mono<PageChatCheckpoint> load(String sessionId) {
        return client.fetch(PageChatCheckpoint.class, name(sessionId))
            .filter(value -> value.getMetadata().getDeletionTimestamp() == null);
    }

    Mono<Void> save(PageChatCheckpoint.Spec snapshot) {
        return Mono.defer(() -> {
            snapshot.setUpdatedAt(Instant.now());
            if (Json.toJson(snapshot).getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                return Mono.error(new AiGenerationException(AiGenerationException.Reason.CHECKPOINT_FAILED));
            }
            // Halo delete marks a tombstone first. Wait for GC before reusing the stable name;
            // otherwise a quick new task could update a snapshot that GC is about to remove.
            return client.fetch(PageChatCheckpoint.class, name(snapshot.getSessionId()))
                .expand(value -> value.getMetadata().getDeletionTimestamp() == null ? Mono.empty()
                    : Mono.delay(java.time.Duration.ofMillis(100)).then(
                        client.fetch(PageChatCheckpoint.class, name(snapshot.getSessionId()))))
                .filter(value -> value.getMetadata().getDeletionTimestamp() == null).next()
                .flatMap(existing -> {
                existing.setSpec(snapshot);
                return client.update(existing);
            }).switchIfEmpty(Mono.defer(() -> {
                var checkpoint = new PageChatCheckpoint();
                var metadata = new Metadata();
                metadata.setName(name(snapshot.getSessionId()));
                checkpoint.setMetadata(metadata);
                checkpoint.setSpec(snapshot);
                return client.create(checkpoint);
            })).then();
        }).timeout(java.time.Duration.ofSeconds(15))
            .onErrorMap(error -> new AiGenerationException(AiGenerationException.Reason.CHECKPOINT_FAILED));
    }

    Mono<Void> available(String sessionId, String runId) {
        return load(sessionId).filter(value -> runId.equals(value.getSpec().getRequest().runId()))
            .flatMap(value -> {
                value.getSpec().setAvailable(true);
                return client.update(value);
            }).then();
    }

    Mono<Void> delete(String sessionId) {
        return load(sessionId).flatMap(client::delete).then();
    }

    static ChatApi.Recovery summary(PageChatCheckpoint checkpoint, PageDetail page,
        boolean busy) {
        var spec = checkpoint.getSpec();
        String blocked = null;
        if (spec.getFormatVersion() != 1) blocked = "进度格式不受支持，请重新生成。";
        else if (!Objects.equals(spec.getRequest().version(), page.page().version())
            || !Objects.equals(spec.getBaseRevision(), page.page().draftRevision())) {
            blocked = "页面已变化，不能继续旧进度，请重新生成。";
        } else if (busy || !spec.isAvailable()) blocked = "运行结果尚未确定，请先检查运行状态。";
        return new ChatApi.Recovery(spec.getRequest().runId(), checkpoint.getMetadata().getVersion(),
            blocked == null, blocked, spec.getUpdatedAt());
    }

    private static String name(String sessionId) {
        return "checkpoint-" + UUID.nameUUIDFromBytes(sessionId.getBytes(StandardCharsets.UTF_8));
    }
}
