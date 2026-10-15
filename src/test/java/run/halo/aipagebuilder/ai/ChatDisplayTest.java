package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import run.halo.app.infra.utils.JsonUtils;

class ChatDisplayTest {
    @Test
    void retainsOrderAndSummariesWithoutRawInputsOrProviderMetadata() {
        var display = new ChatDisplay();
        display.accept(Map.of("type", "text-delta", "id", "a", "delta", "先看页面"));
        display.accept(Map.of("type", "reasoning-delta", "id", "r", "delta", "模型返回的说明",
            "providerMetadata", Map.of("signature", "private-signature")));
        display.accept(Map.of("type", "tool-input-start", "toolCallId", "t", "toolName", "patch_current_page"));
        display.accept(Map.of("type", "tool-input-delta", "toolCallId", "t", "inputTextDelta", "raw-html-marker"));
        display.accept(Map.of("type", "tool-output-available", "toolCallId", "t", "output",
            Map.of("valid", true, "staged", true, "html", "raw-html-marker")));
        display.accept(Map.of("type", "text-delta", "id", "b", "delta", "完成"));
        var parts = display.snapshot("completed");
        assertThat(parts).extracting(ChatApi.DisplayPart::type).containsExactly("text", "reasoning", "tool", "text");
        assertThat(parts.get(2).status()).isEqualTo("completed");
        assertThat(JsonUtils.objectToJson(parts)).doesNotContain("raw-html-marker", "private-signature");
    }

    @Test
    void retainsMemoryOutcomeWithoutConfusingItWithPageStaging() {
        var display = new ChatDisplay();
        display.accept(Map.of("type", "tool-input-start", "toolCallId", "m",
            "toolName", "update_site_memory"));
        display.accept(Map.of("type", "tool-output-available", "toolCallId", "m", "output",
            Map.of("summary", "已记住公司电话", "entries", "private-source")));
        assertThat(display.snapshot("failed").getFirst().summary()).isEqualTo("已记住公司电话");
        assertThat(display.snapshot("failed").getFirst().status()).isEqualTo("completed");
        display.accept(Map.of("type", "tool-output-available", "toolCallId", "m", "output",
            Map.of("summary", "保存结果待确认", "error", "保存结果待确认")));
        assertThat(display.snapshot("completed").getFirst().status()).isEqualTo("error");
        assertThat(JsonUtils.objectToJson(display.snapshot("completed"))).doesNotContain("private-source");
    }

    @Test
    void reconcilesEachMemoryCallWithItsOwnStoredOutcome() {
        var display = new ChatDisplay();
        for (var id : java.util.List.of("saved", "unknown", "pending")) {
            display.accept(Map.of("type", "tool-input-start", "toolCallId", id,
                "toolName", PageMemoryTools.NAME));
        }
        display.accept(Map.of("type", "tool-output-error", "toolCallId", "saved"));
        var parts = display.snapshot("cancelled", Map.of(
            "saved", new ChatApi.Tool(PageMemoryTools.NAME, "completed", "已记住公司电话"),
            "unknown", new ChatApi.Tool(PageMemoryTools.NAME, "error", "记忆保存结果待确认")));
        assertThat(parts).extracting(ChatApi.DisplayPart::id).containsExactly("saved", "unknown", "pending");
        assertThat(parts).extracting(ChatApi.DisplayPart::status).containsExactly("completed", "error", "error");
        assertThat(parts).extracting(ChatApi.DisplayPart::summary)
            .containsExactly("已记住公司电话", "记忆保存结果待确认", "本轮结束，工具未完成");
    }

    @Test
    void preservesPendingToolsWhileWaitingForAnAnswer() {
        var display = new ChatDisplay();
        display.accept(Map.of("type", "tool-input-start", "toolCallId", "q", "toolName", "ask_user"));
        assertThat(display.snapshot("waiting_for_user").getFirst().status()).isEqualTo("running");
        assertThat(display.snapshot("cancelled").getFirst().status()).isEqualTo("error");
    }

    @Test
    void boundsReasoningAndFinishesInterruptedTools() {
        var display = new ChatDisplay();
        display.accept(Map.of("type", "reasoning-delta", "id", "r", "delta", "思".repeat(20_000)));
        display.accept(Map.of("type", "reasoning-delta", "id", "r2", "delta", "more"));
        display.accept(Map.of("type", "tool-input-start", "toolCallId", "t", "toolName", "update_current_page"));
        var parts = display.snapshot("cancelled");
        assertThat(parts.getFirst().text()).hasSize(16_000);
        assertThat(parts.getFirst().truncated()).isTrue();
        assertThat(parts.get(1).text()).isEmpty();
        assertThat(parts.get(2).status()).isEqualTo("error");
    }
}
