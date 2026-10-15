package run.halo.aipagebuilder.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "aipagebuilder.halo.run", version = "v1alpha1", kind = "SiteMemory",
    plural = "sitememories", singular = "sitememory")
public class SiteMemory extends AbstractExtension {
    public static final String NAME = "site-memory";

    private Spec spec;

    @Data
    public static class Spec {
        private List<Entry> entries = new ArrayList<>();
    }

    public record Entry(String id, String subject, String key, String kind, String value,
                        Source source, Instant updatedAt) {
    }

    public record Source(String pageName, String sessionId, String runId, String sourceId,
                         String quote, String actor) {
    }
}
