package run.halo.aipagebuilder.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.page.PageApi.PageDetail;

/**
 * One admitted run and the state machine that owns its transitions.
 *
 * <p>All state changes happen under the run's monitor. Persistence is supplied by
 * {@link RunPersistence}; admission and capacity stay in {@link PageRunCoordinator}.
 */
final class PageRun {
    static final Duration WAIT_TIMEOUT = Duration.ofHours(24);
    static final int MAX_STEERS = 10;

    enum State {
        RUNNING, WAITING_FOR_USER, COMMITTING, INTERRUPTED, CANCELLED, COMPLETED, FAILED, INCOMPLETE;

        String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Map<State, Set<State>> TRANSITIONS = Map.of(
        State.RUNNING, Set.of(State.WAITING_FOR_USER, State.COMMITTING, State.INTERRUPTED,
            State.CANCELLED, State.COMPLETED, State.FAILED, State.INCOMPLETE),
        State.WAITING_FOR_USER, Set.of(State.RUNNING, State.COMMITTING, State.INTERRUPTED,
            State.CANCELLED, State.FAILED, State.INCOMPLETE),
        State.COMMITTING, Set.of(State.COMPLETED, State.FAILED, State.INCOMPLETE),
        State.INTERRUPTED, Set.of(),
        State.CANCELLED, Set.of(),
        State.COMPLETED, Set.of(),
        State.FAILED, Set.of(),
        State.INCOMPLETE, Set.of());

    final ChatApi.Request request;
    final String actor;
    final String sessionId;
    final reactor.core.Disposable.Swap execution = reactor.core.Disposables.swap();
    final Sinks.Empty<Void> prepared = Sinks.empty();
    final Sinks.One<Flux<Map<String, Object>>> accepted = Sinks.one();
    final Sinks.Empty<Void> finished = Sinks.empty();
    final reactor.core.Disposable.Swap waiting = reactor.core.Disposables.swap();
    final Instant createdAt = Instant.now();
    final PageDetail base;
    final PageWorkingCopy copy;
    PageMemoryTools memory;
    final StringBuilder text = new StringBuilder();
    final ChatDisplay display = new ChatDisplay();
    final Sinks.Empty<Void> cancel = Sinks.empty();
    final Sinks.One<Throwable> failure = Sinks.one();
    final Sinks.Many<Map<String, Object>> events = Sinks.many().replay().limit(256);
    private final ArrayDeque<Map<String, Object>> pendingEvents = new ArrayDeque<>();
    private boolean emittingEvents;
    private boolean eventsClosed;
    final List<ChatApi.Question> questions = new ArrayList<>();
    final List<String> steers = new ArrayList<>();
    int continuations;
    private Sinks.One<ChatApi.Question> answer;
    private State state = State.RUNNING;
    private Mono<Void> transition;
    private Mono<Void> checkpointWrites = Mono.empty();
    private boolean answering;
    boolean reserving;
    private volatile boolean saveStarted;
    private volatile boolean terminalPersisted;
    private volatile String revision;
    private volatile String error;
    private final RunPersistence persistence;
    private final Duration waitTimeout;
    private final Supplier<Scheduler> scheduler;

    PageRun(ChatApi.Request request, String actor, PageDetail base, ContentPolicy policy,
        Mono<run.halo.aipagebuilder.theme.ThemeStyleContext> themeContext, String sessionId,
        RunPersistence persistence) {
        this(request, actor, base, policy, themeContext, sessionId, persistence, WAIT_TIMEOUT,
            Schedulers::parallel);
    }

    PageRun(ChatApi.Request request, String actor, PageDetail base, ContentPolicy policy,
        Mono<run.halo.aipagebuilder.theme.ThemeStyleContext> themeContext, String sessionId,
        RunPersistence persistence, Duration waitTimeout, Supplier<Scheduler> scheduler) {
        this.sessionId = sessionId;
        this.request = request;
        this.actor = actor;
        this.base = base;
        this.persistence = persistence;
        this.waitTimeout = waitTimeout;
        this.scheduler = scheduler;
        this.copy = new PageWorkingCopy(base.document(), policy, themeContext,
            base.page().slug(), base.page().publishedRevision() != null);
        this.copy.identify(base.page().name(), sessionId, request.runId(), request.modelName());
        this.copy.annotations(request.annotations() == null ? List.of() : request.annotations());
    }

    synchronized State state() {
        return state;
    }

    synchronized boolean is(State expected) {
        return state == expected;
    }

