package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;
import run.halo.aipagebuilder.page.PageApi;
import run.halo.aipagebuilder.theme.ThemeStyleContext;

class PageRunTest {
    private static final String QUESTION_ID = "question-1";
    private static final String RUN_ID = UUID.randomUUID().toString();

    private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
    private final RecordingPersistence persistence = new RecordingPersistence();

    @Test
    void askAndAnswerMoveBetweenRunningAndWaitingForUser() {
        var run = run();
        var asking = run.ask(question()).subscribe();

        assertThat(run.state()).isEqualTo(PageRun.State.WAITING_FOR_USER);
        assertThat(run.turn().status()).isEqualTo("waiting_for_user");
        assertThat(persistence.calls).containsExactly("active", "checkpoint");
        assertThat(run.events.asFlux().next().block())
            .containsEntry("type", "data-user-question");

        var turn = run.answer(QUESTION_ID, answerRequest(), "admin").block();

        assertThat(turn).isNotNull();
        assertThat(turn.status()).isEqualTo("running");
        assertThat(turn.questions()).singleElement()
            .satisfies(question -> assertThat(question.answeredAt()).isNotNull());
        assertThat(run.state()).isEqualTo(PageRun.State.RUNNING);
        assertThat(run.events.asFlux().skip(1).next().block())
            .containsEntry("type", "data-user-answer");
        assertThat(persistence.calls).containsExactly("active", "checkpoint", "active", "checkpoint");
        asking.dispose();
    }

    @Test
    void answerRejectsAnotherActorAndRequirementsAreBounded() {
        var run = run();
        var asking = run.ask(question()).subscribe();

        assertThatThrownBy(() -> run.answer(QUESTION_ID, answerRequest(), "other").block())
            .isInstanceOfSatisfying(RunTransitionException.class, error ->
                assertThat(error.kind()).isEqualTo(RunTransitionException.Kind.FORBIDDEN));
        assertThat(run.state()).isEqualTo(PageRun.State.WAITING_FOR_USER);

        run.answer(QUESTION_ID, answerRequest(), "admin").block();

        for (var i = 0; i < PageRun.MAX_STEERS; i++) {
            run.steer("补充 " + i, "admin").block();
        }
        assertThatThrownBy(() -> run.steer("overflow", "admin").block())
            .isInstanceOfSatisfying(RunTransitionException.class, error ->
                assertThat(error.kind()).isEqualTo(RunTransitionException.Kind.CONFLICT));
        assertThat(run.turn().steers()).hasSize(PageRun.MAX_STEERS);
        asking.dispose();
    }

    @Test
    void committingOnlyReachesTerminalStates() {
        var run = run();
        run.markCommitting();

        assertThatThrownBy(() -> run.ask(question()).block())
            .isInstanceOfSatisfying(RunTransitionException.class, error ->
                assertThat(error.kind()).isEqualTo(RunTransitionException.Kind.CONFLICT));
        assertThatThrownBy(() -> run.steer("too late", "admin").block())
            .isInstanceOfSatisfying(RunTransitionException.class, error ->
                assertThat(error.kind()).isEqualTo(RunTransitionException.Kind.CONFLICT));

        run.complete("revision-2");

        assertThat(run.state()).isEqualTo(PageRun.State.COMPLETED);
        // A late failure must not reclassify a terminal run; finish() still retries persistence.
        run.fail("cannot fail a completed run");
        assertThat(run.state()).isEqualTo(PageRun.State.COMPLETED);
        assertThat(run.error()).isNull();
        assertThatThrownBy(run::markCommitting)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("COMPLETED -> COMMITTING");
    }

    @Test
    void terminalRunsIgnoreInterruptAndCancel() {
        var run = run();
        run.markCommitting();
        run.complete("revision-2");

        assertThat(run.interrupt("stop", null).block()).isNull();
        assertThat(run.cancel().block()).isNull();
        assertThatThrownBy(() -> run.answer(QUESTION_ID, answerRequest(), "admin").block())
            .isInstanceOfSatisfying(RunTransitionException.class, error ->
                assertThat(error.kind()).isEqualTo(RunTransitionException.Kind.CONFLICT));
        assertThat(run.state()).isEqualTo(PageRun.State.COMPLETED);
    }

