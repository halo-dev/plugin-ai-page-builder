package run.halo.aipagebuilder.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
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

    /** A successful AI save may change properties without producing a content revision. */
    public record AiCommit(String runId, String revision) { }

    @Schema(name = "PageMemberType")
    public enum MemberType {
        USER, ROLE
    }

    @Schema(name = "PageMember")
    public record PageMember(MemberType kind, String name) { }

    @Data
    public static class Spec {
        @Schema(pattern = "^[a-z0-9]+(?:-[a-z0-9]+)*(?:/[a-z0-9]+(?:-[a-z0-9]+)*)*$",
            maxLength = 200)
        private String slug;
        private String title;
        private String description;
        private boolean themeLayout = true;
        /** Restricts the published page to the super administrator and listed members. */
        private boolean authorizeRequired;
        /** Users and roles allowed to read a restricted page; empty means super administrator only. */
        private List<PageMember> members;
        private String draftRevision;
        private String publishedRevision;
        /**
         * Snapshot of the published revision's theme; null when unknown at save or publish time.
         */
        private ThemeRef publishedTheme;
        /** Written with the page CAS; older runs are recorded in conversation history. */
        private AiCommit lastAiCommit;
        private Instant updatedAt;
    }
}
