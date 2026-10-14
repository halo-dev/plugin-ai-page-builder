package run.halo.aipagebuilder.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;
import run.halo.aipagebuilder.ai.ChatApi;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "aipagebuilder.halo.run", version = "v1alpha1", kind = "PageChatSession",
    plural = "pagechatsessions", singular = "pagechatsession")
public class PageChatSession extends AbstractExtension {
    private Spec spec;

    @Data
    public static class Spec {
        private String pageName;
        private String title;
        private Instant createdAt;
        private Instant updatedAt;
        // Legacy turns are cleared only after every turn has been archived successfully.
        private List<ChatApi.Turn> turns = new ArrayList<>();
        // The page-named default session also owns the page-wide CAS reservation.
        private ChatApi.Turn active;
        private String activeSessionId;
        private String deletingSessionId;
        private boolean deleting;
        private String owner;
    }
}
