package run.halo.aipagebuilder.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.utils.JsonUtils;
import run.halo.aipagebuilder.memory.SiteMemoryService.Change;
import run.halo.aipagebuilder.memory.SiteMemoryService.SourceContext;
import run.halo.aipagebuilder.model.SiteMemory;
import run.halo.aipagebuilder.model.SiteMemory.Entry;
import run.halo.aipagebuilder.model.SiteMemory.Source;

class SiteMemoryServiceTest {
    private static final SourceContext CONTEXT =
        new SourceContext("page-1", "session-1", "run-1", "admin");
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
        when(client.update(any(SiteMemory.class))).thenAnswer(invocation ->
            Mono.fromSupplier(() -> commit(invocation.getArgument(0))));
    }

    @Test
    void createsFixedSingletonOnceAndLoadsStoredVersion() {
        var first = service.load().block();
        var second = service.load().block();

        assertThat(first.getMetadata().getName()).isEqualTo("site-memory");
        assertThat(first.getMetadata().getVersion()).isEqualTo(1L);
        assertThat(first.getSpec().getEntries()).isEmpty();
        assertThat(second).isEqualTo(first);
        verify(client, times(1)).create(any(SiteMemory.class));
    }

    @Test
    void reloadsWinnerAfterConcurrentCreation() {
        var winner = memory(7L, List.of(entry("existing", "key", "known")));
        when(client.create(any(SiteMemory.class))).thenReturn(Mono.defer(() -> {
            stored.set(winner);
            return Mono.error(new OptimisticLockingFailureException("Already exists"));
        }));

        assertThat(service.load().block()).isEqualTo(winner);
        verify(client, times(2)).fetch(SiteMemory.class, SiteMemory.NAME);
        verify(client, times(1)).create(any(SiteMemory.class));
    }

    @Test
    void reloadsDuplicateKeyWinnerButPreservesConflictWhenNoWinnerExists() {
        var winner = memory(3L, List.of());
        when(client.create(any(SiteMemory.class))).thenReturn(Mono.defer(() -> {
            stored.set(winner);
            return Mono.error(new DuplicateKeyException("Already exists"));
        }));
        assertThat(service.load().block()).isEqualTo(winner);

        stored.set(null);
        var failure = new DuplicateKeyException("No visible winner");
        when(client.create(any(SiteMemory.class))).thenReturn(Mono.error(failure));
        assertThatThrownBy(() -> service.load().block()).isSameAs(failure);
    }

    @Test
    void reloadsWinnerAfterHaloWrapsDuplicateKeyButPreservesOtherBadRequests() {
        var winner = memory(3L, List.of());
        when(client.create(any(SiteMemory.class))).thenReturn(Mono.defer(() -> {
            stored.set(winner);
            return Mono.error(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "Duplicate name detected.", new DuplicateKeyException("Already exists")));
        }));
        assertThat(service.load().block()).isEqualTo(winner);
        stored.set(null);
        var failure = new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
            "Invalid resource");
        when(client.create(any(SiteMemory.class))).thenReturn(Mono.error(failure));
        assertThatThrownBy(() -> service.load().block()).isSameAs(failure);
    }

    @Test
    void uncertainStorageFailuresAreNotSwallowedOrRetried() {
        var failure = new IllegalStateException("Connection lost after write");
        when(client.create(any(SiteMemory.class))).thenReturn(Mono.error(failure));
        assertThatThrownBy(() -> service.load().block()).isSameAs(failure);
        verify(client, times(1)).fetch(SiteMemory.class, SiteMemory.NAME);
        verify(client, times(1)).create(any(SiteMemory.class));

        stored.set(memory(1L, List.of()));
        when(client.update(any(SiteMemory.class))).thenReturn(Mono.error(failure));
        assertThatThrownBy(() -> apply(add("Brand", "Name", "Acme"))).isSameAs(failure);
        verify(client, times(1)).update(any(SiteMemory.class));
    }

    @Test
    void addsNormalizedUniqueKeysWithoutLosingDisplayCaseAndTreatsSameValueAsNoOp() {
        service.load().block();
        var first = apply(add(" My  Brand ", " Primary Color ", " Blue "));
        var entry = first.getSpec().getEntries().getFirst();
        assertThat(entry.subject()).isEqualTo("My  Brand");
        assertThat(entry.key()).isEqualTo("Primary Color");
        assertThat(entry.value()).isEqualTo("Blue");
        assertThat(entry.id()).isNotBlank();
        assertThat(entry.updatedAt()).isNotNull();
        assertThat(entry.source()).isEqualTo(
            new Source("page-1", "session-1", "run-1", "source-1", "user said this", "admin"));

        clearInvocations(client);
        var duplicate = service.apply(first.getMetadata().getVersion(),
            List.of(add("my\tbrand", "primary color", "Blue")),
            new SourceContext("page-2", "session-2", "run-2", "other")).block();
        assertThat(duplicate).isEqualTo(first);
        verify(client, never()).update(any(SiteMemory.class));
        assertStatus(409, () -> apply(add("my brand", "primary color", "Red")));
        assertThat(stored.get()).isEqualTo(first);
    }

    @Test
    void keyComparisonDoesNotCollapseDifferentSubjectAndKeyPairs() {
        service.load().block();
        var result = apply(add("a", "b:c", "first"), add("a:b", "c", "second"));
        assertThat(result.getSpec().getEntries()).hasSize(2);
    }

    @Test
    void updateKeepsIdAndReplacesOnlyLatestSourceAndNoOpPreservesVersion() {
        service.load().block();
        var first = apply(add("Brand", "Color", "Blue"));
        var original = first.getSpec().getEntries().getFirst();
        var updated = service.apply(first.getMetadata().getVersion(), List.of(new Change("update",
            original.id(), "Brand", "Color", "brand_preference", "Red", "source-2",
            "I prefer red")), new SourceContext("page-2", "session-2", "run-2", "editor")).block();
        var replacement = updated.getSpec().getEntries().getFirst();
        assertThat(replacement.id()).isEqualTo(original.id());
        assertThat(replacement.value()).isEqualTo("Red");
        assertThat(replacement.kind()).isEqualTo("brand_preference");
        assertThat(replacement.source()).isEqualTo(new Source("page-2", "session-2", "run-2",
            "source-2", "I prefer red", "editor"));

        clearInvocations(client);
        var noOp = apply(new Change("update", original.id(), "brand", "color",
            "brand_preference", "Red", "source-3", "still red"));
        assertThat(noOp).isEqualTo(updated);
        verify(client, never()).update(any(SiteMemory.class));
    }

    @Test
    void deletionKeepsSingletonAndRequiresExistingId() {
        service.load().block();
        var first = apply(add("Brand", "Name", "Acme"));
        var id = first.getSpec().getEntries().getFirst().id();
        var deleted = apply(delete(id));
        assertThat(deleted.getSpec().getEntries()).isEmpty();
        assertThat(deleted.getMetadata().getName()).isEqualTo(SiteMemory.NAME);
        assertThat(deleted.getMetadata().getVersion()).isEqualTo(3L);
        assertThat(service.load().block()).isEqualTo(deleted);
        verify(client, never()).delete(any(SiteMemory.class));
        assertStatus(409, () -> apply(delete(id)));
        assertStatus(409, () -> apply(new Change("update", id, "Brand", "Name", "fact",
            "Unknown", "source-2", "unknown")));
    }

    @Test
    void deletionClearsAllSurvivingQuotesAfterTheWholeBatch() {
        for (boolean deleteFirst : List.of(true, false)) {
            var original = entry("name", "Name", "Acme");
            stored.set(memory(1L, List.of(original, entry("phone", "Phone", "0571-12345678"))));
            var added = new Change("add", null, "Brand", "Email", "fact", "contact@example.com",
                "source-2", "电话 0571 12345678，邮箱 contact@example.com");
            var changes = deleteFirst ? List.of(delete("phone"), added)
                : List.of(added, delete("phone"));
            var result = service.apply(1L, changes, CONTEXT).block();
            assertThat(result.getSpec().getEntries()).hasSize(2).allSatisfy(entry ->
                assertThat(entry.source().quote()).isEmpty());
            assertThat(result.getSpec().getEntries().getFirst())
                .usingRecursiveComparison().ignoringFields("source.quote").isEqualTo(original);
            assertThat(result.getSpec().getEntries().getLast().value()).isEqualTo("contact@example.com");
            assertThat(service.load().block()).isEqualTo(result);
            // Cleared provenance remains valid for later batches, including a no-op add.
            assertThat(apply(add("Brand", "Name", "Acme"))).isEqualTo(result);
            assertThat(apply(add("Brand", "Hours", "9–5")).getSpec().getEntries()).hasSize(3);
        }
    }

    @Test
    void failedDeletionBatchLeavesStoredQuotesAndEntriesIntact() {
        var original = memory(1L, List.of(entry("name", "Name", "Acme"),
            entry("phone", "Phone", "0571-12345678")));
        stored.set(original);
        when(client.fetch(SiteMemory.class, SiteMemory.NAME)).thenReturn(Mono.just(original));
        assertStatus(409, () -> apply(delete("phone"), delete("missing")));
        assertThat(stored.get()).isEqualTo(original);
        assertThat(original.getSpec().getEntries()).hasSize(2).allSatisfy(entry ->
            assertThat(entry.source().quote()).isEqualTo("user said this"));
        verify(client, never()).update(any(SiteMemory.class));
    }

    @Test
    void staleVersionRejectsEvenNoOpAndNeverWrites() {
        service.load().block();
        apply(add("Brand", "Name", "Acme"));
        clearInvocations(client);
        assertStatus(409, () -> service.apply(1L, List.of(add("Brand", "Name", "Acme")),
            CONTEXT).block());
        verify(client, never()).update(any(SiteMemory.class));
    }

    @Test
    void concurrentChangesFromSameVersionHaveOnlyOneWinnerWithoutRetry() {
        stored.set(memory(1L, List.of()));
        Sinks.One<Boolean> release = Sinks.one();
        when(client.update(any(SiteMemory.class))).thenAnswer(invocation -> {
            SiteMemory proposed = invocation.getArgument(0);
            return release.asMono().map(ignored -> commit(proposed));
        });
        var first = service.apply(1L, List.of(add("Brand", "Name", "Acme")), CONTEXT)
            .materialize().toFuture();
        var second = service.apply(1L, List.of(add("Brand", "Color", "Blue")), CONTEXT)
            .materialize().toFuture();
        release.tryEmitValue(true);
        var results = List.of(first.join(), second.join());
        assertThat(results.stream().filter(signal -> signal.hasValue())).hasSize(1);
        assertThat(results.stream().filter(signal -> signal.isOnError())).singleElement()
            .satisfies(signal -> assertThat(signal.getThrowable())
                .isInstanceOfSatisfying(ResponseStatusException.class,
                    error -> assertThat(error.getStatusCode().value()).isEqualTo(409)));
        assertThat(stored.get().getSpec().getEntries()).hasSize(1);
        assertThat(stored.get().getMetadata().getVersion()).isEqualTo(2L);
        verify(client, times(2)).update(any(SiteMemory.class));
    }

    @Test
    void rejectsWholeBatchOnLateFailureWithoutMutatingFetchedObject() {
        var original = memory(1L, List.of(entry("id-1", "Color", "Blue")));
        stored.set(original);
        when(client.fetch(SiteMemory.class, SiteMemory.NAME)).thenReturn(Mono.just(original));
        assertStatus(409, () -> apply(add("Brand", "Name", "Acme"),
            add("Brand", "Color", "Red")));
        assertThat(original.getSpec().getEntries()).hasSize(1);
        assertThat(original.getSpec().getEntries().getFirst().value()).isEqualTo("Blue");
        verify(client, never()).update(any(SiteMemory.class));
    }

    @Test
    void rejectsDuplicateKeysIdsAndRenamingOntoOccupiedKey() {
        stored.set(memory(1L, List.of(entry("id-1", "Color", "Blue"),
            entry("id-2", "Name", "Acme"))));
        assertStatus(400, () -> apply(add("Brand", "new", "A"), add("brand", "NEW", "A")));
        assertStatus(400, () -> apply(delete("id-1"), delete("id-1")));
        assertStatus(400, () -> apply(delete("id-1"), add("brand", "color", "Red")));
        assertStatus(400, () -> apply(new Change("update", "id-1", "Brand", "new", "fact",
            "Red", "source-1", "quote"), delete("id-1")));
        assertStatus(409, () -> apply(new Change("update", "id-1", "Brand", "Name", "fact",
            "Other", "source-1", "quote")));
        assertThat(stored.get().getSpec().getEntries()).hasSize(2);
        verify(client, never()).update(any(SiteMemory.class));
    }

    @Test
    void validatesBatchSizeFieldsAndTrustedContextBeforeWriting() {
        service.load().block();
        assertStatus(400, () -> service.apply(null, List.of(add("a", "b", "c")), CONTEXT).block());
        assertStatus(400, () -> service.apply(1L, List.of(), CONTEXT).block());
        assertStatus(400, () -> service.apply(1L,
            IntStream.range(0, 6).mapToObj(i -> add("a", "key" + i, "c")).toList(),
            CONTEXT).block());
        assertStatus(400, () -> service.apply(1L, List.of(add("a", "b", "c")), null).block());
        assertStatus(400, () -> service.apply(1L, List.of(add("a", "b", "c")),
            new SourceContext("page", "session", "run", "invalid actor")).block());
        assertStatus(400, () -> apply(add("a", "b", "x".repeat(501))));
        assertStatus(400, () -> apply(add("a".repeat(101), "b", "c")));
        assertStatus(400, () -> apply(add("a", "b", "bad\u0000value")));
        assertStatus(400, () -> apply(new Change("add", null, "a", "b", "other", "c", "s", "q")));
        assertStatus(400, () -> apply(new Change("add", null, "a", "b", "fact", "c", " ", "q")));
        assertStatus(400, () -> apply(new Change("add", null, "a", "b", "fact", "c", "s",
            "q".repeat(501))));
        assertStatus(400, () -> apply(new Change("add", null, "a", "b", "fact", "c",
            "s".repeat(513), "q")));
        assertStatus(400, () -> apply(new Change("add", "chosen-id", "a", "b", "fact", "c",
            "s", "q")));
        verify(client, never()).update(any(SiteMemory.class));
    }

    @Test
    void deleteAlsoRequiresBoundedSourceEvidence() {
        stored.set(memory(1L, List.of(entry("id-1", "Color", "Blue"))));
        assertStatus(400, () -> apply(new Change("delete", "id-1", null, null, null, null,
            null, null)));
        assertStatus(400, () -> apply(new Change("delete", "id-1", null, null, null, null,
            "source", "q".repeat(501))));
        verify(client, never()).update(any(SiteMemory.class));
    }

    @Test
    void limitsEntriesButAllowsAtomicReplacementAtCapacity() {
        stored.set(memory(1L, IntStream.range(0, 30)
            .mapToObj(i -> entry("id-" + i, "key" + i, "v")).toList()));
        assertStatus(422, () -> apply(add("Brand", "new", "new")));
        assertThat(stored.get().getSpec().getEntries()).hasSize(30);
        var updated = apply(add("Brand", "new", "new"), delete("id-0"));
        assertThat(updated.getSpec().getEntries()).hasSize(30);
        assertThat(updated.getSpec().getEntries()).noneMatch(entry -> entry.id().equals("id-0"));
        verify(client, times(1)).update(any(SiteMemory.class));
    }

    @Test
    void enforcesContentBudgetIncludingSubjectAndKey() {
        var entries = new ArrayList<Entry>();
        for (int i = 0; i < 15; i++) {
            entries.add(entry("id-" + i, "key" + i, "v".repeat(500)));
        }
        stored.set(memory(1L, entries));
        var snapshot = JsonUtils.deepCopy(stored.get());
        assertStatus(422, () -> apply(add("Brand", "last", "v".repeat(500))));
        assertThat(stored.get()).isEqualTo(snapshot);
        verify(client, never()).update(any(SiteMemory.class));
    }

    @Test
    void enforcesMetadataBudgetAndSerializedUtf8BudgetSeparately() {
        var largeSource = new Source("p".repeat(253), "s".repeat(253), "r".repeat(253),
            "i".repeat(512), "q".repeat(500), "a".repeat(253));
        stored.set(memory(1L, IntStream.range(0, 11).mapToObj(i ->
            new Entry("id-" + i, "Brand", "key" + i, "fact", "v", largeSource, Instant.now()))
            .toList()));
        assertStatus(422, () -> apply(new Change("add", null, "Brand", "last", "fact", "v",
            largeSource.sourceId(), largeSource.quote()),
            new SourceContext(largeSource.pageName(), largeSource.sessionId(),
                largeSource.runId(), largeSource.actor())));

        var chineseSource = new Source("页".repeat(70), "会".repeat(70), "运".repeat(70),
            "源".repeat(70), "引".repeat(400), "人".repeat(70));
        stored.set(memory(1L, IntStream.range(0, 29).mapToObj(i ->
            new Entry("id-" + i, "Brand", "key" + i, "fact", "v", chineseSource, Instant.now()))
            .toList()));
        while (JsonUtils.objectToJson(stored.get()).getBytes(StandardCharsets.UTF_8).length
            > 64 * 1024) {
            stored.get().getSpec().getEntries().removeLast();
        }
        assertThatThrownBy(() -> apply(new Change("add", null, "Brand", "last", "fact", "v",
            chineseSource.sourceId(), chineseSource.quote()),
            new SourceContext(chineseSource.pageName(), chineseSource.sessionId(),
                chineseSource.runId(), chineseSource.actor())))
            .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                assertThat(error.getStatusCode().value()).isEqualTo(422);
                assertThat(error.getReason()).contains("64 KiB");
            });
        verify(client, never()).update(any(SiteMemory.class));
    }

    @Test
    void waitsForWriteCompletionBeforeReportingSuccess() {
        stored.set(memory(1L, List.of()));
        var failure = new IllegalStateException("Commit failed");
        // A publisher can emit a value before a transactional completion error.
        when(client.update(any(SiteMemory.class))).thenAnswer(invocation ->
            Mono.fromDirect(Mono.just(invocation.<SiteMemory>getArgument(0))
                .concatWith(Mono.<SiteMemory>error(failure))));
        StepVerifier.create(service.apply(1L, List.of(add("Brand", "Name", "Acme")), CONTEXT))
            .expectErrorMatches(error -> error == failure).verify();
    }

    private SiteMemory apply(Change... changes) {
        return service.apply(stored.get().getMetadata().getVersion(), List.of(changes), CONTEXT)
            .block();
    }

    private SiteMemory apply(Change change, SourceContext context) {
        return service.apply(stored.get().getMetadata().getVersion(), List.of(change), context)
            .block();
    }

    private SiteMemory commit(SiteMemory proposed) {
        var current = stored.get();
        if (!Objects.equals(current.getMetadata().getVersion(),
            proposed.getMetadata().getVersion())) {
            throw new OptimisticLockingFailureException("Changed");
        }
        var next = JsonUtils.deepCopy(proposed);
        next.getMetadata().setVersion(current.getMetadata().getVersion() + 1);
        if (!stored.compareAndSet(current, next)) {
            throw new OptimisticLockingFailureException("Changed");
        }
        return JsonUtils.deepCopy(next);
    }

    private static Change add(String subject, String key, String value) {
        return new Change("add", null, subject, key, "fact", value, "source-1", "user said this");
    }

    private static Change delete(String id) {
        return new Change("delete", id, null, null, null, null, "source-1", "forget this");
    }

    private static Entry entry(String id, String key, String value) {
        return new Entry(id, "Brand", key, "fact", value,
            new Source("page-1", "session-1", "run-1", "source-1", "user said this", "admin"),
            Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static SiteMemory memory(long version, List<Entry> entries) {
        var memory = new SiteMemory();
        var metadata = new Metadata();
        metadata.setName(SiteMemory.NAME);
        metadata.setVersion(version);
        memory.setMetadata(metadata);
        memory.setSpec(new SiteMemory.Spec());
        memory.getSpec().setEntries(new ArrayList<>(entries));
        return memory;
    }

    private static void assertStatus(int status, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(status));
    }
}
