package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;

class ChatFailureReasonTest {
    @Test
    void classifiesTimeoutAndKnownErrorsWithoutExposingProviderMessages() {
        var copy = copy();
        assertThat(PageChatService.failureReason(new RuntimeException("private-response",
            new java.util.concurrent.TimeoutException()), copy)).isEqualTo(AiGenerationException.Reason.TIMEOUT);
        for (var reason : AiGenerationException.Reason.values()) {
            assertThat(PageChatService.failureReason(new AiGenerationException(reason), copy)).isEqualTo(reason);
        }
        assertThat(PageChatService.failureReason(new RuntimeException("private-response"), copy).message)
            .doesNotContain("private-response");
    }

    @Test
    void preservesDistinctStickyBudgetsEvenIfProviderWrapsTheirErrors() {
        var calls = copy();
        for (int i = 0; i < RunBudget.MAX_CALLS; i++) calls.read();
        assertThatThrownBy(calls::read).isInstanceOf(IllegalStateException.class);
        assertThat(PageChatService.failureReason(new RuntimeException(), calls))
            .isEqualTo(AiGenerationException.Reason.TOOL_LIMIT);
        var bytes = copy();
        assertThatThrownBy(() -> bytes.consume("a".repeat(RunBudget.MAX_BYTES + 1)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(bytes::read).isInstanceOf(IllegalStateException.class);
        assertThat(PageChatService.failureReason(new RuntimeException(), bytes))
            .isEqualTo(AiGenerationException.Reason.DATA_LIMIT);
    }

    @Test
    void reportedTokenLimitTakesPrecedenceOverToolAndDataLimitsInEitherOrder() {
        for (boolean tokensFirst : List.of(false, true)) {
            var calls = copy();
            var bytes = copy();
            if (tokensFirst) {
                calls.budget.usage(RunBudget.MAX_REPORTED_TOKENS, 0);
                bytes.budget.usage(RunBudget.MAX_REPORTED_TOKENS, 0);
            }
            for (int i = 0; i < RunBudget.MAX_CALLS; i++) calls.read();
            assertThatThrownBy(calls::read).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> bytes.consume("a".repeat(RunBudget.MAX_BYTES + 1)))
                .isInstanceOf(IllegalStateException.class);
            if (!tokensFirst) {
                calls.budget.usage(RunBudget.MAX_REPORTED_TOKENS, 0);
                bytes.budget.usage(RunBudget.MAX_REPORTED_TOKENS, 0);
            }
            var wrapped = new RuntimeException(new AiGenerationException(
                AiGenerationException.Reason.CHECKPOINT_FAILED));
            assertThat(PageChatService.failureReason(wrapped, calls))
                .isEqualTo(AiGenerationException.Reason.TOKEN_LIMIT);
            assertThat(PageChatService.failureReason(wrapped, bytes))
                .isEqualTo(AiGenerationException.Reason.TOKEN_LIMIT);
        }
    }

    private PageWorkingCopy copy() {
        return new PageWorkingCopy(new PageDocument(1, "code", null, null, "", "", "Test", ""),
            new ContentPolicy());
    }
}
