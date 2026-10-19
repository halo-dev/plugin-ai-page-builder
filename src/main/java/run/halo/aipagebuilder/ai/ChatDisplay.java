package run.halo.aipagebuilder.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded display-only history. Raw tool inputs and provider metadata are never retained. */
final class ChatDisplay {
    private static final int MAX_PARTS = 96;
    private final Map<String, ChatApi.DisplayPart> parts = new LinkedHashMap<>();
    private int reasoningLength;

    synchronized void accept(Map<String, Object> chunk) {
        var type = string(chunk, "type");
        if (type.startsWith("text-") || type.startsWith("reasoning-")) {
            var kind = type.startsWith("text-") ? "text" : "reasoning";
            var id = string(chunk, "id");
            if (id.isEmpty()) return;
            var key = kind + ":" + id;
            var old = parts.get(key);
            if (old == null && parts.size() >= MAX_PARTS) return;
            var text = old == null ? "" : old.text();
            boolean truncated = old != null && Boolean.TRUE.equals(old.truncated());
            if (type.endsWith("-delta")) {
                var delta = string(chunk, "delta");
                int limit = "reasoning".equals(kind) ? Math.max(0, 16_000 - reasoningLength) : 16_000 - text.length();
                var retained = delta.substring(0, Math.min(limit, delta.length()));
                if ("reasoning".equals(kind)) reasoningLength += retained.length();
                text += retained;
                truncated |= retained.length() < delta.length();
            }
            parts.put(key, new ChatApi.DisplayPart(kind, id, text, null, null, null, truncated));
        } else if (type.startsWith("tool-")) {
            var id = string(chunk, "toolCallId");
            if (id.isEmpty()) return;
            var key = "tool:" + id;
            var old = parts.get(key);
            if (old == null && parts.size() >= MAX_PARTS) return;
            var name = string(chunk, "toolName");
            if (name.isEmpty() && old != null) name = old.toolName();
            if (name.isEmpty()) return;
            var status = old == null ? "running" : old.status();
            var summary = old == null ? "" : old.summary();
            if ("tool-output-available".equals(type)) {
                var output = chunk.get("output") instanceof Map<?, ?> value ? value : Map.of();
                boolean memory = PageMemoryTools.NAME.equals(name);
                boolean apiDetails = "get_public_api".equals(name);
                // A rejected ask_user returns {error: ...} without recording a question;
                // persist the failure so restored history pairs questions like the live view.
                boolean askUser = "ask_user".equals(name);
                boolean invalid = Boolean.FALSE.equals(output.get("valid"))
                    || (memory || apiDetails || askUser) && output.containsKey("error");
                status = invalid ? "error" : "completed";
                summary = (memory || apiDetails)
                    && output.get("summary") instanceof String message ? message
                    : invalid ? String.valueOf(output.getOrDefault("error", null))
                    : "rename_session".equals(name) ? "已更新会话名称"
                    : Boolean.TRUE.equals(output.get("staged")) ? "修改已通过校验并暂存"
                    : Boolean.TRUE.equals(output.get("valid")) ? "内容未发生变化" : "读取完成";
            } else if (type.endsWith("-error") || type.endsWith("-denied")) {
                status = "error";
                summary = "工具未完成";
            }
            parts.put(key, new ChatApi.DisplayPart("tool", id, null, name, status,
                summary.substring(0, Math.min(300, summary.length())), false));
        }
    }

    synchronized String toolName(String id) {
        var part = parts.get("tool:" + id);
        return part == null ? "" : part.toolName();
    }

    synchronized List<ChatApi.DisplayPart> snapshot(String status) {
        return snapshot(status, Map.of());
    }

    synchronized List<ChatApi.DisplayPart> snapshot(String status,
        Map<String, ChatApi.Tool> memoryOutcomes) {
        var result = new ArrayList<ChatApi.DisplayPart>();
        for (var part : parts.values()) {
            var outcome = memoryOutcomes.get(part.id());
            if ("tool".equals(part.type()) && PageMemoryTools.NAME.equals(part.toolName())
                && outcome != null) {
                result.add(new ChatApi.DisplayPart(part.type(), part.id(), null, part.toolName(),
                    outcome.status(), outcome.summary(), false));
            } else if ("tool".equals(part.type()) && "running".equals(part.status())
                && !"running".equals(status) && !"waiting_for_user".equals(status)) {
                result.add(new ChatApi.DisplayPart(part.type(), part.id(), null, part.toolName(),
                    "error", "本轮结束，工具未完成", false));
            } else {
                result.add(part);
            }
        }
        return List.copyOf(result);
    }

    private static String string(Map<String, Object> value, String name) {
        return value.get(name) instanceof String text ? text : "";
    }
}
