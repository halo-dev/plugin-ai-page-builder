package run.halo.aipagebuilder.ai;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.aipagebuilder.Json;
import run.halo.aipagebuilder.model.PageChatCheckpoint;
import run.halo.aipagebuilder.model.PageChatSession;
import run.halo.aipagebuilder.model.PageChatTurnRecord;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.PageRequest;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.index.query.Condition;
import run.halo.app.extension.index.query.IndexCondition;

/** Storage stand-in shared by protocol and runtime integration tests. */
final class ConversationStorage {
    final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    final Map<String, PageChatCheckpoint> checkpoints = new HashMap<>();
    final Map<String, PageChatSession> stored = new HashMap<>();
    final Map<String, PageChatTurnRecord> turns = new HashMap<>();

    void install() {
        var type = run.halo.aipagebuilder.model.PageChatCheckpoint.class;
        when(client.fetch(eq(type), anyString())).thenAnswer(i -> Mono.defer(() ->
            Mono.justOrEmpty(checkpoints.get(i.<String>getArgument(1))).map(Json::deepCopy)));
        when(client.create(any(type))).thenAnswer(i -> Mono.fromSupplier(() -> {
            run.halo.aipagebuilder.model.PageChatCheckpoint value = i.getArgument(0);
            if (checkpoints.containsKey(value.getMetadata().getName())) throw new OptimisticLockingFailureException("CAS");
            value.getMetadata().setVersion(1L);
            checkpoints.put(value.getMetadata().getName(), Json.deepCopy(value));
            return value;
        }));
        when(client.update(any(type))).thenAnswer(i -> Mono.fromSupplier(() -> {
            run.halo.aipagebuilder.model.PageChatCheckpoint value = i.getArgument(0);
            var previous = checkpoints.get(value.getMetadata().getName());
            if (!previous.getMetadata().getVersion().equals(value.getMetadata().getVersion())) throw new OptimisticLockingFailureException("CAS");
            value.getMetadata().setVersion(value.getMetadata().getVersion() + 1);
            checkpoints.put(value.getMetadata().getName(), Json.deepCopy(value));
            return value;
        }));
        when(client.delete(any(type))).thenAnswer(i -> Mono.fromSupplier(() -> {
            run.halo.aipagebuilder.model.PageChatCheckpoint value = i.getArgument(0);
            checkpoints.remove(value.getMetadata().getName());
            return value;
        }));
        when(client.listAll(eq(type), any(), any())).thenAnswer(i -> Flux.defer(() -> {
            ListOptions options = i.getArgument(1);
            return Flux.fromIterable(checkpoints.values().stream().filter(value -> matches(
                (Condition) options.getFieldSelector().query(),
                field -> switch (field) {
                    case "spec.pageName" -> value.getSpec().getPageName();
                    case "metadata.deletionTimestamp" -> value.getMetadata().getDeletionTimestamp() == null
                        ? null : value.getMetadata().getDeletionTimestamp().toString();
                    default -> null;
                }))
                .map(Json::deepCopy).toList());
        }));
        when(client.fetch(eq(PageChatSession.class), anyString())).thenAnswer(i -> Mono.defer(() ->
            Mono.justOrEmpty(stored.get(i.<String>getArgument(1))).map(Json::deepCopy)));
        when(client.create(any(PageChatSession.class))).thenAnswer(i -> Mono.fromSupplier(() -> {
            PageChatSession session = i.getArgument(0);
            session.getMetadata().setVersion(1L);
            stored.put(session.getMetadata().getName(), Json.deepCopy(session));
            return session;
        }));
        when(client.update(any(PageChatSession.class))).thenAnswer(i -> Mono.fromSupplier(() -> {
            PageChatSession session = i.getArgument(0);
            var previous = stored.get(session.getMetadata().getName());
            if (!previous.getMetadata().getVersion().equals(session.getMetadata().getVersion())) {
                throw new OptimisticLockingFailureException("CAS");
            }
            session.getMetadata().setVersion(session.getMetadata().getVersion() + 1);
            stored.put(session.getMetadata().getName(), Json.deepCopy(session));
            return session;
        }));
        when(client.fetch(eq(PageChatTurnRecord.class), anyString())).thenAnswer(i -> Mono.defer(() ->
            Mono.justOrEmpty(turns.get(i.<String>getArgument(1))).map(Json::deepCopy)));
        when(client.create(any(PageChatTurnRecord.class))).thenAnswer(i -> Mono.fromSupplier(() -> {
            PageChatTurnRecord record = i.getArgument(0);
            if (turns.containsKey(record.getMetadata().getName())) {
                throw new OptimisticLockingFailureException("already exists");
            }
            record.getMetadata().setVersion(1L);
            turns.put(record.getMetadata().getName(), Json.deepCopy(record));
            return record;
        }));
        when(client.listBy(eq(PageChatTurnRecord.class), any(), any())).thenAnswer(i -> Mono.fromSupplier(() -> {
            ListOptions options = i.getArgument(1);
            PageRequest request = i.getArgument(2);
            var matching = matchingTurns(options);
            return new ListResult<>(1, request.getPageSize(), matching.size(),
                matching.subList(0, Math.min(request.getPageSize(), matching.size())));
        }));
        when(client.listAll(eq(PageChatTurnRecord.class), any(), any())).thenAnswer(i ->
            Flux.defer(() -> Flux.fromIterable(matchingTurns(i.getArgument(1)))));
        when(client.listAll(eq(PageChatSession.class), any(), any())).thenAnswer(i -> Flux.defer(() -> {
            ListOptions options = i.getArgument(1);
            return Flux.fromIterable(stored.values().stream().filter(session -> matches(
                (Condition) options.getFieldSelector().query(), field -> switch (field) {
                    case "metadata.name" -> session.getMetadata().getName();
                    case "metadata.deletionTimestamp" -> session.getMetadata().getDeletionTimestamp() == null
                        ? null : session.getMetadata().getDeletionTimestamp().toString();
                    case "spec.pageName" -> session.getSpec().getPageName();
                    default -> null;
                })).map(Json::deepCopy).toList());
        }));
        when(client.delete(any(PageChatTurnRecord.class))).thenAnswer(i -> Mono.fromSupplier(() -> {
            PageChatTurnRecord record = i.getArgument(0);
            turns.remove(record.getMetadata().getName());
            return record;
        }));
        when(client.delete(any(PageChatSession.class))).thenAnswer(i -> Mono.fromSupplier(() -> {
            PageChatSession session = i.getArgument(0);
            stored.remove(session.getMetadata().getName());
            return session;
        }));
    }

