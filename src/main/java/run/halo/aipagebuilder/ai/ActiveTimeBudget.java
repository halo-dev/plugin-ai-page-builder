package run.halo.aipagebuilder.ai;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Deadlines consume only model/tool execution time, never time awaiting a user. */
final class ActiveTimeBudget {
    private final Set<Deadline> deadlines = new HashSet<>();
    private boolean paused;

    Mono<Void> deadline(Duration duration, AiGenerationException.Reason reason) {
        return Mono.create(sink -> {
            synchronized (this) {
                var deadline = new Deadline(sink, duration.toNanos(), reason);
                deadlines.add(deadline);
                sink.onDispose(() -> remove(deadline));
                if (!paused) deadline.schedule();
            }
        });
    }

    synchronized void pause() {
        if (paused) return;
        paused = true;
        deadlines.forEach(Deadline::pause);
    }

    synchronized void resume() {
        if (!paused) return;
        paused = false;
        deadlines.forEach(Deadline::schedule);
    }

    private synchronized void remove(Deadline deadline) {
        deadlines.remove(deadline);
        if (deadline.task != null) deadline.task.dispose();
    }

    private final class Deadline {
        private final MonoSink<Void> sink;
        private final AiGenerationException.Reason reason;
        private final Scheduler scheduler = Schedulers.parallel();
        private long remaining;
        private long started;
        private Disposable task;
        private long generation;

        Deadline(MonoSink<Void> sink, long remaining, AiGenerationException.Reason reason) {
            this.sink = sink;
            this.remaining = remaining;
            this.reason = reason;
        }

        void schedule() {
            started = scheduler.now(TimeUnit.NANOSECONDS);
            var expected = ++generation;
            task = scheduler.schedule(() -> {
                boolean expired;
                synchronized (ActiveTimeBudget.this) {
                    expired = !paused && expected == generation && deadlines.remove(this);
                }
                if (expired) sink.error(new AiGenerationException(reason));
            }, Math.max(0, remaining), TimeUnit.NANOSECONDS);
        }

        void pause() {
            generation++;
            if (task != null) task.dispose();
            remaining -= scheduler.now(TimeUnit.NANOSECONDS) - started;
        }
    }
}