    synchronized String revision() {
        return revision;
    }

    synchronized String error() {
        return error;
    }

    synchronized boolean saveStarted() {
        return saveStarted;
    }

    synchronized boolean terminalPersisted() {
        return terminalPersisted;
    }

    /** Terminal persistence must not be replaced by a later error path. */
    synchronized void markSaveStarted() {
        saveStarted = true;
    }

    synchronized Mono<Void> settleTransition() {
        return transition == null ? Mono.empty() : transition.onErrorComplete();
    }

    synchronized Mono<Void> settleCheckpoints() {
        return checkpointWrites.onErrorComplete();
    }

    synchronized void markCommitting() {
        transitionTo(State.COMMITTING);
    }

    synchronized void complete(String revision) {
        transitionTo(State.COMPLETED);
        if (revision != null) this.revision = revision;
    }

    synchronized void fail(String message) {
        if (terminal()) return;
        transitionTo(State.FAILED);
        error = message;
    }

    synchronized void incomplete(String message) {
        if (terminal()) return;
        transitionTo(State.INCOMPLETE);
        error = message;
    }

    Mono<Void> persistActive() {
        return persistence.active(this);
    }

    /**
     * Queue one checkpoint write. Callers must already hold the run lock so the snapshot and
     * the queue position describe the same state.
     */
    synchronized Mono<Void> persistCheckpoint() {
        var save = persistence.checkpoint(this);
        checkpointWrites = checkpointWrites.then(save).doOnError(writeError -> {
            copy.markCheckpointFailed();
            failure.tryEmitValue(writeError);
        }).cache();
        return checkpointWrites;
    }

    /** Track a write that must not interleave with run transitions. */
    synchronized <T> Mono<T> exclusive(Mono<T> write) {
        var ref = new AtomicReference<Mono<Void>>();
        var update = write
            .doOnSuccess(ignored -> clearTransition(ref.get()))
            .doOnError(ignored -> clearTransition(ref.get()))
            .doFinally(signal -> clearTransition(ref.get()))
            .cache();
        var barrier = update.then().onErrorComplete();
        ref.set(barrier);
        transition = barrier;
        return update;
    }

    Mono<ChatApi.Question> ask(ChatApi.Question question) {
        return Mono.defer(() -> {
            synchronized (this) {
                if (state != State.RUNNING) {
                    return Mono.error(RunTransitionException.conflict("运行不接受新的问题"));
                }
                if (transition != null) {
                    // A steer or rename write may still own the session record.
                    return transition.onErrorComplete().then(Mono.defer(() -> ask(question)));
                }
                transitionTo(State.WAITING_FOR_USER);
                questions.add(question);
                answer = Sinks.one();
                copy.timeBudget.pause();
                var barrier = install(persistence.active(this)
                    .then(Mono.defer(this::persistCheckpoint)), () -> {
                    armWaitTimer(question.id());
                    emitEvent(Map.of("type", "data-user-question", "name", "user-question",
                        "id", question.id(), "data",
                        Map.of("runId", request.runId(), "question", question)));
                }, ignored -> { });
                return barrier.then(answer.asMono())
                    .doOnError(error -> failure.tryEmitValue(error));
            }
        });
    }

    Mono<ChatApi.Turn> answer(String questionId, ChatApi.AnswerRequest answerRequest, String actor) {
        return Mono.defer(() -> {
            synchronized (this) {
                if (!this.actor.equals(actor)) {
                    return Mono.error(RunTransitionException.forbidden("只能回答自己的运行"));
                }
                if (transition != null || answering || state != State.WAITING_FOR_USER
                    || questions.isEmpty()) {
                    return Mono.error(RunTransitionException.conflict("问题已回答或运行状态已变化"));
                }
                var pending = questions.getLast();
                if (!pending.id().equals(questionId) || pending.answeredAt() != null) {
                    return Mono.error(RunTransitionException.conflict("问题已过期或已回答"));
                }
                var answered = UserQuestions.answer(pending, answerRequest);
                answering = true;
                cancelWaitTimer();
                transitionTo(State.RUNNING);
                questions.set(questions.size() - 1, answered);
                var barrier = install(persistence.active(this)
                    .then(Mono.defer(this::persistCheckpoint)), () -> {
                    answering = false;
                    emitEvent(Map.of("type", "data-user-answer", "name", "user-answer",
                        "id", answered.id(), "data",
                        Map.of("runId", request.runId(), "question", answered)));
                    copy.timeBudget.resume();
                    if (memory != null) memory.answered(answered);
                    answer.tryEmitValue(answered);
                }, failure -> {
                    answering = false;
                    state = State.WAITING_FOR_USER;
                    questions.set(questions.size() - 1, pending);
                    armWaitTimer(pending.id());
                    answer.tryEmitError(failure);
                });
                return barrier.then(Mono.fromSupplier(this::turn));
            }
        });
    }

