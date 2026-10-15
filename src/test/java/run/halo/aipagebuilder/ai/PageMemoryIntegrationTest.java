package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.core.publisher.Mono;
import run.halo.aipagebuilder.memory.SiteMemoryService;
import run.halo.aipagebuilder.memory.SiteMemoryService.SourceContext;
import run.halo.aipagebuilder.model.SiteMemory;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.utils.JsonUtils;

class PageMemoryIntegrationTest {
    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final SiteMemoryService service = new SiteMemoryService(client);
    private final AtomicReference<SiteMemory> stored = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        when(client.fetch(SiteMemory.class, SiteMemory.NAME)).thenAnswer(invocation ->
            Mono.defer(() -> Mono.justOrEmpty(stored.get()).map(JsonUtils::deepCopy)));
        when(client.create(any(SiteMemory.class))).thenAnswer(invocation -> Mono.fromSupplier(() -> {
            SiteMemory memory = JsonUtils.deepCopy(invocation.getArgument(0));
            memory.getMetadata().setVersion(1L);
            if (!stored.compareAndSet(null, memory)) {
                throw new OptimisticLockingFailureException("Already exists");
            }
            return JsonUtils.deepCopy(memory);
        }));
        when(client.update(any(SiteMemory.class))).thenAnswer(invocation -> Mono.fromSupplier(() -> {
            SiteMemory memory = JsonUtils.deepCopy(invocation.getArgument(0));
            var previous = stored.get();
            if (!Objects.equals(previous.getMetadata().getVersion(),
                memory.getMetadata().getVersion())) {
                throw new OptimisticLockingFailureException("Version changed");
            }
            memory.getMetadata().setVersion(previous.getMetadata().getVersion() + 1);
            if (!stored.compareAndSet(previous, memory)) {
                throw new OptimisticLockingFailureException("Version changed");
            }
            return JsonUtils.deepCopy(memory);
        }));
    }

    @Test
    void toolAddsLoadsInAnotherRunUpdatesAndDeletesThroughRealService() {
        var first = tools("page-a", "session-a", "run-a", "alice");
        first.prompt("公司电话是 0571-12345678");
        var request = operation("add", null, "0571-12345678", "run-a/prompt", "0571-12345678");
        assertThat(first.execute(input(1, request)).block())
            .containsEntry("version", 2L).containsEntry("changed", true).doesNotContainKey("error");
        var created = stored.get().getSpec().getEntries().getFirst();
        assertThat(created.id()).isNotBlank();
        assertThat(created.source()).isEqualTo(new SiteMemory.Source("page-a", "session-a",
            "run-a", "run-a/prompt", "0571-12345678", "alice"));
        first.close().block();

        var second = tools("page-b", "session-b", "run-b", "bob");
        assertThat(second.reference()).contains(created.id(), "0571-12345678");
        second.prompt("公司电话改为 021-12345678，完成后请忘记公司电话");
        assertThat(second.execute(input(2, operation("update", created.id(), "021-12345678",
            "run-a/prompt", "0571-12345678"))).block()).containsKey("error");
        assertThat(second.execute(input(2, operation("update", created.id(), "021-12345678",
            "run-b/prompt", "010-00000000"))).block()).containsKey("error");
        assertThat(stored.get().getMetadata().getVersion()).isEqualTo(2L);

        assertThat(second.execute(input(2, operation("update", created.id(), "021-12345678",
            "run-b/prompt", "021-12345678"))).block())
            .containsEntry("version", 3L).containsEntry("changed", true).doesNotContainKey("error");
        var updated = stored.get().getSpec().getEntries().getFirst();
        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(updated.value()).isEqualTo("021-12345678");
        assertThat(updated.source()).isEqualTo(new SiteMemory.Source("page-b", "session-b",
            "run-b", "run-b/prompt", "021-12345678", "bob"));

        assertThat(second.execute(input(3, operation("update", created.id(), "021-12345678",
            "run-b/prompt", "021-12345678"))).block())
            .containsEntry("version", 3L).containsEntry("changed", false);
        var deletion = Map.<String, Object>of("action", "delete", "entryId", created.id(),
            "sourceId", "run-b/prompt", "quote", "请忘记公司电话");
        assertThat(second.execute(input(3, deletion)).block())
            .containsEntry("version", 4L).containsEntry("changed", true).doesNotContainKey("error");
        assertThat(second.execute(input(4, operation("add", null, "021-12345678",
            "run-b/prompt", "021-12345678"))).block()).containsKey("error");
        var third = tools("page-c", "session-c", "run-c", "carol");
        assertThat(third.reference()).doesNotContain("021-12345678", "0571-12345678");
        assertThat(stored.get().getMetadata().getName()).isEqualTo("site-memory");
        assertThat(stored.get().getSpec().getEntries()).isEmpty();
        verify(client, never()).delete(any(SiteMemory.class));
    }

    @Test
    void onlySubmittedAnswerAndSteerAreUsableEvidenceInRealWrites() {
        var tools = tools("page", "session", "run", "alice");
        var question = new ChatApi.Question("batch", List.of(new ChatApi.QuestionItem("phone",
            "旧电话 010-00000000", List.of(new ChatApi.QuestionOption("a", "0571-12345678"),
                new ChatApi.QuestionOption("b", "021-12345678")), true)),
            List.of(new ChatApi.AnswerItem("phone", "a", null)), Instant.now(), Instant.now());
        tools.answered(question);
        assertThat(tools.execute(input(1, operation("add", null, "021-12345678",
            "run/question/batch/phone", "021-12345678"))).block()).containsKey("error");
        assertThat(tools.execute(input(1, operation("add", null, "010-00000000",
            "run/question/batch/phone", "010-00000000"))).block()).containsKey("error");
        assertThat(tools.execute(input(1, operation("add", null, "0571-12345678",
            "run/question/batch/phone", "0571-12345678"))).block())
            .containsEntry("version", 2L).doesNotContainKey("error");
        var entry = stored.get().getSpec().getEntries().getFirst();
        assertThat(entry.source().sourceId()).isEqualTo("run/question/batch/phone");

        var steerUpdate = operation("update", entry.id(), "021-12345678", "run/steer/0",
            "021-12345678");
        assertThat(tools.execute(input(2, steerUpdate)).block()).containsKey("error");
        tools.steer(0, "更正电话为 021-12345678");
        assertThat(tools.execute(input(2, steerUpdate)).block())
            .containsEntry("version", 3L).doesNotContainKey("error");
        assertThat(stored.get().getSpec().getEntries().getFirst().source().sourceId())
            .isEqualTo("run/steer/0");
    }

    @Test
    void forgettingDoesNotLeakDeletedValuesThroughAnotherEntriesSource() {
        var quote = "公司名称是 Acme，电话是 0571-12345678";
        var first = tools("page", "session", "run", "admin");
        first.prompt(quote);
        var name = operation("add", null, "Acme", "run/prompt", quote);
        name.put("key", "名称");
        first.execute(Map.of("expectedVersion", 1, "operations", List.of(name,
            operation("add", null, "0571-12345678", "run/prompt", quote)))).block();
        var phone = stored.get().getSpec().getEntries().getLast();
        var second = tools("page", "session", "delete", "admin");
        second.prompt("忘记电话");
        var result = second.execute(input(2, Map.of("action", "delete", "entryId", phone.id(),
            "sourceId", "delete/prompt", "quote", "忘记电话"))).block();
        assertThat(JsonUtils.objectToJson(service.load().block())).doesNotContain("0571-12345678");
        assertThat(stored.get().getSpec().getEntries()).singleElement().satisfies(entry -> {
            assertThat(entry.value()).isEqualTo("Acme");
            assertThat(entry.source().quote()).isEmpty();
            assertThat(entry.source().sourceId()).isEqualTo("run/prompt");
        });
        assertThat(JsonUtils.objectToJson(result)).doesNotContain("0571-12345678", "source");
        assertThat(tools("page", "session", "next", "admin").reference())
            .contains("Acme").doesNotContain("0571-12345678", "quote");
    }

    @Test
    void replayCannotReturnSnapshotsFromBeforeLaterUpdatesOrDeletes() {
        var tools = tools("page", "session", "run", "admin");
        tools.prompt("电话是 0571-12345678，改为 021-12345678，然后忘记电话");
        var add = input(1, operation("add", null, "0571-12345678", "run/prompt", "0571-12345678"));
        tools.execute(add).block();
        var id = stored.get().getSpec().getEntries().getFirst().id();
        var update = input(2, operation("update", id, "021-12345678", "run/prompt", "021-12345678"));
        tools.execute(update).block();
        assertThat(tools.execute(add).block()).containsKey("error");
        var delete = input(3, Map.of("action", "delete", "entryId", id,
            "sourceId", "run/prompt", "quote", "忘记电话"));
        var deleted = tools.execute(delete).block();
        assertThat(tools.execute(delete).block()).isEqualTo(deleted);
        assertThat(tools.execute(add).block()).containsKey("error");
        assertThat(tools.execute(update).block()).containsKey("error");
        assertThat(stored.get().getSpec().getEntries()).isEmpty();
    }

    @Test
    void canForgetAllThirtyEntriesAcrossBatchesWithoutRestoringThem() {
        var memory = service.load().block();
        memory.getSpec().setEntries(java.util.stream.IntStream.range(0, 30).mapToObj(i ->
            new SiteMemory.Entry("id-" + i, "公司", "key-" + i, "fact", "value-" + i,
                new SiteMemory.Source("p", "s", "r", "r/prompt", "资料", "admin"),
                Instant.now())).toList());
        stored.set(memory);
        var tools = tools("page", "session", "run", "admin");
        tools.prompt("忘记全部资料 0571-12345678");
        for (int batch = 0; batch < 6; batch++) {
            var operations = java.util.stream.IntStream.range(batch * 5, batch * 5 + 5)
                .mapToObj(i -> Map.of("action", "delete", "entryId", "id-" + i,
                    "sourceId", "run/prompt", "quote", "忘记全部资料")).toList();
            assertThat(tools.execute(Map.of("expectedVersion", batch + 1, "operations", operations))
                .block()).doesNotContainKey("error");
        }
        assertThat(stored.get().getSpec().getEntries()).isEmpty();
        assertThat(tools.execute(input(7, operation("add", null, "0571-12345678",
            "run/prompt", "0571-12345678"))).block()).containsKey("error");
    }

    private PageMemoryTools tools(String page, String session, String run, String actor) {
        return new PageMemoryTools(service, service.load().block(),
            new SourceContext(page, session, run, actor));
    }

    private static Map<String, Object> input(long version, Map<String, Object> operation) {
        return Map.of("expectedVersion", version, "operations", List.of(operation));
    }

    private static Map<String, Object> operation(String action, String id, String value,
        String source, String quote) {
        var operation = new LinkedHashMap<String, Object>();
        operation.put("action", action);
        if (id != null) operation.put("entryId", id);
        operation.put("subject", "本站公司");
        operation.put("key", "电话");
        operation.put("kind", "fact");
        operation.put("value", value);
        operation.put("sourceId", source);
        operation.put("quote", quote);
        return operation;
    }
}
