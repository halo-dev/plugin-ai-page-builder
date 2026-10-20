package run.halo.aipagebuilder.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "aipagebuilder.halo.run", version = "v1alpha1", kind = "ThemePageRevision",
    plural = "themepagerevisions", singular = "themepagerevision")
public class ThemePageRevision extends AbstractExtension {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    public static class Spec {
        private String pageName;
        private String createdBy;
        private String source;
        private String contentHash;
        // Links only successfully installed draft snapshots, including later manual saves.
        private String parentRevision;
        private PageDocument document;
    }
}
