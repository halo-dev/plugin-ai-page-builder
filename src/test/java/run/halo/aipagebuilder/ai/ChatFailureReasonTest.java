package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        for (int i = 0; i < PageWorkingCopy.MAX_CALLS; i++) calls.read();
        assertThatThrownBy(calls::read).isInstanceOf(IllegalStateException.class);
        assertThat(PageChatService.failureReason(new RuntimeException(), calls))
            .isEqualTo(AiGenerationException.Reason.TOOL_LIMIT);
        var bytes = copy();
        assertThatThrownBy(() -> bytes.consume("a".repeat(PageWorkingCopy.MAX_BYTES + 1)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(bytes::read).isInstanceOf(IllegalStateException.class);
        assertThat(PageChatService.failureReason(new RuntimeException(), bytes))
            .isEqualTo(AiGenerationException.Reason.DATA_LIMIT);
    }

    private PageWorkingCopy copy() {
        return new PageWorkingCopy(new PageDocument(1, "code", null, null, "", "", "Test", ""),
            new ContentPolicy());
    }
}
