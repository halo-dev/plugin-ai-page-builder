package run.halo.aipagebuilder.ai;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import run.halo.aifoundation.message.ModelMessage;
import run.halo.aifoundation.tool.ToolDefinition;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;
import static org.assertj.core.api.Assertions.*;

class RunResourceBudgetTest {
    private PageWorkingCopy copy() {
        return new PageWorkingCopy(new PageDocument(1, "code", null, null, "", "", "Title", ""),
            new ContentPolicy(), reactor.core.publisher.Mono.empty());
    }

    @Test
    void repeatedLargePreviewsDoNotExhaustModelOrToolBudget() {
        var copy = copy();
        for (int i = 0; i < 20; i++) copy.preview("x".repeat(1024 * 1024));
        assertThat(copy.exhausted()).isFalse();
        assertThat(copy.usage().toolBytes()).isZero();
        assertThat(copy.usage().previewBytes()).isEqualTo(PageWorkingCopy.MAX_PREVIEW_BYTES);
        assertThat(copy.preview("more")).isFalse();
        copy.consume("tool result");
        assertThat(copy.usage().toolBytes()).isPositive();
    }

    @Test
    void missingUsageRemainsUnknownAndReportedUsageSurvivesAcrossAttempts() {
        var budget = new RunBudget();
        budget.usage(null, null);
        assertThat(budget.snapshot("m", 0, 0, 0, 0).inputTokens()).isNull();
        budget.usage(100, 0);
        budget.nextAttempt();
        budget.usage(200, 30);
        var usage = budget.snapshot("m", 0, 0, 0, 0);
        assertThat(usage.inputTokens()).isEqualTo(300);
        assertThat(usage.outputTokens()).isEqualTo(30);
        assertThat(usage.missingUsageSteps()).isEqualTo(1);
        budget.usage(RunBudget.MAX_REPORTED_TOKENS, 0);
        assertThatThrownBy(budget::nextStep).isInstanceOfSatisfying(AiGenerationException.class,
            error -> assertThat(error.reason()).isEqualTo(AiGenerationException.Reason.TOKEN_LIMIT));
    }

    @Test
    void contextLimitIncludesSystemAndToolSchemasNotOnlyHistory() {
        var copy = copy();
        var tool = ToolDefinition.builder().name("read")
            .description("x".repeat(ModelContextBudget.MAX_BYTES / 2))
            .inputSchema(Map.of("type", "object")).build();
        assertThatThrownBy(() -> ModelContextBudget.requireFits(
            "s".repeat(ModelContextBudget.MAX_BYTES / 2), List.of(ModelMessage.user("hello")),
            List.of(tool), copy)).isInstanceOfSatisfying(AiGenerationException.class,
                error -> assertThat(error.reason()).isEqualTo(AiGenerationException.Reason.CONTEXT_LIMIT));
        assertThat(copy.usage().maxContextBytes()).isGreaterThan(ModelContextBudget.MAX_BYTES);
    }
}
