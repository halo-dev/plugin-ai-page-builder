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
    void retainsPublicApiToolNamesAndCompletionInHistory() {
        var display = new ChatDisplay();
        for (var name : java.util.List.of("search_public_apis", "get_public_api")) {
            display.accept(Map.of("type", "tool-input-start", "toolCallId", name,
                "toolName", name));
            assertThat(display.snapshot("running").getLast().status()).isEqualTo("running");
            display.accept(Map.of("type", "tool-output-available", "toolCallId", name,
                "output", Map.of("items", java.util.List.of(), "schema", "raw-contract-marker")));
        }
        var parts = display.snapshot("completed");
        assertThat(parts).extracting(ChatApi.DisplayPart::toolName)
            .containsExactly("search_public_apis", "get_public_api");
        assertThat(parts).allSatisfy(part -> {
            assertThat(part.status()).isEqualTo("completed");
            assertThat(part.summary()).isEqualTo("读取完成");
        });
        assertThat(JsonUtils.objectToJson(parts)).doesNotContain("raw-contract-marker");
    }

    @Test
    void retainsRenameSessionOutcomeInHistory() {
        var display = new ChatDisplay();
        display.accept(Map.of("type", "tool-input-start", "toolCallId", "n",
            "toolName", "rename_session"));
        display.accept(Map.of("type", "tool-output-available", "toolCallId", "n",
            "output", Map.of("valid", true, "renamed", true, "title", "公司介绍页")));
        var parts = display.snapshot("completed");
        assertThat(parts.getFirst().status()).isEqualTo("completed");
        assertThat(parts.getFirst().summary()).isEqualTo("已更新会话名称");
        assertThat(JsonUtils.objectToJson(parts)).doesNotContain("公司介绍页");
    }

    @Test
    void retainsFailedPublicApiDetailsAndSummaryInHistory() {
        var catalog = org.mockito.Mockito.mock(PublicApiCatalog.class);
        var ids = java.util.List.of("GET /missing", "GET /unavailable", "GET /ambiguous");
        var items = java.util.List.of(
            PublicApiCatalog.failure(ids.get(0), "not_found", "未找到接口"),
            PublicApiCatalog.failure(ids.get(1), "unavailable", "契约不可用"),
            PublicApiCatalog.failure(ids.get(2), "ambiguous", "接口存在歧义"));
        org.mockito.Mockito.when(catalog.details(ids))
            .thenReturn(Map.of("items", items, "partial", true));
        var output = new ApiReferenceTools(catalog)
            .execute("get_public_api", Map.of("ids", ids)).block();
        var display = new ChatDisplay();
        display.accept(Map.of("type", "tool-input-start", "toolCallId", "api",
            "toolName", "get_public_api"));
        display.accept(Map.of("type", "tool-output-available", "toolCallId", "api",
            "output", output));

        var parts = display.snapshot("completed");
        assertThat(parts.getFirst().status()).isEqualTo("error");
        assertThat(parts.getFirst().summary()).isEqualTo("已读取 0/3 项接口文档，3 项失败：未找到接口");
        assertThat(JsonUtils.objectToJson(parts)).doesNotContain("raw-contract-marker", "items");
    }

    @Test
    void retainsPartiallySuccessfulPublicApiSummaryInHistory() {
        var catalog = org.mockito.Mockito.mock(PublicApiCatalog.class);
        var ids = java.util.List.of("GET /posts", "GET /missing");
        org.mockito.Mockito.when(catalog.details(ids)).thenReturn(Map.of("items", java.util.List.of(
            Map.of("id", ids.get(0), "status", "ok", "responses", "raw-contract-marker"),
            PublicApiCatalog.failure(ids.get(1), "not_found", "未找到接口")), "partial", true));
        var output = new ApiReferenceTools(catalog)
            .execute("get_public_api", Map.of("ids", ids)).block();
        var display = new ChatDisplay();
        display.accept(Map.of("type", "tool-output-available", "toolCallId", "api",
            "toolName", "get_public_api", "output", output));

        var parts = display.snapshot("completed");
        assertThat(parts.getFirst().status()).isEqualTo("completed");
        assertThat(parts.getFirst().summary()).isEqualTo("已读取 1/2 项接口文档，1 项失败：未找到接口");
        assertThat(JsonUtils.objectToJson(parts)).doesNotContain("raw-contract-marker", "items");
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
    void retainsRejectedQuestionAsFailureSoHistoryPairsLikeTheLiveView() {
        var display = new ChatDisplay();
        display.accept(Map.of("type", "tool-input-start", "toolCallId", "bad", "toolName", "ask_user"));
        display.accept(Map.of("type", "tool-output-available", "toolCallId", "bad",
            "output", Map.of("error", "问题格式不正确")));
        display.accept(Map.of("type", "tool-input-start", "toolCallId", "q", "toolName", "ask_user"));
        display.accept(Map.of("type", "tool-output-available", "toolCallId", "q",
            "output", Map.of("questions", java.util.List.of())));
        var parts = display.snapshot("completed");
        assertThat(parts).extracting(ChatApi.DisplayPart::status)
            .containsExactly("error", "completed");
        assertThat(parts.getFirst().summary()).isEqualTo("问题格式不正确");
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
