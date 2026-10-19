package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import run.halo.aipagebuilder.Json;

class ApiReferenceToolsTest {
    private final PublicApiCatalog catalog = mock(PublicApiCatalog.class);
    private final ApiReferenceTools tools = new ApiReferenceTools(catalog);

    @Test
    void defaultsSearchAndReturnsEmptyResultsNormally() {
        var result = Map.<String, Object>of("items", List.of(), "totalMatches", 0, "truncated", false);
        when(catalog.search("", 8)).thenReturn(result);
        assertThat(tools.execute("search_public_apis", Map.of()).block())
            .containsAllEntriesOf(result).containsEntry("summary", "0 个接口");
        verify(catalog).search("", 8);
    }

    @Test
    void rejectsInvalidInputs() {
        for (var arguments : List.<Map<String, Object>>of(Map.of("query", "a".repeat(201)),
            Map.of("query", 2), Map.of("limit", 0), Map.of("limit", 21), Map.of("limit", 1.5),
            Map.of("limit", Double.NaN))) {
            assertThatThrownBy(() -> tools.execute("search_public_apis", arguments).block())
                .isInstanceOf(IllegalArgumentException.class);
        }
        for (var arguments : List.<Map<String, Object>>of(Map.of(), Map.of("ids", List.of()),
            Map.of("ids", List.of("a", "b", "c", "d")), Map.of("ids", List.of(1)),
            Map.of("ids", List.of(" ")))) {
            assertThatThrownBy(() -> tools.execute("get_public_api", arguments).block())
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void boundsUtf8SearchWithoutCuttingJson() {
        var large = Map.of("description", "中文".repeat(4000));
        when(catalog.search("posts", 20)).thenReturn(Map.of("items", List.of(large, large, large),
            "totalMatches", 3, "truncated", false));
        var result = tools.execute("search_public_apis", Map.of("query", "posts", "limit", 20)).block();
        assertThat(bytes(result)).isLessThanOrEqualTo(16 * 1024);
        assertThat(result).containsEntry("truncated", true).containsEntry("totalMatches", 3);
        assertThat((List<?>) result.get("items")).hasSize(0);
    }

    @Test
    void boundsBatchDetailsAndRetainsOtherItems() {
        var ids = List.of("GET /apis/api.example/v1/large", "GET /apis/api.example/v1/small");
        when(catalog.details(ids)).thenReturn(Map.of("items", List.of(
            Map.of("id", ids.get(0), "description", "中文".repeat(20000)),
            Map.of("id", ids.get(1), "status", "ok")), "partial", false));
        var result = tools.execute("get_public_api", Map.of("ids", ids)).block();
        assertThat(bytes(result)).isLessThanOrEqualTo(64 * 1024);
        assertThat(result).containsEntry("partial", true);
        var items = (List<?>) result.get("items");
        assertThat(((Map<?, ?>) items.getFirst()).get("status")).isEqualTo("unavailable");
        assertThat(((Map<?, ?>) items.getLast()).get("status")).isEqualTo("ok");
    }

    @Test
    void reportsAllFailedDetailsWithoutLosingIndividualResults() {
        var ids = List.of("GET /missing", "GET /unavailable", "GET /ambiguous");
        var items = List.of(
            PublicApiCatalog.failure(ids.get(0), "not_found", "未找到接口"),
            PublicApiCatalog.failure(ids.get(1), "unavailable", "契约不可用"),
            PublicApiCatalog.failure(ids.get(2), "ambiguous", "接口存在歧义"));
        when(catalog.details(ids)).thenReturn(Map.of("items", items, "partial", true));

        var result = tools.execute("get_public_api", Map.of("ids", ids)).block();

        assertThat(result).containsEntry("items", items).containsEntry("partial", true)
            .containsEntry("error", "已读取 0/3 项接口文档，3 项失败：未找到接口")
            .containsEntry("summary", "已读取 0/3 项接口文档，3 项失败：未找到接口");
    }

    @Test
    void summarizesPartialSuccessWithoutFailingTheWholeBatch() {
        var ids = List.of("GET /posts", "GET /missing");
        var items = List.of(Map.of("id", ids.get(0), "status", "ok", "responses", Map.of()),
            PublicApiCatalog.failure(ids.get(1), "not_found", "未找到接口"));
        when(catalog.details(ids)).thenReturn(Map.of("items", items, "partial", true));

        var result = tools.execute("get_public_api", Map.of("ids", ids)).block();

        assertThat(result).containsEntry("items", items).containsEntry("partial", true)
            .containsEntry("summary", "已读取 1/2 项接口文档，1 项失败：未找到接口")
            .doesNotContainKey("error");
    }

    @Test
    void summarizesReadableButIncompleteContractsWithoutReportingFailure() {
        var id = "GET /posts";
        var items = List.of(Map.of("id", id, "status", "ok", "partial", true,
            "routeConditions", Map.of("headers", List.of("X-Version=1"))));
        when(catalog.details(List.of(id))).thenReturn(Map.of("items", items, "partial", true));

        var result = tools.execute("get_public_api", Map.of("ids", List.of(id))).block();

        assertThat(result).containsEntry("items", items)
            .containsEntry("summary", "已读取 1/1 项接口文档（契约不完整）")
            .doesNotContainKey("error");
    }

    @Test
    void marksBudgetRejectedDetailsAsFailed() {
        var id = "GET /large";
        when(catalog.details(List.of(id))).thenReturn(Map.of("items", List.of(
            Map.of("id", id, "status", "ok", "description", "中文".repeat(20000))),
            "partial", false));

        var result = tools.execute("get_public_api", Map.of("ids", List.of(id))).block();

        assertThat(result).containsEntry("partial", true)
            .containsEntry("error", "已读取 0/1 项接口文档，1 项失败：接口契约超过本次返回预算，请缩小接口选择");
        assertThat(bytes(result)).isLessThanOrEqualTo(64 * 1024);
    }

    @Test
    void includesSummaryInDetailsBudget() {
        var id = "GET /near-limit";
        var item = new java.util.LinkedHashMap<String, Object>();
        item.put("id", id);
        item.put("status", "ok");
        item.put("description", "");
        var response = Map.<String, Object>of("items", List.of(item), "partial", false);
        item.put("description", "x".repeat(64 * 1024 - bytes(response)));
        assertThat(bytes(response)).isEqualTo(64 * 1024);
        when(catalog.details(List.of(id))).thenReturn(response);

        var result = tools.execute("get_public_api", Map.of("ids", List.of(id))).block();

        assertThat(bytes(result)).isLessThanOrEqualTo(64 * 1024);
        assertThat(result).containsEntry("partial", true)
            .containsEntry("error", "已读取 0/1 项接口文档，1 项失败：接口契约超过本次返回预算，请缩小接口选择");
    }

    @Test
    void keepsFinalBatchWithinBudgetWhenLaterDetailsFail() {
        var ids = List.of("GET /near-limit", "GET /second");
        var budgetReason = "接口契约超过本次返回预算，请缩小接口选择";
        for (var second : List.of(
            PublicApiCatalog.failure(ids.get(1), "not_found", "未找到接口".repeat(50)),
            Map.<String, Object>of("id", ids.get(1), "status", "ok",
                "description", "中文".repeat(20000)))) {
            var first = new java.util.LinkedHashMap<String, Object>();
            first.put("id", ids.get(0));
            first.put("status", "ok");
            first.put("description", "");
            var initial = Map.<String, Object>of("items", List.of(first,
                PublicApiCatalog.failure(ids.get(1), "unavailable", budgetReason)),
                "partial", false, "summary", "已读取 1/2 项接口文档，1 项失败：" + budgetReason);
            first.put("description", "x".repeat(64 * 1024 - bytes(initial)));
            when(catalog.details(ids)).thenReturn(Map.of("items", List.of(first, second),
                "partial", "not_found".equals(second.get("status"))));

            var result = tools.execute("get_public_api", Map.of("ids", ids)).block();

            assertThat(bytes(result)).isLessThanOrEqualTo(64 * 1024);
            assertThat(result).containsEntry("partial", true)
                .containsEntry("summary", "已读取 1/2 项接口文档，1 项失败：" + budgetReason)
                .doesNotContainKey("error");
            assertThat(((List<?>) result.get("items")).getFirst()).isEqualTo(first);
        }
    }

    @Test
    void boundsFailureReasonInSummaryButPreservesItemError() {
        var id = "GET /unavailable";
        var reason = "契约无法解析".repeat(100);
        var items = List.of(PublicApiCatalog.failure(id, "unavailable", reason));
        when(catalog.details(List.of(id))).thenReturn(Map.of("items", items, "partial", true));

        var result = tools.execute("get_public_api", Map.of("ids", List.of(id))).block();

        assertThat(result).containsEntry("items", items)
            .containsEntry("summary", "已读取 0/1 项接口文档，1 项失败：" + reason.substring(0, 200));
        assertThat((String) result.get("summary")).hasSizeLessThanOrEqualTo(300);
    }

    private static int bytes(Map<String, Object> result) {
        return Json.toJson(result).getBytes(StandardCharsets.UTF_8).length;
    }
}