    @Test
    void waitingTimesOutAfterDeadline() {
        var run = run();
        var asking = run.ask(question()).subscribe();

        scheduler.advanceTimeBy(PageRun.WAIT_TIMEOUT.minusSeconds(1));
        assertThat(run.state()).isEqualTo(PageRun.State.WAITING_FOR_USER);

        scheduler.advanceTimeBy(Duration.ofSeconds(1));

        assertThat(run.state()).isEqualTo(PageRun.State.INTERRUPTED);
        assertThat(run.error()).contains("24 小时");
        assertThat(run.cancel.asMono().block(Duration.ofSeconds(1))).isNull();
        asking.dispose();
    }

    @Test
    void answerCancelsWaitTimer() {
        var run = run();
        var asking = run.ask(question()).subscribe();

        scheduler.advanceTimeBy(PageRun.WAIT_TIMEOUT.minusSeconds(1));
        run.answer(QUESTION_ID, answerRequest(), "admin").block();
        scheduler.advanceTimeBy(Duration.ofSeconds(2));

        assertThat(run.state()).isEqualTo(PageRun.State.RUNNING);
        asking.dispose();
    }

    @Test
    void expiredTimerForAnAnsweredQuestionIsIgnored() {
        var run = run();
        var asking = run.ask(question()).subscribe();
        run.answer(QUESTION_ID, answerRequest(), "admin").block();

        // The timer may already be executing when the answer cancels it.
        assertThat(run.interrupt("expired", QUESTION_ID).block()).isNull();

        assertThat(run.state()).isEqualTo(PageRun.State.RUNNING);
        assertThat(run.error()).isNull();
        asking.dispose();
    }

    @Test
    void failedAnswerPersistenceRestoresWaitingAndRearmsTimer() {
        var run = run();
        var askingError = new AtomicReference<Throwable>();
        var asking = run.ask(question())
            .subscribe(ignored -> { }, askingError::set);

        persistence.activeFailure = new IllegalStateException("persist failed");

        assertThatThrownBy(() -> run.answer(QUESTION_ID, answerRequest(), "admin").block())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("persist failed");
        assertThat(run.state()).isEqualTo(PageRun.State.WAITING_FOR_USER);
        assertThat(askingError.get()).hasMessageContaining("persist failed");
        assertThat(run.turn().questions()).singleElement()
            .satisfies(question -> assertThat(question.answeredAt()).isNull());

        // answering was reset, so a retry reaches persistence instead of returning a conflict.
        assertThatThrownBy(() -> run.answer(QUESTION_ID, answerRequest(), "admin").block())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("persist failed");

        persistence.activeFailure = null;
        scheduler.advanceTimeBy(PageRun.WAIT_TIMEOUT.plusSeconds(1));

        assertThat(run.state()).isEqualTo(PageRun.State.INTERRUPTED);
        assertThat(run.error()).contains("24 小时");
        asking.dispose();
    }

    @Test
    void waitingCommandRetriesAfterPendingTransitionCompletes() {
        var run = run();
        var gate = Sinks.<Void>empty();
        persistence.activeGate = gate;

        run.steer("first", "admin").subscribe();
        var error = new AtomicReference<Throwable>();
        var second = new AtomicReference<ChatApi.Turn>();
        run.steer("second", "admin").subscribe(second::set, error::set);

        gate.tryEmitEmpty();

        assertThat(error.get()).isNull();
        assertThat(second.get()).isNotNull();
        assertThat(run.turn().steers()).containsExactly("first", "second");
    }

    @Test
    void checkpointStartsOnlyAfterActiveWriteCompletes() {
        var run = run();
        var gate = Sinks.<Void>empty();
        persistence.activeGate = gate;

        run.steer("steer", "admin").subscribe();

        assertThat(persistence.calls).containsExactly("active");
        gate.tryEmitEmpty();
        assertThat(persistence.calls).containsExactly("active", "checkpoint");
    }

    @Test
    void exclusiveBarrierReleasesWaitingCommandsBeforeNotifying() {
        var run = run();
        var gate = Sinks.<String>one();
        var renamed = new AtomicReference<String>();
        run.exclusive(gate.asMono()).subscribe(renamed::set);

        var error = new AtomicReference<Throwable>();
        var turn = new AtomicReference<ChatApi.Turn>();
        run.steer("after rename", "admin").subscribe(turn::set, error::set);

        gate.tryEmitValue("renamed");

        assertThat(error.get()).isNull();
        assertThat(renamed.get()).isEqualTo("renamed");
        assertThat(turn.get()).isNotNull();
        assertThat(run.turn().steers()).containsExactly("after rename");
    }

