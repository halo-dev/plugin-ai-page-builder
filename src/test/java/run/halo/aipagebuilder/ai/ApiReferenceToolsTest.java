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
import run.halo.app.infra.utils.JsonUtils;

class ApiReferenceToolsTest {
    private final PublicApiCatalog catalog = mock(PublicApiCatalog.class);
    private final ApiReferenceTools tools = new ApiReferenceTools(catalog);

    @Test
    void defaultsSearchAndReturnsEmptyResultsNormally() {
        var result = Map.<String, Object>of("items", List.of(), "totalMatches", 0, "truncated", false);
        when(catalog.search("", 8)).thenReturn(result);
        assertThat(tools.execute("search_public_apis", Map.of()).block()).isEqualTo(result);
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

    private static int bytes(Map<String, Object> result) {
        return JsonUtils.objectToJson(result).getBytes(StandardCharsets.UTF_8).length;
    }
}
