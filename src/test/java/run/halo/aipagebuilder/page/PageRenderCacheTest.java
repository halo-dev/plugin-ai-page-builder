package run.halo.aipagebuilder.page;

import org.junit.jupiter.api.Test;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PageRenderCacheTest {
    private final ContentPolicy policy = spy(new ContentPolicy());
    private final PageRenderCache cache = new PageRenderCache(policy);
    private final PageDocument document = new PageDocument(1, "code", null, null,
        "<p>Hello</p>", "p {color:red}");

    @Test
    void reusesDerivedContentAndSeparatesScopeAndChangedSource() {
        var first = cache.compile("a", document);
        assertThat(cache.compile("a", document)).isSameAs(first);
        verify(policy, times(1)).validate(document);
        assertThat(cache.compile("b", document).css()).contains("#b");
        var changed = new PageDocument(1, "code", null, null, "<p>Changed</p>", "");
        assertThat(cache.compile("a", changed).html()).contains("Changed");
        assertThat(document.html()).isEqualTo("<p>Hello</p>");
    }

    @Test
    void cacheDoesNotBypassValidationForDifferentMetadata() {
        cache.compile("a", document);
        var invalid = new PageDocument(999, "code", null, null,
            document.html(), document.css());
        assertThatThrownBy(() -> cache.compile("a", invalid)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void boundedCacheEvictsOldContentAndClearRevalidates() {
        cache.compile("first", document);
        for (int i = 0; i < 128; i++) cache.compile("scope-" + i, document);
        clearInvocations(policy);
        cache.compile("first", document);
        verify(policy).validate(document);
        cache.clear();
        cache.compile("first", document);
        verify(policy, times(2)).validate(document);
    }
}
