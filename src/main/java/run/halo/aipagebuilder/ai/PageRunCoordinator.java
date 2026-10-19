package run.halo.aipagebuilder.ai;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Single-process admission and lifecycle. Durable transitions belong to PageRun. */
final class PageRunCoordinator {
    static final int MAX_ACTIVE_RUNS = 4;
    static final Duration STOP_TIMEOUT = Duration.ofSeconds(20);
    private final Map<String, PageRun> active = new LinkedHashMap<>();
    private final String owner = UUID.randomUUID().toString();
    private boolean stopping;

    String owner() { return owner; }
    synchronized boolean stopping() { return stopping; }
    synchronized PageRun get(String name) { return active.get(name); }
    synchronized boolean contains(String name) { return active.containsKey(name); }

    synchronized void reserve(String name, PageRun run) {
        if (stopping) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "插件正在停止，请稍后重试");
        if (active.containsKey(name)) throw new ResponseStatusException(HttpStatus.CONFLICT, "页面已有活动运行");
        if (active.size() >= MAX_ACTIVE_RUNS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "同时运行的页面已达 4 个上限，请结束已有运行后重试");
        }
        active.put(name, run);
    }

    void execute(String name, PageRun run, Mono<Void> execution) {
        run.execution.update(execution.doFinally(signal -> release(name, run))
            .subscribe(ignored -> { }, error -> LoggerFactory.getLogger(getClass()).warn(
                "Page execution needs reconciliation: page={}, session={}, run={}, exception={}",
                name, run.sessionId, run.request.runId(), error.getClass().getName())));
    }

    void release(String name, PageRun run) {
        synchronized (this) {
            active.remove(name, run);
        }
        run.disposeWaiting();
        // Cancellation does not reach doOnError. Also settle HTTP/SSE on forced shutdown;
        // already accepted requests and normally completed streams keep their original result.
        run.accepted.tryEmitError(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "运行已停止，结果待确认，请查询运行状态"));
        run.completeEvents(List.of(Map.of("type", "error", "errorText", "运行结果待确认，请查询运行状态")));
        run.finished.tryEmitEmpty();
    }

    void stop(BiFunction<String, PageRun, Mono<Void>> interrupt) {
        Map<String, PageRun> snapshot;
        synchronized (this) {
            stopping = true;
            snapshot = Map.copyOf(active);
        }
        try {
            Flux.fromIterable(snapshot.entrySet()).flatMap(entry -> {
                var run = entry.getValue();
                return run.prepared.asMono()
                    .then(Mono.defer(() -> interrupt.apply(entry.getKey(), run)))
                    .then(run.finished.asMono()).onErrorResume(error -> Mono.empty());
            }).then().block(STOP_TIMEOUT);
        } catch (RuntimeException error) {
            LoggerFactory.getLogger(getClass()).warn(
                "Page execution shutdown deadline reached; persisted runs require reconciliation");
        } finally {
            snapshot.forEach((name, run) -> {
                run.execution.dispose();
                release(name, run);
            });
        }
    }
}
