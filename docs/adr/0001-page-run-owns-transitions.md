# ADR 0001: PageRun owns run-state transitions

## Status

Accepted (2026-10-19)

## Context

`PageRun` was originally just a bag of mutable fields. The run status (`status`), the
serialization barrier for persistence writes (`transition`), the 24-hour question timer
(`waiting`), terminal events, and the failure signal were spread across more than a dozen
`synchronized (run)` blocks in `PageChatService`. Any new lifecycle path had to
simultaneously understand the write order of those fields, how to roll them back, and the
barrier cleanup rules; missing one detail would leave a permanent 409, a timeout that could
not be re-armed, or a race that cleared someone else's barrier.

## Decision

1. **`PageRun` owns the run-level state machine.**
   - State is an explicit `State` enum, while the persisted strings remain the original
     eight values: `running`, `waiting_for_user`, `committing`, `interrupted`, `cancelled`,
     `completed`, `failed`, `incomplete`. No externally visible state is added.
   - Legal transitions are constrained by an explicit transition table. `ask`, `answer`,
     `steer`, `interrupt`, `cancel`, and `finish` are guarded commands; callers can no
     longer mutate state directly.
   - Rolling back a transition after a persistence failure restores the previous
     state directly under the run monitor, rather than through the forward
     transition table.

2. **`PageRun` owns the serialization barrier and the wait timer.**
   - Installation, identity checking, and cleanup of the `transition` barrier live in
     `PageRun`. Only the installer can clear its own barrier.
   - `PageRun` arms and cancels the 24-hour question timeout; an expiry goes through
     `interrupt` and preserves the checkpoint.

3. **Persistence is injected as `RunPersistence` callbacks.**
   - The interface contains only the writes a run transition actually needs: `active`,
     `checkpoint`, `recordFinished`, `deleteCheckpoint`, and `checkpointAvailable`.
   - `PageChatService` still owns snapshot construction, storage calls, session recovery,
     and HTTP exception mapping.
   - `PageRun` throws domain exceptions; HTTP status codes are mapped only at the public
     boundary of `PageChatService`.

4. **Admission and capacity stay in `PageRunCoordinator`.**
   - One run per page, a global limit of four, the stopping state, the registry, the
     20-second shutdown deadline, and forced release do not enter `PageRun`. These are
     process-level admission concerns, not transitions of a single run.

5. **Do not merge `PageRun` and `PageRunCoordinator` in this change.**
   - Their lifecycles differ: the coordinator exists across runs, while a run is destroyed
     when its execution ends. Merging them would put the process-level registry into every
     run instance, breaking release timing and test isolation.

## Consequences

- `PageChatService` no longer reads or writes `run.status` directly and no longer manages
  `run.transition`.
- Error handling, rollback, and timers for run transitions have a single home, so a new
  path only needs to declare its target state.
- The external contract is unchanged: the eight persisted states, the SSE event order,
  409/429/503, the 24-hour timeout, the 20-second shutdown deadline, and recovery
  semantics.
- A known gap deliberately left unaddressed here: `cancel` has no actor check. That is an
  authorization-boundary issue and will be recorded separately.
