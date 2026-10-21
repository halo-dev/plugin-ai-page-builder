package run.halo.aipagebuilder.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Output schemas match MCP values, including omitted null fields. */
final class PageMcpOutputSchemas {
    private PageMcpOutputSchemas() { }

    static Map<String, Object> forTool(String name) {
        return switch (name) {
            case "get_page_capabilities" -> fields(
                "maxCharacters", fields("html", integer(), "css", integer(), "js", integer(),
                    "title", integer(), "description", integer()),
                "htmlTags", array(text()), "htmlAttributes", array(text()),
                "rules", array(text()), "workflow", array(text()));
            case "get_theme_context" -> theme();
            case "list_pages" -> page(summary(false));
            case "get_page" -> detail();
            case "create_page", "update_page_properties", "update_page", "patch_page", "publish_page", "unpublish_page",
                "restore_page_revision" -> summary(true);
            case "preview_page" -> fields("name", text(), "version", integer(),
                "draftRevision", text(), "previewUrl", uri(), "expiresAt", timestamp());
            case "list_page_revisions" -> page(revision());
            default -> throw new IllegalArgumentException("Missing output schema for tool: " +
                name);
        };
    }

    private static Map<String, Object> summary(boolean detail) {
        var schema = fields("name", text(), "title", text(), "version", integer(),
            "themeLayout", Map.of("type", "boolean"), "draftRevision", text(),
            "updatedAt", timestamp(), "hasUnpublishedChanges", Map.of("type", "boolean"));
        add(schema, "slug", text(), false);
        add(schema, "description", text(), false);
        add(schema, "publishedRevision", text(), false);
        if (detail) {
            add(schema, "mode", enumeration("visual", "code"), true);
            add(schema, "publicPath", Map.of("type", "string", "description",
                "Page path; public access depends on publishedRevision being present."), false);
            add(schema, "publicUrl", Map.of("type", "string", "format", "uri", "description",
                "Page URL when Halo's external URL is configured; may be unpublished."), false);
        }
        return schema;
    }

    private static Map<String, Object> detail() {
        var schema = summary(true);
        add(schema, "target", enumeration("draft", "published", "revision"), true);
        add(schema, "revision", text(), true);
        add(schema, "document", fields("html", text(), "css", text(), "js", text()), true);
        add(schema, "baseHash", Map.of("type", "string", "pattern", "^[a-f0-9]{64}$",
            "description", "Hash of the selected document's HTML, CSS and JavaScript."), true);
        return schema;
    }

    private static Map<String, Object> revision() {
        var schema = fields("name", text(), "createdAt", timestamp(), "createdBy", text(),
            "source", text());
        add(schema, "parentRevision", text(), false);
        return schema;
    }

    private static Map<String, Object> theme() {
        var rootState = fields("mode", enumeration("light", "dark"), "selector", text(),
            "classes", array(text()), "attributes",
            Map.of("type", "object", "additionalProperties", text()));
        var wrapper = fields("tag", text(), "classes", array(text()));
        add(wrapper, "style", text(), false);
        var contentSlot = fields("template", text(), "slot", array(wrapper), "wrapper",
            array(wrapper), "contentClasses", array(text()));
        var uiStack = fields("frameworks", array(text()), "componentClasses", array(text()));
        var schema = fields("layoutState", text(),
            "sources", array(fields("path", text(), "kind", text(), "content", text())),
            "notes", array(text()), "previewCss", text(), "colorMode",
            fields("strategy", enumeration("unknown", "explicit"),
                "rootStates", array(rootState), "evidence", array(text())),
            "contentSlot", contentSlot, "uiStack", uiStack);
        add(schema, "themeName", text(), false);
        add(schema, "displayName", text(), false);
        return schema;
    }

    private static Map<String, Object> page(Map<String, Object> item) {
        return fields("items", array(item), "total", integer(), "page", integer(), "size",
            integer());
    }

    private static Map<String, Object> fields(Object... entries) {
        var properties = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) {
            properties.put((String) entries[i], entries[i + 1]);
        }
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new ArrayList<>(properties.keySet()));
        schema.put("additionalProperties", false);
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static void add(Map<String, Object> schema, String key, Map<String, Object> value,
        boolean required) {
        ((Map<String, Object>) schema.get("properties")).put(key, value);
        if (required) ((List<String>) schema.get("required")).add(key);
    }

    private static Map<String, Object> text() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> integer() {
        return Map.of("type", "integer");
    }

    private static Map<String, Object> timestamp() {
        return Map.of("type", "string", "format", "date-time");
    }

    private static Map<String, Object> uri() {
        return Map.of("type", "string", "format", "uri");
    }

    private static Map<String, Object> enumeration(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    private static Map<String, Object> array(Map<String, Object> item) {
        return Map.of("type", "array", "items", item);
    }
}
