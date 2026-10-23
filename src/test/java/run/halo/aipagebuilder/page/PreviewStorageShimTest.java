package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class PreviewStorageShimTest {
    @Test
    void runsBeforeEveryHeadScript() {
        var document = Jsoup.parse(PreviewStorageShim.inject(
            "<!doctype html><html><head><script>theme()</script></head><body></body></html>"));
        var scripts = document.select("head script");
        assertThat(scripts).hasSize(2);
        assertThat(scripts.getFirst().attr("data-ai-page-builder"))
            .isEqualTo("preview-storage");
        assertThat(scripts.getFirst().data()).contains("Object.defineProperty");
        assertThat(scripts.getLast().data()).isEqualTo("theme()");
    }

    @Test
    void keepsHeadAttributesAndCase() {
        var injected = PreviewStorageShim.inject(
            "<html><HEAD data-theme=\"dark\"><title>x</title></HEAD></html>");
        assertThat(injected).startsWith("<html><HEAD data-theme=\"dark\"><script");
    }

    @Test
    void prependsWhenTheDocumentHasNoHead() {
        var injected = PreviewStorageShim.inject("<p>fragment</p>");
        assertThat(injected).startsWith("<script").endsWith("<p>fragment</p>");
    }
}