    /** Queues a mid-run instruction; injected at the next model step or a bounded continuation. */
    Mono<ChatApi.Turn> steer(String text, String actor) {
        return Mono.defer(() -> {
            synchronized (this) {
                if (transition != null) {
                    return transition.onErrorComplete()
                        .then(Mono.defer(() -> steer(text, actor)));
                }
                if (!this.actor.equals(actor)) {
                    return Mono.error(RunTransitionException.forbidden("只能补充自己的运行"));
                }
                if (state != State.RUNNING && state != State.WAITING_FOR_USER) {
                    return Mono.error(RunTransitionException.conflict("运行已结束，请作为新消息发送"));
                }
                if (steers.size() >= MAX_STEERS) {
                    return Mono.error(RunTransitionException.conflict(
                        "本轮补充指令已达上限，请等本轮结束后继续"));
                }
                steers.add(text);
                var index = steers.size() - 1;
                var barrier = install(persistence.active(this)
                    .then(Mono.defer(this::persistCheckpoint)), () -> {
                    if (memory != null) memory.steer(index, text);
                    copy.queueSteer(text);
                    emitEvent(Map.of("type", "data-steer", "name", "steer",
                        "id", request.runId() + "-steer-" + index, "data",
                        Map.of("runId", request.runId(), "index", index, "text", text)));
                }, ignored -> steers.remove(index));
                return barrier.then(Mono.fromSupplier(this::turn));
            }
        });
    }

    Mono<Void> interrupt(String message, String questionId) {
        return Mono.defer(() -> {
            synchronized (this) {
                if (transition != null) {
                    return transition.onErrorComplete()
                        .then(Mono.defer(() -> interrupt(message, questionId)));
                }
                // An expired timer may already be executing when an answer cancels it.
                if (questionId != null && (state != State.WAITING_FOR_USER || questions.isEmpty()
                    || !questionId.equals(questions.getLast().id()))) {
                    return Mono.empty();
                }
                if (state != State.RUNNING && state != State.WAITING_FOR_USER) {
                    return Mono.empty(); // In particular, never cancel an uncertain commit.
                }
                var previous = state;
                transitionTo(State.INTERRUPTED);
                error = message;
                return install(persistence.active(this), () -> {
                    cancelWaitTimer();
                    cancel.tryEmitEmpty();
                }, ignored -> {
                    state = previous;
                    error = null;
                });
            }
        });
    }

    Mono<Void> cancel() {
        return Mono.defer(() -> {
            synchronized (this) {
                if (transition != null) {
                    return transition.onErrorComplete().then(Mono.defer(this::cancel));
                }
                if (state != State.RUNNING && state != State.WAITING_FOR_USER) {
                    return copy.closeMemory();
                }
                var previous = state;
                transitionTo(State.CANCELLED);
                var barrier = install(persistence.active(this), () -> {
                    cancelWaitTimer();
                    cancel.tryEmitEmpty();
                }, ignored -> state = previous);
                return barrier.then(Mono.defer(copy::closeMemory));
            }
        });
    }

    Mono<Void> finish(PageDetail page) {
        return copy.closeMemory().then(copy.closeEdits())
            .then(Mono.defer(this::settleTransition))
            .then(Mono.defer(this::settleCheckpoints))
            .then(Mono.defer(() -> persistence.active(this)))
            .doOnSuccess(ignored -> terminalPersisted = true)
            .then(Mono.defer(() -> {
                if (is(State.COMPLETED) || is(State.CANCELLED)) {
                    return persistence.deleteCheckpoint(this);
                }
                var save = copy.checkpointFailed() ? Mono.<Void>empty()
                    : persistCheckpoint().onErrorComplete();
                return save.then(persistence.checkpointAvailable(this));
            }))
            .then(Mono.defer(() -> persistence.recordFinished(this, turn())))
            .doOnSuccess(ignored -> {
                org.slf4j.LoggerFactory.getLogger(PageRun.class).info(
                    "Page chat finished: {}, status={}, usage={}", copy.trace(), status(),
                    copy.usage());
                completeEvents(terminalEvents(page));
            }).then();
    }

