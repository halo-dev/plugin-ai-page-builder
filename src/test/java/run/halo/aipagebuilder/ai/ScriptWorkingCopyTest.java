package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;

class ScriptWorkingCopyTest {
    @Test
    void scriptChangesParticipateInDigestAndAtomicPatch() {
        var copy = new PageWorkingCopy(new PageDocument(2, "code", null, null,
            "<button>Next</button>", "", "Page", "", "let index = 0;"), new ContentPolicy());
        var before = copy.read();
        assertThat(before.get("js")).isEqualTo("let index = 0;");
        assertThat(before.get("baseHash")).isNotEqualTo(DocumentHash.of(copy.document().html(), ""));
        var result = copy.patch(Map.of("baseHash", before.get("baseHash"), "patches", List.of(
            Map.of("field", "html", "oldText", "Next", "newText", "Previous"),
            Map.of("field", "js", "oldText", "index = 0", "newText", "index = 1")))).block();
        assertThat(result).containsEntry("valid", true);
        assertThat(copy.document().js()).isEqualTo("let index = 1;");
        assertThat(copy.document().schemaVersion()).isEqualTo(2);
        var hash = result.get("baseHash");
        assertThat(copy.patch(Map.of("baseHash", hash, "patches", List.of(
            Map.of("field", "html", "oldText", "Previous", "newText", "Changed"),
            Map.of("field", "js", "oldText", "index = 1", "newText", "bad\u0000")))).block())
            .containsEntry("valid", false);
        assertThat(copy.document().html()).contains("Previous");
        assertThat(copy.document().js()).isEqualTo("let index = 1;");
        assertThat(copy.update(Map.of("html", "<p>New</p>", "css", "")).block()).containsEntry("valid", true);
        assertThat(copy.document().js()).isEqualTo("let index = 1;");
    }

    @Test
    void emptyScriptPreservesExistingDigestProtocol() {
        assertThat(DocumentHash.of("你好", "p{color:red}", ""))
            .isEqualTo(DocumentHash.of("你好", "p{color:red}"));
    }
}
