package run.halo.aipagebuilder.ai;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import run.halo.aifoundation.message.ModelMessage;
import run.halo.aifoundation.tool.ToolDefinition;
import static org.assertj.core.api.Assertions.*;

class RunResourceBudgetTest {
    @Test
    void repeatedLargePreviewsDoNotExhaustModelOrToolBudget() {
        var budget = new RunBudget();
        assertThat(budget.preview("x".repeat(RunBudget.MAX_BYTES + 1))).isFalse();
        assertThat(budget.snapshot("m", 0).previewBytes()).isZero();
        for (int i = 0; i < 20; i++) budget.preview("x".repeat(1024 * 1024));
        assertThat(budget.exhausted()).isFalse();
        assertThat(budget.snapshot("m", 0).toolBytes()).isZero();
        assertThat(budget.snapshot("m", 0).previewBytes()).isEqualTo(RunBudget.MAX_PREVIEW_BYTES);
        assertThat(budget.preview("more")).isFalse();
        budget.consume("tool result");
        assertThat(budget.snapshot("m", 0).toolBytes()).isPositive();
    }

    @Test
    void missingUsageRemainsUnknownAndReportedUsageSurvivesAcrossAttempts() {
        var budget = new RunBudget();
        budget.usage(null, null);
        assertThat(budget.snapshot("m", 0).inputTokens()).isNull();
        budget.usage(100, 0);
        budget.nextAttempt();
        budget.usage(200, 30);
        var usage = budget.snapshot("m", 0);
        assertThat(usage.inputTokens()).isEqualTo(300);
        assertThat(usage.outputTokens()).isEqualTo(30);
        assertThat(usage.missingUsageSteps()).isEqualTo(1);
        budget.usage(RunBudget.MAX_REPORTED_TOKENS, 0);
        assertThatThrownBy(budget::nextStep).isInstanceOfSatisfying(AiGenerationException.class,
            error -> assertThat(error.reason()).isEqualTo(AiGenerationException.Reason.TOKEN_LIMIT));
    }

    @Test
    void contextLimitIncludesSystemAndToolSchemasNotOnlyHistory() {
        var budget = new RunBudget();
        var tool = ToolDefinition.builder().name("read")
            .description("x".repeat(RunBudget.MAX_CONTEXT_BYTES / 2))
            .inputSchema(Map.of("type", "object")).build();
        assertThatThrownBy(() -> budget.requireContextFits(ModelContextBudget.bytes(
            "s".repeat(RunBudget.MAX_CONTEXT_BYTES / 2), List.of(ModelMessage.user("hello")),
            List.of(tool)))).isInstanceOfSatisfying(AiGenerationException.class,
                error -> assertThat(error.reason()).isEqualTo(AiGenerationException.Reason.CONTEXT_LIMIT));
        assertThat(budget.snapshot("m", 0).maxContextBytes())
            .isGreaterThan(RunBudget.MAX_CONTEXT_BYTES);
    }

    @Test
    void countsUtf8BytesAndKeepsPeakContextWithoutMakingItSticky() {
        var budget = new RunBudget();
        budget.consume("中");
        budget.consume(null);
        budget.preview("文");
        assertThat(budget.snapshot("m", 0).toolBytes()).isEqualTo(3);
        assertThat(budget.snapshot("m", 0).previewBytes()).isEqualTo(3);
        budget.requireContextFits(RunBudget.MAX_CONTEXT_BYTES);
        budget.requireContextFits(1);
        assertThat(budget.snapshot("m", 0).maxContextBytes())
            .isEqualTo(RunBudget.MAX_CONTEXT_BYTES);
        assertThatThrownBy(() -> budget.requireContextFits(RunBudget.MAX_CONTEXT_BYTES + 1))
            .isInstanceOfSatisfying(AiGenerationException.class,
                error -> assertThat(error.reason())
                    .isEqualTo(AiGenerationException.Reason.CONTEXT_LIMIT));
        budget.requireContextFits(1);
        assertThat(budget.snapshot("m", 0).maxContextBytes())
            .isEqualTo(RunBudget.MAX_CONTEXT_BYTES + 1);
        assertThat(budget.exhausted()).isFalse();
    }

    @Test
    void emptyContinuationRequestsDoNotSpendAllowanceOrCauseExhaustion() {
        var budget = new RunBudget();
        for (int i = 0; i < 3; i++) {
            for (int empty = 0; empty < 10; empty++) {
                assertThat(budget.tryContinuation(false)).isFalse();
            }
            assertThat(budget.tryContinuation(true)).isTrue();
        }
        assertThat(budget.tryContinuation(true)).isFalse();
        assertThat(budget.exhaustionReason()).isNull();
    }

    @Test
    void stickyExhaustionBlocksRecoveryAndContinuation() {
        var calls = new RunBudget();
        for (int i = 0; i < RunBudget.MAX_CALLS; i++) calls.call();
        assertThatThrownBy(calls::call).isInstanceOf(IllegalStateException.class);
        var bytes = new RunBudget();
        assertThatThrownBy(() -> bytes.consume("x".repeat(RunBudget.MAX_BYTES + 1)))
            .isInstanceOf(IllegalStateException.class);
        var tokens = new RunBudget();
        tokens.usage(RunBudget.MAX_REPORTED_TOKENS, 0);
        for (var budget : List.of(calls, bytes, tokens)) {
            assertThat(budget.recover()).isFalse();
            assertThat(budget.tryContinuation(true)).isFalse();
        }
    }

    @Test
    void stepLimitBlocksRecoveryButIsNotStickyExhaustion() {
        var budget = new RunBudget();
        for (int i = 0; i < RunBudget.MAX_STEPS; i++) budget.nextStep();
        assertThat(budget.recover()).isFalse();
        assertThat(budget.exhausted()).isFalse();
        assertThat(budget.tryContinuation(true)).isTrue();
        budget.usage(RunBudget.MAX_REPORTED_TOKENS, 0);
        assertThatThrownBy(budget::nextStep).isInstanceOfSatisfying(AiGenerationException.class,
            error -> assertThat(error.reason())
                .isEqualTo(AiGenerationException.Reason.TOKEN_LIMIT));
    }
}