    @Test
    void cancelRetriesAfterWaitingOnAFailedCheckpointWrite() {
        var run = run();
        var gate = Sinks.<Void>empty();
        persistence.activeGate = gate;

        var steerError = new AtomicReference<Throwable>();
        run.steer("first", "admin").subscribe(ignored -> { }, steerError::set);
        var cancelError = new AtomicReference<Throwable>();
        run.cancel().subscribe(ignored -> { }, cancelError::set);

        persistence.checkpointFailure = new IllegalStateException("checkpoint failed");
        gate.tryEmitEmpty();

        assertThat(steerError.get()).hasMessageContaining("checkpoint failed");
        assertThat(cancelError.get()).isNull();
        assertThat(run.state()).isEqualTo(PageRun.State.CANCELLED);
    }

    @Test
    void failedAskPersistenceReleasesTransitionForCancel() {
        var run = run();
        persistence.checkpointFailure = new IllegalStateException("checkpoint failed");

        assertThatThrownBy(() -> run.ask(question()).block())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("checkpoint failed");

        // The barrier must not be left installed; otherwise cancellation would keep failing.
        persistence.checkpointFailure = null;
        assertThat(run.cancel().block()).isNull();

        assertThat(run.state()).isEqualTo(PageRun.State.CANCELLED);
        assertThat(run.cancel.asMono().block(Duration.ofSeconds(1))).isNull();
    }

    @Test
    void finishPersistsTerminalStateAndDeletesCheckpoint() {
        var run = run();
        run.markCommitting();
        run.complete("revision-2");

        run.finish(page("revision-2", 2L, "new")).block();

        assertThat(persistence.calls)
            .containsExactly("active", "deleteCheckpoint", "recordFinished");
        assertThat(run.terminalPersisted()).isTrue();
        assertThat(run.state()).isEqualTo(PageRun.State.COMPLETED);
        assertThat(run.events.asFlux().collectList().block())
            .extracting(event -> event.get("type"))
            .containsExactly("data-page-committed", "finish");
    }

    private PageRun run() {
        return new PageRun(request(), "admin", page("old-revision", 1L, "old"),
            new ContentPolicy(), Mono.just(ThemeStyleContext.standalone()), "session", persistence,
            PageRun.WAIT_TIMEOUT, () -> scheduler);
    }

    private static ChatApi.Request request() {
        return new ChatApi.Request(RUN_ID, 1L, "model", "修改页面", null, List.of());
    }

    private static ChatApi.Question question() {
        return new ChatApi.Question(QUESTION_ID,
            List.of(new ChatApi.QuestionItem(QUESTION_ID, "选择样式",
                List.of(new ChatApi.QuestionOption("a", "简洁"),
                    new ChatApi.QuestionOption("b", "丰富")), true)),
            List.of(), Instant.now(), null);
    }

    private static ChatApi.AnswerRequest answerRequest() {
        return new ChatApi.AnswerRequest(List.of(new ChatApi.AnswerItem(QUESTION_ID, "a", null)));
    }

    private static PageApi.PageDetail page(String revision, Long version, String text) {
        return new PageApi.PageDetail(
            new PageApi.PageSummary("page", "page", "Title", "", version, true, revision, null,
                Instant.now(), true),
            new PageDocument(1, "code", "", Map.of(), "<p>" + text + "</p>", "", "Title", ""));
    }

    private static final class RecordingPersistence implements RunPersistence {
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile RuntimeException activeFailure;
        volatile RuntimeException checkpointFailure;
        volatile Sinks.Empty<Void> activeGate;

        @Override
        public Mono<Void> active(PageRun run) {
            calls.add("active");
            if (activeFailure != null) return Mono.error(activeFailure);
            var gate = activeGate;
            return gate == null ? Mono.empty() : gate.asMono();
        }

        @Override
        public Mono<Void> checkpoint(PageRun run) {
            calls.add("checkpoint");
            return failure(checkpointFailure);
        }

        @Override
        public Mono<Void> recordFinished(PageRun run, ChatApi.Turn turn) {
            calls.add("recordFinished");
            return Mono.empty();
        }

        @Override
        public Mono<Void> deleteCheckpoint(PageRun run) {
            calls.add("deleteCheckpoint");
            return Mono.empty();
        }

        @Override
        public Mono<Void> checkpointAvailable(PageRun run) {
            calls.add("checkpointAvailable");
            return Mono.empty();
        }

        private static Mono<Void> failure(RuntimeException error) {
            return error == null ? Mono.empty() : Mono.error(error);
        }
    }
}
