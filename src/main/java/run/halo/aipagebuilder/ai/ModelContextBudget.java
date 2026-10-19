package run.halo.aipagebuilder.ai;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import run.halo.aifoundation.message.ModelMessage;
import run.halo.aifoundation.tool.ToolDefinition;
import run.halo.aipagebuilder.Json;

/** Bounds the whole provider-neutral input. Bytes are deliberately not labelled as tokens. */
final class ModelContextBudget {
    static final int MAX_BYTES = 256 * 1024;

    static void requireFits(String system, List<ModelMessage> messages,
        List<ToolDefinition> tools, PageWorkingCopy copy) {
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
        int bytes = Json.toJson(input).getBytes(StandardCharsets.UTF_8).length;
        copy.contextBytes(bytes);
        if (bytes > MAX_BYTES) throw new AiGenerationException(AiGenerationException.Reason.CONTEXT_LIMIT);
    }

    private ModelContextBudget() { }
}
