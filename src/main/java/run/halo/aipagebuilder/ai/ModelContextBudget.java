package run.halo.aipagebuilder.ai;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import run.halo.aifoundation.message.ModelMessage;
import run.halo.aifoundation.tool.ToolDefinition;
import run.halo.aipagebuilder.Json;

/** Measures the whole provider-neutral input. Bytes are deliberately not labelled as tokens. */
final class ModelContextBudget {
    static int bytes(String system, List<ModelMessage> messages, List<ToolDefinition> tools) {
        var schemas = tools == null ? List.of() : tools.stream().map(tool -> {
            var schema = new LinkedHashMap<String, Object>();
            schema.put("name", tool.getName());
            schema.put("description", tool.getDescription());
            schema.put("inputSchema", tool.getInputSchema());
            return schema;
        }).toList();
        var input = new LinkedHashMap<String, Object>();
        input.put("system", system);
        input.put("messages", messages);
        input.put("tools", schemas);
        return Json.toJson(input).getBytes(StandardCharsets.UTF_8).length;
    }

    private ModelContextBudget() { }
}
