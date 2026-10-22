package run.halo.aipagebuilder.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** The active theme a page revision was saved under. */
@Schema(name = "ThemeRef", requiredProperties = {"name"})
public record ThemeRef(String name, @Schema(nullable = true) String displayName) {
}
