package run.halo.aipagebuilder.model;

import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.aipagebuilder.ai.ChatApi;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/** A recoverable working copy, never a page revision or published content. */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "aipagebuilder.halo.run", version = "v1alpha1", kind = "PageChatCheckpoint",
    plural = "pagechatcheckpoints", singular = "pagechatcheckpoint")
public class PageChatCheckpoint extends AbstractExtension {
    private Spec spec;

    @Data
    public static class Spec {
        private int formatVersion = 1;
        private String pageName;
        private String sessionId;
        private String actor;
        private String baseRevision;
        private ChatApi.Request request;
        private PageDocument document;
        private ChatApi.Turn turn;
        private boolean invalidUpdate;
        private String slug;
        private String title;
        private String description;
        private boolean invalidTitleUpdate;
        private boolean invalidSlugUpdate;
        private boolean available;
        private int steps;
        private int toolCalls;
        private int recoveries;
        private Instant updatedAt;
    }
}