    private List<PageChatTurnRecord> matchingTurns(ListOptions options) {
        return turns.values().stream().filter(record -> matches(
            (Condition) options.getFieldSelector().query(), field -> switch (field) {
                case "spec.pageName" -> record.getSpec().getPageName();
                case "metadata.deletionTimestamp" -> record.getMetadata().getDeletionTimestamp() == null
                    ? null : record.getMetadata().getDeletionTimestamp().toString();
                case "spec.sessionId" -> record.getSpec().getSessionId();
                case "spec.runId" -> record.getSpec().getRunId();
                case "spec.sortKey" -> record.getSpec().getSortKey();
                default -> null;
            })).sorted(java.util.Comparator.comparing((PageChatTurnRecord record) -> record.getSpec().getSortKey()).reversed())
            .map(Json::deepCopy).toList();
    }

    private static boolean matches(Condition query,
        java.util.function.Function<String, String> value) {
        var type = query.getClass().getSimpleName();
        if (query instanceof IndexCondition condition) {
            var actual = value.apply(condition.indexName());
            return switch (type) {
                case "IsNullCondition" -> actual == null;
                case "EqualCondition" -> java.util.Objects.equals(actual, component(query, "key"));
                case "LessThanCondition" -> actual != null && actual.compareTo((String) component(query, "upperBound")) < 0;
                default -> throw new AssertionError(type);
            };
        }
        boolean left = matches((Condition) component(query, "left"), value);
        boolean right = matches((Condition) component(query, "right"), value);
        return type.equals("OrCondition") ? left || right : left && right;
    }

    private static Object component(Condition query, String name) {
        try {
            var accessor = query.getClass().getDeclaredMethod(name);
            accessor.setAccessible(true);
            return accessor.invoke(query);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

}
