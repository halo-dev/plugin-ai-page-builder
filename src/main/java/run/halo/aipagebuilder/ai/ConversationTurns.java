package run.halo.aipagebuilder.ai;

import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.isNull;
import static run.halo.app.extension.index.query.Queries.lessThan;
import static run.halo.app.extension.index.query.Queries.or;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.aipagebuilder.model.PageChatCheckpoint;
import run.halo.aipagebuilder.model.PageChatSession;
import run.halo.aipagebuilder.model.PageChatTurnRecord;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;

/** Durable Conversation Turn history and resumable cleanup, independent of model execution. */
public final class ConversationTurns {
    private final ReactiveExtensionClient client;
    private final PageCheckpoints checkpoints;

    ConversationTurns(ReactiveExtensionClient client, PageCheckpoints checkpoints) {
        this.client = client;
        this.checkpoints = checkpoints;
    }

    record History(List<ChatApi.Turn> turns, boolean hasMore, String nextBefore) { }
    record ArchivedTurn(String sessionId, ChatApi.Turn turn) { }

    /** The caller holds the live-page lock while initializing or resuming deletion. */
    Mono<PageChatSession> initialize(String name) {
        return client.fetch(PageChatSession.class, name)
            .filter(session -> session.getMetadata().getDeletionTimestamp() == null)
            .switchIfEmpty(Mono.defer(() -> create(name, name, "默认会话")
                .onErrorResume(error -> error instanceof OptimisticLockingFailureException
                    || error instanceof DataIntegrityViolationException,
                    error -> client.fetch(PageChatSession.class, name))))
            .flatMap(session -> {
                if (session.getSpec().getPageName() != null
                    && !name.equals(session.getSpec().getPageName())) {
                    return Mono.error(conflict("默认会话名称冲突"));
                }
                return migrate(name, session).flatMap(this::completeDeletion);
            });
    }

    Mono<PageChatSession> create(String name, String id, String title) {
        return client.create(newSession(name, id, title));
    }

    Flux<PageChatSession> sessions(String name) {
        return client.listAll(PageChatSession.class, sessionOptions(name),
            Sort.by(Sort.Order.desc("spec.updatedAt"), Sort.Order.asc("metadata.name")));
    }

