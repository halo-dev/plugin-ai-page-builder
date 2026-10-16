package run.halo.aipagebuilder.ai;

/** Shared by all model invocations, truncation recovery and steers in one run. */
final class RunBudget {
    static final int MAX_STEPS = 20;
    static final int MAX_RECOVERIES = 2;
    private int steps;
    private int recoveries;
    private int attempts;

    synchronized int nextStep() {
        if (steps >= MAX_STEPS) throw new AiGenerationException(AiGenerationException.Reason.STEP_LIMIT);
        return ++steps;
    }

    synchronized boolean hasSteps() { return steps < MAX_STEPS; }
    synchronized int steps() { return steps; }
    synchronized int recoveries() { return recoveries; }
    synchronized int nextAttempt() { return ++attempts; }

    synchronized boolean recover() {
        if (!hasSteps() || recoveries >= MAX_RECOVERIES) return false;
        recoveries++;
        return true;
    }
}
