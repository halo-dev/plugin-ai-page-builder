package run.halo.aipagebuilder.page;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import run.halo.aipagebuilder.model.PageDocument;
import run.halo.aipagebuilder.model.ThemePage;
import run.halo.aipagebuilder.model.ThemeRef;

public final class PageApi {
    private PageApi() {
    }

    @Schema(name = "CreatePageRequest", requiredProperties = {"title"})
    public record CreatePageRequest(
        @Schema(nullable = true, pattern = "^[a-z0-9]+(?:-[a-z0-9]+)*(?:/[a-z0-9]+(?:-[a-z0-9]+)*)*$",
            maxLength = 200) String slug,
        String title,
        @Schema(nullable = true, description = "Whether to reuse the theme layout; defaults to true") Boolean themeLayout
    ) {
        public CreatePageRequest(String slug, String title) {
            this(slug, title, null);
        }
    }

    @Schema(name = "ClonePageRequest", requiredProperties = {"version", "title"})
    public record ClonePageRequest(Long version,
        @Schema(nullable = true, pattern = "^[a-z0-9]+(?:-[a-z0-9]+)*(?:/[a-z0-9]+(?:-[a-z0-9]+)*)*$",
            maxLength = 200) String slug, String title) { }

    @Schema(name = "UpdatePagePropertiesRequest", requiredProperties = {"version"},
        description = "Page properties take effect immediately. Omitted fields remain unchanged.")
    public record UpdatePagePropertiesRequest(Long version,
        @Schema(maxLength = 200) String title,
        @Schema(maxLength = 1000) String description,
        @Schema(pattern = "^[a-z0-9]+(?:-[a-z0-9]+)*(?:/[a-z0-9]+(?:-[a-z0-9]+)*)*$",
            maxLength = 200) String slug, Boolean themeLayout, Boolean authorizeRequired,
        @Schema(nullable = true, description = "Users and roles allowed to read a restricted page")
        List<ThemePage.PageMember> members) { }

    @Schema(name = "SiteUrlResponse", requiredProperties = {"baseUrl"})
    public record SiteUrlResponse(String baseUrl) { }

    @Schema(name = "SavePageRequest", requiredProperties = {"version", "document", "source"})
    public record SavePageRequest(Long version, PageDocument document,
        @Schema(allowableValues = {"manual", "ai"}) String source) { }

    @Schema(name = "MutationRequest", requiredProperties = {"version"})
    public record MutationRequest(Long version) {
    }

    @Schema(name = "RestoreRequest", requiredProperties = {"version", "revision"})
    public record RestoreRequest(Long version, String revision) {
    }

    @Schema(name = "PageSummary", requiredProperties = {"name", "title", "description",
        "version", "themeLayout", "authorizeRequired", "draftRevision", "updatedAt",
        "hasUnpublishedChanges"})
    public record PageSummary(String name,
        @Schema(nullable = true) String slug, String title,
        @Schema(nullable = true) String description,
        Long version, boolean themeLayout, boolean authorizeRequired,
        @Schema(nullable = true) List<ThemePage.PageMember> members, String draftRevision,
        @Schema(nullable = true) String publishedRevision,
        Instant updatedAt,
        boolean hasUnpublishedChanges,
        @Schema(nullable = true) ThemeRef publishedTheme) {
        public PageSummary(String name, String slug, String title, String description,
            Long version, boolean themeLayout, boolean authorizeRequired,
            List<ThemePage.PageMember> members, String draftRevision, String publishedRevision,
            Instant updatedAt, boolean hasUnpublishedChanges) {
            this(name, slug, title, description, version, themeLayout, authorizeRequired, members,
                draftRevision, publishedRevision, updatedAt, hasUnpublishedChanges, null);
        }
    }

    @Schema(name = "PageDetail", requiredProperties = {"page", "document"})
    public record PageDetail(PageSummary page, PageDocument document,
        @Schema(nullable = true) ThemeRef revisionTheme) {
        public PageDetail(PageSummary page, PageDocument document) {
            this(page, document, null);
        }
    }

    @Schema(name = "PageList", requiredProperties = {"items", "total", "page", "size"})
    public record PageList(List<PageSummary> items, long total, int page, int size,
        @Schema(nullable = true) ThemeRef currentTheme) {
        public PageList(List<PageSummary> items, long total, int page, int size) {
            this(items, total, page, size, null);
        }
    }

    @Schema(name = "RevisionSummary", requiredProperties = {"name", "createdAt", "createdBy",
        "source"})
    public record RevisionSummary(String name, Instant createdAt, String createdBy, String source,
        String parentRevision, @Schema(nullable = true) ThemeRef theme) {
        public RevisionSummary(String name, Instant createdAt, String createdBy, String source,
            String parentRevision) {
            this(name, createdAt, createdBy, source, parentRevision, null);
        }
    }

    @Schema(name = "RevisionDetail", requiredProperties = {"summary", "document"})
    public record RevisionDetail(RevisionSummary summary, PageDocument document) {
    }

    @Schema(name = "ImportReview", requiredProperties = {"reviewId", "verdict", "summary",
        "findings", "title", "themeLayout", "expiresAt"})
    public record ImportReview(String reviewId,
        @Schema(allowableValues = {"safe", "suspicious", "malicious"}) String verdict,
        String summary, List<ImportFinding> findings, String title,
        @Schema(nullable = true) String description, boolean themeLayout, Instant expiresAt) {
    }

    @Schema(name = "ImportFinding", requiredProperties = {"severity", "category", "file",
        "evidence", "explanation"})
    public record ImportFinding(String severity, String category, String file, String evidence,
        String explanation) {
    }
}
