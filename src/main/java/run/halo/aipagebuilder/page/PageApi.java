package run.halo.aipagebuilder.page;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import run.halo.aipagebuilder.model.PageDocument;

public final class PageApi {
    private PageApi() {
    }

    @Schema(name = "CreatePageRequest", requiredProperties = {"slug", "title"})
    public record CreatePageRequest(
        @Schema(pattern = "^[a-z0-9]+(?:-[a-z0-9]+)*(?:/[a-z0-9]+(?:-[a-z0-9]+)*)*$",
            maxLength = 200) String slug,
        String title,
        @Schema(nullable = true, description = "Whether to reuse the theme layout; defaults to true") Boolean themeLayout
    ) {
        public CreatePageRequest(String slug, String title) {
            this(slug, title, null);
        }
    }

    @Schema(name = "ClonePageRequest", requiredProperties = {"version", "slug", "title"})
    public record ClonePageRequest(Long version,
        @Schema(pattern = "^[a-z0-9]+(?:-[a-z0-9]+)*(?:/[a-z0-9]+(?:-[a-z0-9]+)*)*$",
            maxLength = 200) String slug, String title) { }

    @Schema(name = "UpdatePageSlugRequest", requiredProperties = {"version", "slug"})
    public record UpdatePageSlugRequest(Long version,
        @Schema(pattern = "^[a-z0-9]+(?:-[a-z0-9]+)*(?:/[a-z0-9]+(?:-[a-z0-9]+)*)*$",
            maxLength = 200) String slug) { }

    @Schema(name = "UpdatePageLayoutRequest", requiredProperties = {"version", "themeLayout"})
    public record UpdatePageLayoutRequest(Long version, boolean themeLayout) { }

    @Schema(name = "SavePageRequest", requiredProperties = {"version", "document", "source"})
    public record SavePageRequest(Long version, PageDocument document,
        @Schema(allowableValues = {"manual", "ai"}) String source) {
    }

    @Schema(name = "MutationRequest", requiredProperties = {"version"})
    public record MutationRequest(Long version) {
    }

    @Schema(name = "RestoreRequest", requiredProperties = {"version", "revision"})
    public record RestoreRequest(Long version, String revision) {
    }

    @Schema(name = "PageSummary", requiredProperties = {"name", "slug", "title", "description",
        "version", "themeLayout", "draftRevision", "updatedAt", "hasUnpublishedChanges"})
    public record PageSummary(String name, String slug, String title,
        @Schema(nullable = true) String description,
        Long version, boolean themeLayout, String draftRevision,
        @Schema(nullable = true) String publishedRevision,
        Instant updatedAt,
        boolean hasUnpublishedChanges) {
    }

    @Schema(name = "PageDetail", requiredProperties = {"page", "document"})
    public record PageDetail(PageSummary page, PageDocument document) {
    }

    @Schema(name = "PageList", requiredProperties = {"items", "total", "page", "size"})
    public record PageList(List<PageSummary> items, long total, int page, int size) {
    }

    @Schema(name = "RevisionSummary", requiredProperties = {"name", "createdAt", "createdBy",
        "source", "title"})
    public record RevisionSummary(String name, Instant createdAt, String createdBy, String source,
        String title) {
    }

    @Schema(name = "RevisionDetail", requiredProperties = {"summary", "document"})
    public record RevisionDetail(RevisionSummary summary, PageDocument document) {
    }
}