    synchronized ChatApi.Turn turn() {
        return new ChatApi.Turn(request.runId(), actor, createdAt, request.prompt(), text.toString(),
            copy.summaries(), status(), revision, error, display.snapshot(status(), copy.memoryOutcomes()),
            request.annotations() == null ? List.of() : request.annotations().stream()
                .map(annotation -> new ChatApi.Annotation(annotation.id(), List.of(), annotation.tag(),
                    annotation.summary(), "", annotation.comment())).toList(), List.copyOf(questions),
            List.copyOf(steers)).withUsage(copy.usage());
    }

    private synchronized String status() {
        return state.wireName();
    }

    private void transitionTo(State target) {
        if (target == state) return;
        if (!TRANSITIONS.getOrDefault(state, Set.of()).contains(target)) {
            throw new IllegalStateException("Illegal run transition: " + state + " -> " + target);
        }
        state = target;
    }

    private boolean terminal() {
        return TRANSITIONS.getOrDefault(state, Set.of()).isEmpty();
    }

    /**
     * Install a write as the run's serialization barrier. Must be called while holding the
     * run lock; the identity check keeps a superseded write from clearing a newer barrier.
     *
     * <p>The barrier is cleared before its completion is propagated to waiters. Clearing it in
     * {@code doFinally} would be too late: a waiting command subscribes to the cached barrier
     * synchronously from its completion signal and would re-enter the same barrier forever.
     */
    private Mono<Void> install(Mono<Void> write, Runnable onSuccess,
        Consumer<Throwable> onError) {
        var ref = new AtomicReference<Mono<Void>>();
        var barrier = write.doOnSuccess(ignored -> {
            synchronized (this) {
                clearTransition(ref.get());
                onSuccess.run();
            }
        }).doOnError(writeError -> {
            synchronized (this) {
                clearTransition(ref.get());
                onError.accept(writeError);
            }
        }).doFinally(signal -> clearTransition(ref.get())).cache();
        ref.set(barrier);
        transition = barrier;
        return barrier;
    }

    private void clearTransition(Mono<Void> barrier) {
        synchronized (this) {
            if (transition == barrier) transition = null;
        }
    }

    private void armWaitTimer(String questionId) {
        waiting.update(Mono.delay(waitTimeout, scheduler.get())
            .then(Mono.defer(() -> interrupt(
                "等待回答已超过 24 小时，进度已保留，可继续生成。", questionId)))
            .subscribe(ignored -> { }, error -> failure.tryEmitValue(error)));
    }

    private void cancelWaitTimer() {
        waiting.update(null);
    }

    void disposeWaiting() {
        waiting.dispose();
    }

    private List<Map<String, Object>> terminalEvents(PageDetail page) {
        synchronized (this) {
            var terminalEvents = new ArrayList<Map<String, Object>>();
            if (state == State.COMPLETED) {
                if (revision != null && page != null) {
                    terminalEvents.add(Map.of("type", "data-page-committed",
                        "name", "page-committed", "id", request.runId(), "data", page));
                }
                terminalEvents.add(Map.of("type", "finish", "finishReason", "stop"));
            } else if (state == State.CANCELLED) {
                terminalEvents.add(Map.of("type", "abort"));
            } else {
                terminalEvents.add(Map.of("type", "error", "errorText", error));
            }
            return terminalEvents;
        }
    }

    boolean emitEvent(Map<String, Object> event) {
        synchronized (pendingEvents) {
            if (eventsClosed) return false;
            pendingEvents.add(event);
            if (emittingEvents) return true;
            emittingEvents = true;
        }
        drainEvents();
        return true;
    }

    void completeEvents(List<Map<String, Object>> terminalEvents) {
        synchronized (pendingEvents) {
            if (eventsClosed) return;
            pendingEvents.addAll(terminalEvents);
            eventsClosed = true;
            if (emittingEvents) return;
            emittingEvents = true;
        }
        drainEvents();
    }

    // One emitter drains outside the lock: shutdown can enqueue completion even while
    // a subscriber is handling an event, without losing the signal or blocking shutdown.
    private void drainEvents() {
        while (true) {
            Map<String, Object> event;
            boolean complete;
            synchronized (pendingEvents) {
                event = pendingEvents.poll();
                complete = eventsClosed;
                if (event == null) emittingEvents = false;
            }
            if (event == null) {
                if (complete) events.tryEmitComplete();
                return;
            }
            events.tryEmitNext(event);
        }
    }
}
