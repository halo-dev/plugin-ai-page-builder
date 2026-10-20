package run.halo.aipagebuilder;

import java.time.Instant;
import org.springframework.stereotype.Component;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.aipagebuilder.model.SiteMemory;
import run.halo.aipagebuilder.model.ThemePage;
import run.halo.aipagebuilder.model.ThemePageRevision;
import run.halo.aipagebuilder.page.PagePaths;

/**
 * <p>Plugin main class to manage the lifecycle of the plugin.</p>
 * <p>This class must be public and have a public constructor.</p>
 * <p>Only one main class extending {@link BasePlugin} is allowed per plugin.</p>
 *
 * @author ryanwang
 * @since 1.0.0
 */
@Component
public class AiPageBuilderPlugin extends BasePlugin {

    private final SchemeManager schemeManager;
    private final run.halo.aipagebuilder.ai.PageChatService chats;

    private final run.halo.aipagebuilder.page.PagePreviewStore previews;

    public AiPageBuilderPlugin(PluginContext pluginContext, SchemeManager schemeManager,
        run.halo.aipagebuilder.ai.PageChatService chats,
        run.halo.aipagebuilder.page.PagePreviewStore previews) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.chats = chats;
        this.previews = previews;
    }

    @Override
    public void start() {
        schemeManager.register(SiteMemory.class);
        schemeManager.register(run.halo.aipagebuilder.model.PageChatCheckpoint.class, indexes ->
            indexes.add(IndexSpecs.<run.halo.aipagebuilder.model.PageChatCheckpoint, String>single("spec.pageName", String.class)
                .indexFunc(checkpoint -> checkpoint.getSpec().getPageName())));
        schemeManager.register(run.halo.aipagebuilder.model.PageChatSession.class, indexes -> {
            indexes.add(IndexSpecs.<run.halo.aipagebuilder.model.PageChatSession, String>single("spec.pageName", String.class)
                .indexFunc(session -> session.getSpec().getPageName()));
            indexes.add(IndexSpecs.<run.halo.aipagebuilder.model.PageChatSession, Instant>single("spec.updatedAt", Instant.class)
                .indexFunc(session -> session.getSpec().getUpdatedAt()));
        });
        schemeManager.register(run.halo.aipagebuilder.model.PageChatTurnRecord.class, indexes -> {
            indexes.add(IndexSpecs.<run.halo.aipagebuilder.model.PageChatTurnRecord, String>single("spec.pageName", String.class)
                .indexFunc(record -> record.getSpec().getPageName()));
            indexes.add(IndexSpecs.<run.halo.aipagebuilder.model.PageChatTurnRecord, String>single("spec.sessionId", String.class)
                .indexFunc(record -> record.getSpec().getSessionId()));
            indexes.add(IndexSpecs.<run.halo.aipagebuilder.model.PageChatTurnRecord, String>single("spec.runId", String.class)
                .indexFunc(record -> record.getSpec().getRunId()));
            indexes.add(IndexSpecs.<run.halo.aipagebuilder.model.PageChatTurnRecord, String>single("spec.sortKey", String.class)
                .indexFunc(record -> record.getSpec().getSortKey()));
        });
        schemeManager.register(ThemePage.class, indexes -> {
            indexes.add(IndexSpecs.<ThemePage, String>single("spec.slug", String.class)
                .unique(true).indexFunc(PagePaths::slug));
            indexes.add(IndexSpecs.<ThemePage, String>single("spec.title", String.class)
                .indexFunc(page -> page.getSpec().getTitle()));
            indexes.add(IndexSpecs.<ThemePage, Instant>single("spec.updatedAt", Instant.class)
                .indexFunc(page -> page.getSpec().getUpdatedAt()));
            indexes.add(IndexSpecs.<ThemePage, String>single("spec.publishedRevision", String.class)
                .nullable(true).indexFunc(page -> page.getSpec().getPublishedRevision()));
        });
        schemeManager.register(ThemePageRevision.class, indexes -> {
            indexes.add(IndexSpecs.<ThemePageRevision, String>single("spec.pageName", String.class)
                .indexFunc(revision -> revision.getSpec().getPageName()));
        });
    }

    @Override
    public void stop() {
        previews.clear();
        chats.stop();
        schemeManager.unregister(schemeManager.get(SiteMemory.class));
        schemeManager.unregister(schemeManager.get(run.halo.aipagebuilder.model.PageChatCheckpoint.class));
        schemeManager.unregister(schemeManager.get(run.halo.aipagebuilder.model.PageChatTurnRecord.class));
        schemeManager.unregister(schemeManager.get(run.halo.aipagebuilder.model.PageChatSession.class));
        schemeManager.unregister(schemeManager.get(ThemePageRevision.class));
        schemeManager.unregister(schemeManager.get(ThemePage.class));
    }
}