    /** Reserve deletion before touching children; the caller holds the live-page lock. */
    Mono<Void> deleteSession(PageChatSession coordinator, String sessionId, Long version) {
        var name = coordinator.getMetadata().getName();
        return selected(name, sessionId, coordinator).flatMap(session -> {
            if (version == null) return Mono.error(new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "version 必填"));
            if (!version.equals(session.getMetadata().getVersion())) {
                return Mono.error(conflict("会话已变化，请重新载入"));
            }
            if (coordinator.getSpec().getActive() != null) {
                return Mono.error(conflict("请先结束当前会话运行"));
            }
            if (coordinator.getSpec().getDeletingSessionId() != null
                && !sessionId.equals(coordinator.getSpec().getDeletingSessionId())) {
                return Mono.error(conflict("其他会话正在删除，请稍后重试"));
            }
            coordinator.getSpec().setDeletingSessionId(sessionId);
            return client.update(coordinator).flatMap(this::completeDeletion).then();
        });
    }

    /**
     * Archive before releasing the durable reservation. Reuse the observed resource version:
     * a concurrent reservation change must fail CAS, never be overwritten by a fresh fetch.
     */
    Mono<PageChatSession> finish(PageChatSession coordinator, ChatApi.Turn turn) {
        var name = coordinator.getMetadata().getName();
        var active = coordinator.getSpec().getActive();
        if (active == null || !turn.runId().equals(active.runId())) {
            return Mono.error(conflict("会话运行已变化"));
        }
        var sessionId = activeSessionId(coordinator);
        return archive(name, sessionId, turn).then(touchSession(name, sessionId))
            .then(Mono.defer(() -> {
                coordinator.getSpec().setActive(null);
                coordinator.getSpec().setActiveSessionId(null);
                coordinator.getSpec().setUpdatedAt(Instant.now());
                return client.update(coordinator);
            }));
    }

    Mono<History> history(String sessionId, String before, int size) {
        var options = turnOptions(sessionId);
        if (before != null) {
            options.setFieldSelector(options.getFieldSelector()
                .andQuery(lessThan("spec.sortKey", before)));
        }
        return client.listBy(PageChatTurnRecord.class, options,
            PageRequestImpl.of(1, size + 1, Sort.by(Sort.Order.desc("spec.sortKey"))))
            .map(result -> {
                var records = result.getItems();
                boolean hasMore = records.size() > size;
                var visible = records.subList(0, Math.min(size, records.size()));
                return new History(visible.stream().map(record -> record.getSpec().getTurn())
                    .toList().reversed(), hasMore,
                    hasMore ? visible.getLast().getSpec().getSortKey() : null);
            });
    }

    private Mono<PageChatSession> completeDeletion(PageChatSession coordinator) {
        var deletingId = coordinator.getSpec().getDeletingSessionId();
        if (deletingId == null) return Mono.just(coordinator);
        var name = coordinator.getMetadata().getName();
        return client.listAll(PageChatTurnRecord.class, turnOptions(deletingId), Sort.unsorted())
            .concatMap(client::delete).then(checkpoints.delete(deletingId))
            .then(Mono.defer(() -> deletingId.equals(name) ? Mono.empty()
                : client.fetch(PageChatSession.class, deletingId)
                    .filter(session -> session.getMetadata().getDeletionTimestamp() == null
                        && name.equals(session.getSpec().getPageName()))
                    .flatMap(client::delete).then()))
            .then(client.fetch(PageChatSession.class, name)).flatMap(latest -> {
                if (!deletingId.equals(latest.getSpec().getDeletingSessionId())) return Mono.just(latest);
                latest.getSpec().setDeletingSessionId(null);
                if (deletingId.equals(name)) {
                    latest.getSpec().setTitle("默认会话");
                    latest.getSpec().setUpdatedAt(Instant.now());
                }
                return client.update(latest);
            });
    }

    Mono<PageChatSession> selected(String name, String sessionId, PageChatSession coordinator) {
        if (sessionId == null || sessionId.equals(name)) return Mono.just(coordinator);
        return client.fetch(PageChatSession.class, sessionId)
            .filter(session -> session.getMetadata().getDeletionTimestamp() == null
                && name.equals(session.getSpec().getPageName()))
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在")))
            .flatMap(session -> migrate(name, session));
    }

    private Mono<PageChatSession> migrate(String name, PageChatSession session) {
        var spec = session.getSpec();
        var id = session.getMetadata().getName();
        boolean initialize = spec.getPageName() == null;
        if (!initialize && spec.getTurns().isEmpty()) return Mono.just(session);
        return Flux.fromIterable(List.copyOf(spec.getTurns()))
            .concatMap(turn -> archive(name, id, turn)).then(Mono.defer(() -> {
                if (initialize) {
                    spec.setPageName(name);
                    spec.setTitle("默认会话");
                    spec.setCreatedAt(session.getMetadata().getCreationTimestamp() == null
                        ? Instant.now() : session.getMetadata().getCreationTimestamp());
                    spec.setUpdatedAt(spec.getCreatedAt());
                }
                spec.getTurns().clear();
                return client.update(session);
            }));
    }

    private static PageChatSession newSession(String name, String id, String title) {
        var session = new PageChatSession();
        var metadata = new Metadata();
        metadata.setName(id);
        session.setMetadata(metadata);
        var spec = new PageChatSession.Spec();
        spec.setPageName(name);
        spec.setTitle(title);
        spec.setCreatedAt(Instant.now());
        spec.setUpdatedAt(spec.getCreatedAt());
        session.setSpec(spec);
        return session;
    }

    Mono<ArchivedTurn> find(String name, String runId) {
        var options = turnPageOptions(name);
        options.setFieldSelector(options.getFieldSelector().andQuery(equal("spec.runId", runId)));
        return client.listBy(PageChatTurnRecord.class, options,
                PageRequestImpl.of(1, 1, Sort.unsorted()))
            .flatMap(result -> Mono.justOrEmpty(result.getItems().stream().findFirst()))
            .map(record -> new ArchivedTurn(record.getSpec().getSessionId(), record.getSpec().getTurn()));
    }

    private Mono<PageChatTurnRecord> archive(String name, String sessionId, ChatApi.Turn turn) {
        var record = new PageChatTurnRecord();
        var metadata = new Metadata();
        metadata.setName(turnName(sessionId, turn.runId()));
        record.setMetadata(metadata);
        var spec = new PageChatTurnRecord.Spec();
        spec.setPageName(name);
        spec.setSessionId(sessionId);
        spec.setRunId(turn.runId());
        spec.setSortKey(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'")
            .withZone(java.time.ZoneOffset.UTC).format(turn.createdAt()) + "/" + turn.runId());
        spec.setTurn(turn);
        record.setSpec(spec);
        return client.fetch(PageChatTurnRecord.class, metadata.getName())
            .switchIfEmpty(Mono.defer(() -> client.create(record).onErrorResume(
                error -> error instanceof OptimisticLockingFailureException
                    || error instanceof DataIntegrityViolationException,
                error -> client.fetch(PageChatTurnRecord.class, metadata.getName())
                    .switchIfEmpty(Mono.error(error)))));
    }

    private static String turnName(String sessionId, String runId) {
        return UUID.nameUUIDFromBytes((sessionId + "/" + runId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private Mono<Void> touchSession(String name, String sessionId) {
        if (name.equals(sessionId)) return Mono.empty();
        return client.fetch(PageChatSession.class, sessionId).flatMap(session -> {
            session.getSpec().setUpdatedAt(Instant.now());
            return client.update(session);
        }).then();
    }

    private static ListOptions sessionOptions(String name) {
        return new ListOptions().setFieldSelector(FieldSelector.of(or(equal("spec.pageName", name),
            and(equal("metadata.name", name), isNull("spec.pageName"))))
            .andQuery(isNull("metadata.deletionTimestamp")));
    }

    private static ListOptions turnPageOptions(String name) {
        return new ListOptions().setFieldSelector(FieldSelector.of(equal("spec.pageName", name))
            .andQuery(isNull("metadata.deletionTimestamp")));
    }

    private static ListOptions turnOptions(String sessionId) {
        return new ListOptions().setFieldSelector(FieldSelector.of(equal("spec.sessionId", sessionId))
            .andQuery(isNull("metadata.deletionTimestamp")));
    }

    /** Called after page deletion is accepted; the page finalizer retains cleanup responsibility. */
    public static Mono<Void> deletePageHistory(ReactiveExtensionClient client, String name) {
        return client.listAll(PageChatTurnRecord.class, turnPageOptions(name), Sort.unsorted())
            .concatMap(client::delete)
            .thenMany(client.listAll(PageChatCheckpoint.class, turnPageOptions(name), Sort.unsorted()))
            .concatMap(client::delete)
            .thenMany(client.listAll(PageChatSession.class, sessionOptions(name), Sort.unsorted()))
            .concatMap(client::delete).then();
    }

    private static String activeSessionId(PageChatSession coordinator) {
        var id = coordinator.getSpec().getActiveSessionId();
        return id == null ? coordinator.getMetadata().getName() : id;
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
