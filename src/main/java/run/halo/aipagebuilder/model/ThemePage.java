package run.halo.aipagebuilder.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "aipagebuilder.halo.run", version = "v1alpha1", kind = "ThemePage",
    plural = "themepages", singular = "themepage")
public class ThemePage extends AbstractExtension {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    public static class Spec {
        @Schema(pattern = "^[a-z0-9]+(?:-[a-z0-9]+)*(?:/[a-z0-9]+(?:-[a-z0-9]+)*)*$",
            maxLength = 200)
        private String slug;
        private String title;
        private String description;
        /** Whether the page renders inside the theme layout; null means true. */
        private Boolean themeLayout;
        private String draftRevision;
        private String publishedRevision;
        private Instant updatedAt;
    }
}
