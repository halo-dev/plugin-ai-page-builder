package run.halo.aipagebuilder.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(name = "PageDocument", requiredProperties = {"schemaVersion", "mode", "html", "css", "title"})
public record PageDocument(
    int schemaVersion,
    @Schema(allowableValues = {"visual", "code"}) String mode,
    @Schema(nullable = true) String editorVersion,
    @Schema(nullable = true, additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
    Map<String, Object> projectData,
    String html,
    String css,
    String title,
    @Schema(nullable = true) String description,
    @Schema(description = "Page JavaScript") String js
) {
    public PageDocument {
        js = js == null ? "" : js;
    }

    public PageDocument(int schemaVersion, String mode, String editorVersion,
        Map<String, Object> projectData, String html, String css, String title, String description) {
        this(schemaVersion, mode, editorVersion, projectData, html, css, title, description, "");
    }
}
