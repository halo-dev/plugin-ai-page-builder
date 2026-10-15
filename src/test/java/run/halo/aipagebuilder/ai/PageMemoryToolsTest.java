package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import run.halo.aipagebuilder.memory.SiteMemoryService;
import run.halo.aipagebuilder.model.SiteMemory;
import run.halo.app.extension.Metadata;

class PageMemoryToolsTest {
    private final SiteMemoryService service = mock(SiteMemoryService.class);
    private final PageMemoryTools tools = new PageMemoryTools(service, snapshot(1L),
        new SiteMemoryService.SourceContext("page", "session", "run", "admin"));

    @Test
    void requiresActualCurrentRunQuoteAndDoesNotAcceptHistoricSources() {
        tools.prompt("公司电话 0571-12345678");
        assertThat(tools.execute(input(1, "old/prompt", "0571-12345678")).block()).containsKey("error");
        assertThat(tools.execute(input(1, "run/prompt", "021-12345678")).block()).containsKey("error");
        verify(service, never()).apply(any(), any(), any());
        when(service.apply(eq(1L), any(), any())).thenReturn(Mono.just(snapshot(2L)));
        assertThat(tools.execute(input(1, "run/prompt", "0571-12345678")).block())
            .containsEntry("version", 2L).doesNotContainKey("error");
    }

    @Test
    void onlySelectedAnswerAndSubmittedSteerCanBeReferenced() {
        var question = new ChatApi.Question("batch", List.of(new ChatApi.QuestionItem("phone",
            "未选择的 021-00000000", List.of(new ChatApi.QuestionOption("a", "0571-12345678"),
                new ChatApi.QuestionOption("b", "010-11111111")), true)),
            List.of(new ChatApi.AnswerItem("phone", "a", null)), Instant.now(), Instant.now());
        tools.answered(question);
        var source = "run/question/batch/phone";
        assertThat(tools.execute(input(1, source, "010-11111111")).block()).containsKey("error");
        assertThat(tools.execute(input(1, source, "021-00000000")).block()).containsKey("error");
        assertThat(tools.execute(input(1, "run/steer/0", "0571-12345678")).block()).containsKey("error");
        tools.steer(0, "电话改为 0571-12345678");
        when(service.apply(eq(1L), any(), any())).thenReturn(Mono.just(snapshot(2L)));
        assertThat(tools.execute(input(1, source, "0571-12345678")).block()).doesNotContainKey("error");
        assertThat(tools.reference()).contains("run/steer/0", "0571-12345678");
    }

    @Test
    void cachesRepeatedBatchAndStopsAfterExternalVersionConflict() {
        tools.prompt("0571-12345678");
        when(service.apply(eq(1L), any(), any())).thenReturn(Mono.just(snapshot(2L)));
        var request = input(1, "run/prompt", "0571-12345678");
        assertThat(tools.execute(request).block()).isEqualTo(tools.execute(request).block());
        verify(service, times(1)).apply(any(), any(), any());
        when(service.apply(eq(2L), any(), any())).thenReturn(Mono.error(
            new ResponseStatusException(HttpStatus.CONFLICT, "资料已变化")));
        assertThat(tools.execute(input(2, "run/prompt", "0571-12345678")).block()).containsKey("error");
        assertThat(tools.execute(input(3, "run/prompt", "0571-12345678")).block()).containsKey("error");
        verify(service, times(2)).apply(any(), any(), any());
    }

    @Test
    void deletingClosesWritesSoEarlierPromptCannotRestoreTheFact() {
        tools.prompt("0571-12345678，请忘记公司电话");
        when(service.apply(eq(1L), any(), any())).thenReturn(Mono.just(snapshot(2L)));
        var deletion = Map.<String, Object>of("expectedVersion", 1L, "operations", List.of(Map.of(
            "action", "delete", "entryId", "phone", "sourceId", "run/prompt", "quote", "请忘记公司电话")));
        assertThat(tools.execute(deletion).block()).doesNotContainKey("error");
        assertThat(tools.execute(input(2, "run/prompt", "0571-12345678")).block()).containsKey("error");
        verify(service, times(1)).apply(any(), any(), any());
    }

