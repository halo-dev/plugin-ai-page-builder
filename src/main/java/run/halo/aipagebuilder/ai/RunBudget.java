package run.halo.aipagebuilder.ai;

import java.nio.charset.StandardCharsets;

/** Shared by all model invocations, truncation recovery and steers in one run. */
final class RunBudget {
    static final int MAX_STEPS = 20;
    static final int MAX_RECOVERIES = 2;
    static final int MAX_CONTINUATIONS = 3;
    static final int MAX_CALLS = 40;
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_PREVIEW_BYTES = 16 * 1024 * 1024;
    static final int MAX_CONTEXT_BYTES = 256 * 1024;
    static final long MAX_REPORTED_TOKENS = 1_000_000;
    private int steps;
    private int recoveries;
    private int continuations;
    private int attempts;
    private int calls;
    private int bytes;
    private long previewBytes;
    private int contextBytes;
    private AiGenerationException.Reason exhaustedReason;
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
    synchronized int calls() { return calls; }
    synchronized int nextAttempt() { return ++attempts; }

    synchronized void call() {
        if (++calls > MAX_CALLS || exhaustedReason != null) {
            if (exhaustedReason == null) exhaustedReason = AiGenerationException.Reason.TOOL_LIMIT;
            throw new IllegalStateException("工具调用预算已耗尽");
        }
    }

    /** Counts both model text and tool input/output, despite the toolBytes field name. */
    synchronized void consume(String value) {
        bytes += value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) {
            if (exhaustedReason == null) exhaustedReason = AiGenerationException.Reason.DATA_LIMIT;
            throw new IllegalStateException("本轮输出预算已耗尽");
        }
    }

    /** Dropping a disposable preview must not fail an otherwise valid edit. */
    synchronized boolean preview(String value) {
        var size = value.getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_BYTES || previewBytes + size > MAX_PREVIEW_BYTES) return false;
        previewBytes += size;
        return true;
    }

    synchronized void requireContextFits(int value) {
        contextBytes = Math.max(contextBytes, value);
        if (value > MAX_CONTEXT_BYTES) {
            throw new AiGenerationException(AiGenerationException.Reason.CONTEXT_LIMIT);
        }
    }

    synchronized boolean exhausted() { return exhaustionReason() != null; }

    synchronized AiGenerationException.Reason exhaustionReason() {
        return tokenLimitReached() ? AiGenerationException.Reason.TOKEN_LIMIT : exhaustedReason;
    }

    synchronized void usage(Number input, Number output) {
        if (input == null || output == null) missingUsageSteps++;
        else reportedSteps++;
        if (input != null) { inputReports++; inputTokens += Math.max(0, input.longValue()); }
        if (output != null) { outputReports++; outputTokens += Math.max(0, output.longValue()); }
    }

    private boolean tokenLimitReached() {
        return inputTokens + outputTokens >= MAX_REPORTED_TOKENS;
    }

    synchronized ChatApi.Usage snapshot(String model, long elapsedMillis) {
        return new ChatApi.Usage(model, "page-agent-v1", steps, reportedSteps, missingUsageSteps,
            inputReports == 0 ? null : inputTokens,
            outputReports == 0 ? null : outputTokens,
            elapsedMillis, bytes, previewBytes, contextBytes);
    }

    synchronized boolean recover() {
        if (exhausted() || !hasSteps() || recoveries >= MAX_RECOVERIES) return false;
        recoveries++;
        return true;
    }

    /** Only an actual leftover instruction consumes a continuation; refusal still allows commit. */
    synchronized boolean tryContinuation(boolean hasSteers) {
        if (!hasSteers || continuations >= MAX_CONTINUATIONS || exhausted()) return false;
        continuations++;
        return true;
    }
}
