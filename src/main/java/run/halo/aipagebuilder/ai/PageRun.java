package run.halo.aipagebuilder.ai;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.page.PageApi.PageDetail;

/** Mutable execution state, owned by one admitted run in this plugin process. */
final class PageRun {
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
    Sinks.One<ChatApi.Question> answer;
    Mono<Void> transition;
    Mono<Void> checkpointWrites = Mono.empty();
    boolean answering;
    boolean reserving;
    volatile boolean saveStarted;
    volatile boolean terminalPersisted;
    volatile String status = "running";
    volatile String revision;
    volatile String error;

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

    PageRun(ChatApi.Request request, String actor, PageDetail base, ContentPolicy policy,
        Mono<run.halo.aipagebuilder.theme.ThemeStyleContext> themeContext, String sessionId) {
        this.sessionId = sessionId;
        this.request = request;
        this.actor = actor;
        this.base = base;
        this.copy = new PageWorkingCopy(base.document(), policy, themeContext,
            base.page().slug(), base.page().publishedRevision() != null);
        this.copy.identify(base.page().name(), sessionId, request.runId(), request.modelName());
        this.copy.annotations(request.annotations() == null ? List.of() : request.annotations());
    }

    synchronized ChatApi.Turn turn() {
        return new ChatApi.Turn(request.runId(), actor, createdAt, request.prompt(), text.toString(),
            copy.summaries(), status, revision, error, display.snapshot(status, copy.memoryOutcomes()),
            request.annotations() == null ? List.of() : request.annotations().stream()
                .map(annotation -> new ChatApi.Annotation(annotation.id(), List.of(), annotation.tag(),
                    annotation.summary(), "", annotation.comment())).toList(), List.copyOf(questions),
            List.copyOf(steers)).withUsage(copy.usage());
    }
}
