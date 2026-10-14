package run.halo.aipagebuilder.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;
import run.halo.aipagebuilder.ai.ChatApi;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "aipagebuilder.halo.run", version = "v1alpha1", kind = "PageChatTurnRecord",
    plural = "pagechatturnrecords", singular = "pagechatturnrecord")
public class PageChatTurnRecord extends AbstractExtension {
    private Spec spec;

    @Data
    public static class Spec {
        private String pageName;
        private String sessionId;
        private String runId;
        private String sortKey;
        private ChatApi.Turn turn;
    }
}
