package run.halo.aipagebuilder.ai;

import reactor.core.publisher.Mono;

/** Persistence operations a run needs; implemented by {@link PageChatService}. */
interface RunPersistence {
    /** Persist the current turn as the session's active run. */
    Mono<Void> active(PageRun run);

    /** Save one checkpoint snapshot for the run's current state. */
    Mono<Void> checkpoint(PageRun run);

    /** Archive the finished turn, touch the session and clear its active run. */
    Mono<Void> recordFinished(PageRun run, ChatApi.Turn turn);

    Mono<Void> deleteCheckpoint(PageRun run);

    Mono<Void> checkpointAvailable(PageRun run);
}
