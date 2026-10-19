package run.halo.aipagebuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecBuilder;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.PluginContext;
import run.halo.aipagebuilder.model.PageChatSession;
import run.halo.aipagebuilder.model.PageChatTurnRecord;
import run.halo.aipagebuilder.model.SiteMemory;
import run.halo.aipagebuilder.model.ThemePage;
import run.halo.aipagebuilder.model.ThemePageRevision;

@ExtendWith(MockitoExtension.class)
class AiPageBuilderPluginTest {

    @Mock
    PluginContext context;

    @Mock
    SchemeManager schemeManager;

    @Mock
    run.halo.aipagebuilder.ai.PageChatService chats;

    @Mock
    run.halo.aipagebuilder.page.PagePreviewStore previews;

    @InjectMocks
    AiPageBuilderPlugin plugin;

    @Test
    void registersAllSchemes() {
        plugin.start();
        var order = inOrder(schemeManager);
        order.verify(schemeManager).register(SiteMemory.class);
        order.verify(schemeManager).register(eq(run.halo.aipagebuilder.model.PageChatCheckpoint.class), any());
        order.verify(schemeManager).register(eq(PageChatSession.class), any());
        order.verify(schemeManager).register(eq(PageChatTurnRecord.class), any());
        order.verify(schemeManager).register(eq(ThemePage.class), any());
        order.verify(schemeManager).register(eq(ThemePageRevision.class), any());
        plugin.stop();
    }

    @Test
    void unregistersSiteMemorySchemeOnStop() {
        var scheme = Scheme.buildFromType(SiteMemory.class);
        when(schemeManager.get(SiteMemory.class)).thenReturn(scheme);

        plugin.stop();
        verify(previews).clear();

        var order = inOrder(chats, schemeManager);
        order.verify(chats).stop();
        order.verify(schemeManager).unregister(scheme);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void pageSlugIndexIsUnique() {
        IndexSpecs<ThemePage> indexes = mock(IndexSpecs.class);
        plugin.start();
        var configuration = ArgumentCaptor.forClass(Consumer.class);
        verify(schemeManager).register(eq(ThemePage.class), configuration.capture());
        configuration.getValue().accept(indexes);

        var captor = ArgumentCaptor.forClass(IndexSpecBuilder.class);
        verify(indexes, org.mockito.Mockito.times(4)).add(captor.capture());
        var slugIndex = captor.getAllValues().stream().map(IndexSpecBuilder::build)
            .filter(index -> "spec.slug".equals(index.getName())).findFirst().orElseThrow();
        assertThat(slugIndex.isUnique()).isTrue();
        var publishedIndex = captor.getAllValues().stream().map(IndexSpecBuilder::build)
            .filter(index -> "spec.publishedRevision".equals(index.getName()))
            .findFirst().orElseThrow();
        assertThat(publishedIndex.isNullable()).isTrue();
    }
}
