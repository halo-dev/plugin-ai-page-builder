package run.halo.aipagebuilder.ai;

/** Shared by all model invocations, truncation recovery and steers in one run. */
final class RunBudget {
    static final int MAX_STEPS = 20;
    static final int MAX_RECOVERIES = 2;
    static final long MAX_REPORTED_TOKENS = 1_000_000;
    private int steps;
    private int recoveries;
    private int attempts;
    private long inputTokens;
    private long outputTokens;
    private int reportedSteps;
    private int missingUsageSteps;
    private int inputReports;
    private int outputReports;

    synchronized int nextStep() {
        if (tokenLimitReached()) throw new AiGenerationException(AiGenerationException.Reason.TOKEN_LIMIT);
        if (steps >= MAX_STEPS) throw new AiGenerationException(AiGenerationException.Reason.STEP_LIMIT);
        return ++steps;
    }

    synchronized boolean hasSteps() { return steps < MAX_STEPS && !tokenLimitReached(); }
    synchronized int steps() { return steps; }
    synchronized int recoveries() { return recoveries; }
    synchronized int nextAttempt() { return ++attempts; }

    synchronized void usage(Number input, Number output) {
        if (input == null || output == null) missingUsageSteps++;
        else reportedSteps++;
        if (input != null) { inputReports++; inputTokens += Math.max(0, input.longValue()); }
        if (output != null) { outputReports++; outputTokens += Math.max(0, output.longValue()); }
    }

    synchronized boolean tokenLimitReached() {
        return inputTokens + outputTokens >= MAX_REPORTED_TOKENS;
    }

    synchronized ChatApi.Usage snapshot(String model, long elapsedMillis, long toolBytes,
        long previewBytes, int contextBytes) {
        return new ChatApi.Usage(model, "page-agent-v1", steps, reportedSteps, missingUsageSteps,
            inputReports == 0 ? null : inputTokens,
            outputReports == 0 ? null : outputTokens,
            elapsedMillis, toolBytes, previewBytes, contextBytes);
    }

    synchronized boolean recover() {
        if (!hasSteps() || recoveries >= MAX_RECOVERIES) return false;
        recoveries++;
        return true;
    }
}