    @Test
    void rejectsMixedDeletionBatchesBeforeWritingAndAllowsDeleteOnlyRetry() {
        tools.prompt("请忘记 0571-12345678");
        when(service.apply(eq(1L), any(), any())).thenReturn(Mono.just(snapshot(2L)));
        var deletion = Map.<String, Object>of("action", "delete", "entryId", "phone",
            "sourceId", "run/prompt", "quote", "请忘记 0571-12345678");
        for (var action : List.of("add", "update")) {
            var write = new java.util.LinkedHashMap<String, Object>(Map.of(
                "action", action, "subject", "本站公司", "key", "联系电话", "kind", "fact",
                "value", "0571-12345678", "sourceId", "run/prompt", "quote", "0571-12345678"));
            if ("update".equals(action)) write.put("entryId", "other");
            for (boolean deleteFirst : List.of(true, false)) {
                var operations = deleteFirst ? List.of(deletion, write) : List.of(write, deletion);
                assertThat(tools.execute(Map.of("expectedVersion", 1L, "operations", operations)).block())
                    .containsEntry("error", "删除记忆的批次只能包含删除操作，请单独提交删除批次");
            }
        }
        verify(service, never()).apply(any(), any(), any());
        assertThat(tools.execute(Map.of("expectedVersion", 1L, "operations", List.of(deletion)))
            .block()).containsEntry("version", 2L).doesNotContainKey("error");
        verify(service, times(1)).apply(any(), any(), any());
    }

    @Test
    void closingWaitsForInFlightWriteEvenWhenItsCallerUnsubscribes() {
        tools.prompt("0571-12345678");
        var stored = Sinks.<SiteMemory>one();
        when(service.apply(eq(1L), any(), any())).thenReturn(stored.asMono());
        var call = tools.execute(input(1, "run/prompt", "0571-12345678")).subscribe();
        var closed = new AtomicReference<Boolean>(false);
        tools.close().subscribe(ignored -> { }, error -> { }, () -> closed.set(true));
        call.dispose();
        assertThat(closed.get()).isFalse();
        assertThat(tools.execute(input(1, "run/prompt", "0571-12345678")).block()).containsKey("error");
        stored.tryEmitValue(snapshot(2L));
        assertThat(closed.get()).isTrue();
        verify(service, times(1)).apply(any(), any(), any());
    }

    @Test
    void timeoutStopsWaitingWithoutCancellingStorageOrAcceptingLateSuccess() {
        var clock = reactor.test.scheduler.VirtualTimeScheduler.getOrSet();
        try {
            tools.prompt("0571-12345678");
            var stored = Sinks.<SiteMemory>one();
            var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            when(service.apply(any(), any(), any()))
                .thenReturn(stored.asMono().doOnCancel(() -> cancelled.set(true)));
            var result = tools.execute(input(1, "run/prompt", "0571-12345678")).toFuture();
            var closed = tools.close().toFuture();
            clock.advanceTimeBy(PageMemoryTools.SAVE_TIMEOUT);
            assertThat(result.join()).containsEntry("error", "记忆保存结果待确认，本轮停止写入");
            assertThat(closed).isCompleted();
            assertThat(cancelled.get()).isFalse();
            stored.tryEmitValue(snapshot(2));
            assertThat(tools.reference()).contains("\"version\":1");
            assertThat(result.join()).containsKey("error");
            verify(service, times(1)).apply(any(), any(), any());
        } finally {
            reactor.test.scheduler.VirtualTimeScheduler.reset();
        }
    }

    @Test
    void uncertainStorageFailureDoesNotRetryOrClaimSuccess() {
        tools.prompt("0571-12345678");
        when(service.apply(eq(1L), any(), any())).thenReturn(Mono.error(new IllegalStateException("disk")));
        assertThat(tools.execute(input(1, "run/prompt", "0571-12345678")).block())
            .containsEntry("error", "记忆保存结果待确认，本轮停止写入");
        assertThat(tools.execute(input(1, "run/prompt", "0571-12345678")).block()).containsKey("error");
        verify(service, times(1)).apply(any(), any(), any());
    }

    static SiteMemory snapshot(long version) {
        var memory = new SiteMemory();
        memory.setMetadata(new Metadata());
        memory.getMetadata().setName("site-memory");
        memory.getMetadata().setVersion(version);
        memory.setSpec(new SiteMemory.Spec());
        memory.getSpec().setEntries(List.of());
        return memory;
    }

    static Map<String, Object> input(long version, String source, String quote) {
        return Map.of("expectedVersion", version, "operations", List.of(Map.of(
            "action", "add", "subject", "本站公司", "key", "电话", "kind", "fact",
            "value", "0571-12345678", "sourceId", source, "quote", quote)));
    }
}
