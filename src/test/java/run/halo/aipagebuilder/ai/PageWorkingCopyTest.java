package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.infra.utils.JsonUtils;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;
import run.halo.aipagebuilder.theme.ThemeContextExtractor;
import run.halo.aipagebuilder.theme.ThemeStyleContext;

class PageWorkingCopyTest {
    @Test
    void memorySuccessRemainsRecordedWhenItsOutputExceedsTheBudget() {
        var service = org.mockito.Mockito.mock(run.halo.aipagebuilder.memory.SiteMemoryService.class);
        org.mockito.Mockito.when(service.apply(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(Mono.just(PageMemoryToolsTest.snapshot(2)));
        var memory = new PageMemoryTools(service, PageMemoryToolsTest.snapshot(1),
            new run.halo.aipagebuilder.memory.SiteMemoryService.SourceContext("p", "s", "run", "admin"));
        memory.prompt("0571-12345678");
        var copy = copy("<p>old</p>", "");
        copy.memory(memory);
        var input = PageMemoryToolsTest.input(1, "run/prompt", "0571-12345678");
        copy.consume("x".repeat(PageWorkingCopy.MAX_BYTES
            - JsonUtils.objectToJson(input).getBytes(java.nio.charset.StandardCharsets.UTF_8).length - 1));
        assertThatThrownBy(() -> copy.updateMemory("memory", input).block())
            .hasMessageContaining("预算");
        copy.closeMemory().block();
        assertThat(copy.exhausted()).isTrue();
        assertThat(copy.summaries()).singleElement().satisfies(tool ->
            assertThat(tool.status()).isEqualTo("completed"));
        assertThat(copy.memoryOutcomes().get("memory").status()).isEqualTo("completed");
        org.mockito.Mockito.verify(service).apply(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void hashUsesLengthPrefixedUtf8AndPreservesExactText() {
        assertThat(DocumentHash.of("", ""))
            .isEqualTo("af5570f5a1810b7af78caf4bc70a660f0df51e42baf91d4de5b2328de0e83dfc");
        assertThat(DocumentHash.of("你好🌍", ".x{color:red}"))
            .isEqualTo("557f0b0e80db941285eebb74d056458f023a21f3afaa2430bc727722727ed668");
        assertThat(DocumentHash.of("ab", "c")).isNotEqualTo(DocumentHash.of("a", "bc"));
        assertThat(DocumentHash.of("a\n", "")).isNotEqualTo(DocumentHash.of("a\r\n", ""));
    }

    @Test
    void readAndUpdateReturnCurrentHashAndReadIncludesRunAnnotations() {
        var copy = copy("<p>old</p>", "");
        var annotation = new ChatApi.Annotation("a", List.of(0), "p", "old", "<p>old</p>", "edit");
        var annotations = new ArrayList<>(List.of(annotation));
        copy.annotations(annotations);
        annotations.clear();
        assertThat(copy.read()).containsEntry("baseHash", hash(copy))
            .containsEntry("annotations", List.of(annotation));
        assertThat(copy.update(Map.of("html", "<p>new</p>", "css", "")))
            .containsEntry("valid", true).containsEntry("staged", true)
            .containsEntry("baseHash", DocumentHash.of("<p>new</p>", ""));
        assertThat(copy.read()).containsEntry("baseHash", hash(copy));
    }

    @Test
    void semanticSlugIsStagedWithTitleAndContent() {
        var copy = copy("<p>old</p>", "");
        assertThat(copy.read()).containsEntry("slug", "").containsEntry("canSetSlug", true);
        assertThat(copy.setSlug(Map.of("slug", "  company/about-us  ")))
            .containsEntry("valid", true).containsEntry("staged", true);
        copy.setTitle(Map.of("title", "关于我们"));
        copy.update(Map.of("html", "<p>About us</p>", "css", ""));
        assertThat(copy.read()).containsEntry("slug", "company/about-us")
            .containsEntry("title", "关于我们");
        assertThat(copy.setSlug(Map.of("slug", "company/about-us")))
            .containsEntry("staged", false);
        assertThat(copy.hasInvalidUpdate()).isFalse();
    }

    @Test
    void invalidSlugCannotBeHiddenByContentOrTitleUpdates() {
        for (var slug : List.of("", "UPPER", "/about", "console/about", "a".repeat(201))) {
            var copy = copy("<p>old</p>", "");
            assertThat(copy.setSlug(Map.of("slug", slug))).containsEntry("valid", false);
            assertThat(copy.slug()).isNull();
            copy.setTitle(Map.of("title", "About"));
            copy.update(Map.of("html", "<p>About</p>", "css", ""));
            assertThat(copy.hasInvalidUpdate()).isTrue();
            assertThat(copy.setSlug(Map.of("slug", "about"))).containsEntry("valid", true);
            assertThat(copy.hasInvalidUpdate()).isFalse();
        }
    }

    @Test
    void existingAndPublishedPathsAreProtectedFromAi() {
        var document = copy("<p>old</p>", "").document();
        for (var published : List.of(false, true)) {
            var copy = new PageWorkingCopy(document, new ContentPolicy(),
                Mono.just(ThemeStyleContext.unavailable()), "custom-path", published);
            assertThat(copy.read()).containsEntry("canSetSlug", false);
            assertThat(copy.setSlug(Map.of("slug", "about"))).containsEntry("valid", false);
            assertThat(copy.slug()).isEqualTo("custom-path");
        }
        var legacy = new PageWorkingCopy(document, new ContentPolicy(),
            Mono.just(ThemeStyleContext.unavailable()), null, true);
        assertThat(legacy.setSlug(Map.of("slug", "about"))).containsEntry("valid", false);
        assertThat(legacy.slug()).isNull();
    }

    @Test
    void setTitleStagesTrimmedTitleAndSurvivesLaterContentUpdates() {
        var copy = copy("<p>old</p>", "");
        assertThat(copy.setTitle(Map.of("title", "  新标题  ")))
            .containsEntry("valid", true).containsEntry("staged", true);
        assertThat(copy.document().title()).isEqualTo("新标题");
        assertThat(copy.document().html()).isEqualTo("<p>old</p>");
        assertThat(copy.hasInvalidUpdate()).isFalse();
        assertThat(copy.update(Map.of("html", "<p>new</p>", "css", "")))
            .containsEntry("valid", true);
        assertThat(copy.document().title()).isEqualTo("新标题");
        assertThat(copy.summaries()).extracting(ChatApi.Tool::name)
            .containsExactly("update_page_title", "update_current_page");
    }

    @Test
    void invalidTitleIsRejectedWithoutMutationAndClearedByLaterSuccess() {
        var copy = copy("<p>old</p>", "");
        var original = copy.document();
        List<Map<String, Object>> invalidInputs = List.of(
            Map.<String, Object>of(),
            Map.<String, Object>of("title", "   "),
            Map.<String, Object>of("title", "x".repeat(201)),
            Map.<String, Object>of("title", 1));
        for (var input : invalidInputs) {
            assertThat(copy.setTitle(input)).containsEntry("valid", false)
                .containsEntry("staged", false);
            assertThat(copy.document()).isSameAs(original);
            assertThat(copy.hasInvalidUpdate()).isTrue();
        }
        assertThat(copy.setTitle(Map.of("title", "恢复")))
            .containsEntry("valid", true).containsEntry("staged", true);
        assertThat(copy.hasInvalidUpdate()).isFalse();
        assertThat(copy.setTitle(Map.of("title", "恢复")))
            .containsEntry("valid", true).containsEntry("staged", false);
        assertThat(copy.document().title()).isEqualTo("恢复");
    }

    @Test
    void patchesUseOriginalPositionsAndApplyInDescendingOrderAcrossFields() {
        var copy = copy("<p>one two three</p>", "p{color:red}");
        var result = copy.patch(Map.of("baseHash", hash(copy), "patches", List.of(
            patch("html", "one", "a much longer first value"),
            patch("css", "red", "blue"),
            patch("html", "three", ""),
            patch("html", "two", "one"))));
        assertThat(copy.document().html()).isEqualTo("<p>a much longer first value one </p>");
        assertThat(copy.document().css()).isEqualTo("p{color:blue}");
        assertThat(result).containsEntry("valid", true).containsEntry("staged", true)
            .containsEntry("baseHash", hash(copy));
        assertThat(copy.summaries()).extracting(ChatApi.Tool::name).containsExactly("patch_current_page");
    }

    @Test
    void hashMismatchAndInvalidBatchShapesAreRejectedWithoutMutation() {
        List<Map<String, Object>> invalidInputs = List.of(
            Map.of(),
            Map.of("baseHash", "stale", "patches", List.of(patch("html", "old", "new"))),
            Map.of("patches", List.of(patch("html", "old", "new"))),
            Map.of("baseHash", DocumentHash.of("<p>old</p>", "")),
            Map.of("baseHash", DocumentHash.of("<p>old</p>", ""), "patches", "invalid"));
        for (var input : invalidInputs) {
            var copy = copy("<p>old</p>", "");
            var original = copy.document();
            assertThat(copy.patch(input)).containsEntry("valid", false)
                .containsEntry("staged", false).containsEntry("baseHash", hash(copy));
            assertThat(copy.document()).isSameAs(original);
            assertThat(copy.hasInvalidUpdate()).isTrue();
        }
    }

    @Test
    void malformedPatchEntriesAndEmptyOrOversizedBatchesAreRejected() {
        List<List<?>> batches = List.of(
            List.of(),
            List.of("invalid"),
            List.of(Map.of()),
            List.of(patch("title", "old", "new")),
            List.of(patch("html", "", "new")),
            List.of(Map.of("field", "html", "oldText", 1, "newText", "new")),
            List.of(Map.of("field", "html", "oldText", "old", "newText", 1)),
            List.of(Map.of("field", "html", "oldText", "old")),
            java.util.Collections.nCopies(21, patch("html", "old", "new")));
        for (var patches : batches) {
            var copy = copy("<p>old</p>", "");
            var original = copy.document();
            assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches", patches)))
                .containsEntry("valid", false);
            assertThat(copy.document()).isSameAs(original);
        }
    }

    @Test
    void missingAndRepeatedIncludingOverlappingOccurrencesAreRejected() {
        for (var oldText : List.of("missing", "aa")) {
            var copy = copy("<p>aaa</p>", "");
            var original = copy.document();
            assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches",
                List.of(patch("html", oldText, "new")))))
                .containsEntry("valid", false);
            assertThat(copy.document()).isSameAs(original);
        }
        var repeated = copy("<p>old old</p>", "");
        assertThat(repeated.patch(Map.of("baseHash", hash(repeated), "patches",
            List.of(patch("html", "old", "new")))))
            .containsEntry("valid", false);
    }

    @Test
    void intersectingAndIdenticalRangesAreRejectedButAdjacentRangesAreAllowed() {
        for (var second : List.of("bc", "ab")) {
            var copy = copy("<p>abc</p>", "");
            var original = copy.document();
            assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches", List.of(
                patch("html", "ab", "X"), patch("html", second, "Y")))))
                .containsEntry("valid", false);
            assertThat(copy.document()).isSameAs(original);
        }
        var adjacent = copy("<p>abc</p>", "");
        assertThat(adjacent.patch(Map.of("baseHash", hash(adjacent), "patches", List.of(
            patch("html", "a", "X"), patch("html", "bc", "Y")))))
            .containsEntry("valid", true);
        assertThat(adjacent.document().html()).isEqualTo("<p>XY</p>");
    }

    @Test
    void laterPatchCannotMatchTextIntroducedByEarlierPatch() {
        var copy = copy("<p>old</p>", "");
        var original = copy.document();
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches", List.of(
            patch("html", "old", "new"), patch("html", "new", "latest")))))
            .containsEntry("valid", false);
        assertThat(copy.document()).isSameAs(original);
    }

    @Test
    void wholeCandidateValidationIsAtomicAndInvalidStateIsStickyUntilSuccess() {
        var copy = copy("<p>old</p>", "p{color:red}");
        var original = copy.document();
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches", List.of(
            patch("html", "old", "new"), patch("css", "red", "expression(alert(1))")))))
            .containsEntry("valid", false);
        assertThat(copy.document()).isSameAs(original);
        assertThat(copy.hasInvalidUpdate()).isTrue();
        copy.read();
        copy.readTheme().block();
        assertThat(copy.hasInvalidUpdate()).isTrue();
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches",
            List.of(patch("html", "old", "new")))))
            .containsEntry("valid", true);
        assertThat(copy.hasInvalidUpdate()).isFalse();
        var staged = copy.document();
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches",
            List.of(patch("html", "new", "<script>bad</script>")))))
            .containsEntry("valid", false);
        assertThat(copy.document()).isSameAs(staged);
        assertThat(copy.hasInvalidUpdate()).isTrue();
        assertThat(copy.update(Map.of("html", "<p>recovered</p>", "css", "p{color:red}")))
            .containsEntry("valid", true);
        assertThat(copy.hasInvalidUpdate()).isFalse();
    }

    @Test
    void unsupportedInputsReturnGuidanceWithoutStagingAndAllowAnAgreedSupportedRepair() {
        for (var html : List.of("<input>", "<textarea></textarea>",
            "<select><option>One</option></select>", "<div contenteditable=\"true\">Search</div>")) {
            for (var usePatch : List.of(false, true)) {
                var copy = copy("<p>old</p>", "");
                var original = copy.document();
                var originalHash = hash(copy);
                var result = usePatch
                    ? copy.patch(Map.of("baseHash", originalHash, "patches",
                        List.of(patch("html", "<p>old</p>", html))))
                    : copy.update(Map.of("html", html, "css", ""));
                assertThat(result).containsEntry("valid", false).containsEntry("staged", false)
                    .containsEntry("baseHash", originalHash);
                assertThat((String) result.get("guidance"))
                    .contains("ask_user", "do not try equivalent workarounds", "not staged");
                assertThat(copy.document()).isSameAs(original);
                assertThat(copy.hasInvalidUpdate()).isTrue();
                assertThat(copy.update(Map.of("html", "<button type=\"button\">All</button>", "css", "")))
                    .containsEntry("valid", true).containsEntry("staged", true);
                assertThat(copy.hasInvalidUpdate()).isFalse();
            }
        }
    }

    @Test
    void successfulPatchInvalidatesOldBaseHash() {
        var copy = copy("<p>old</p>", "");
        var oldHash = hash(copy);
        copy.patch(Map.of("baseHash", oldHash, "patches", List.of(patch("html", "old", "new"))));
        assertThat(copy.patch(Map.of("baseHash", oldHash, "patches",
            List.of(patch("html", "new", "latest")))))
            .containsEntry("valid", false).containsEntry("baseHash", hash(copy));
        assertThat(copy.document().html()).isEqualTo("<p>new</p>");
    }

    @Test
    void noOpDoesNotNormalizeLegacyDocumentAndCanClearInvalidState() {
        var document = new PageDocument(1, "visual", "legacy", Map.of(), "<p>old</p>", "", "Title", "desc");
        var copy = new PageWorkingCopy(document, new ContentPolicy());
        copy.update(Map.of("html", "<script>bad</script>", "css", ""));
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches",
            List.of(patch("html", "old", "old")))))
            .containsEntry("valid", true).containsEntry("staged", false)
            .containsEntry("baseHash", hash(copy));
        assertThat(copy.document()).isSameAs(document);
        assertThat(copy.hasInvalidUpdate()).isFalse();
        assertThat(copy.update(Map.of("html", document.html(), "css", document.css())))
            .containsEntry("valid", true).containsEntry("staged", false);
        assertThat(copy.document()).isSameAs(document);
    }

    @Test
    void noOpStillValidatesWholeDocument() {
        var copy = copy("<script>bad</script>", "");
        var original = copy.document();
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches",
            List.of(patch("html", "bad", "bad")))))
            .containsEntry("valid", false);
        assertThat(copy.document()).isSameAs(original);
        assertThat(copy.hasInvalidUpdate()).isTrue();
    }

    @Test
    void patchReusesCallAndByteBudgetsWithoutPartialStaging() {
        var copy = copy("<p>old</p>", "");
        for (int i = 0; i < PageWorkingCopy.MAX_CALLS; i++) {
            copy.patch(Map.of());
        }
        assertThatThrownBy(() -> copy.patch(Map.of("baseHash", hash(copy), "patches",
            List.of(patch("html", "old", "new")))))
            .isInstanceOf(IllegalStateException.class);
        assertThat(copy.exhausted()).isTrue();
        assertThat(copy.document().html()).isEqualTo("<p>old</p>");
        var bytes = copy("<p>old</p>", "");
        bytes.consume("x".repeat(PageWorkingCopy.MAX_BYTES));
        assertThatThrownBy(() -> bytes.patch(Map.of("baseHash", hash(bytes), "patches",
            List.of(patch("html", "old", "new")))))
            .isInstanceOf(IllegalStateException.class);
        assertThat(bytes.exhausted()).isTrue();
        assertThat(bytes.document().html()).isEqualTo("<p>old</p>");
        assertThat(bytes.summaries()).isEmpty();
    }

    @Test
    void twentyDistinctPatchesAreAccepted() {
        var patches = new ArrayList<Map<String, Object>>();
        var html = new StringBuilder("<p>");
        for (int i = 0; i < 20; i++) {
            html.append("[").append(i).append("]");
            patches.add(patch("html", "[" + i + "]", "x"));
        }
        var copy = copy(html.append("</p>").toString(), "");
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches", patches)))
            .containsEntry("valid", true);
        assertThat(copy.document().html()).isEqualTo("<p>" + "x".repeat(20) + "</p>");
    }

    @Test
    void changedCssHonorsReadThemeSnapshotWhileTextOnlyPatchPreservesLegacyCss() {
        var css = "@media (prefers-color-scheme:dark){p{color:red}}";
        var context = new ThemeStyleContext("sample", "Sample", "SUPPORTED", List.of(), List.of(), "",
            new ThemeStyleContext.ColorMode("explicit", List.of(), List.of()));
        var copy = new PageWorkingCopy(
            new PageDocument(1, "code", null, null, "<p>old</p>", css, "Title", ""),
            new ContentPolicy(), Mono.just(context));
        copy.readTheme().block();
        var original = copy.document();
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches", List.of(
            patch("html", "old", "new"), patch("css", "red", "blue")))))
            .containsEntry("valid", false);
        assertThat(copy.document()).isSameAs(original);
        assertThat(copy.hasInvalidUpdate()).isTrue();
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches",
            List.of(patch("html", "old", "new")))))
            .containsEntry("valid", true).containsEntry("staged", true);
        assertThat(copy.document().css()).isEqualTo(css);
        assertThat(copy.hasInvalidUpdate()).isFalse();
        assertThat(copy.update(Map.of("html", "<p>new</p>", "css", css.replace("red", "blue"))))
            .containsEntry("valid", false);
        assertThat(copy.document().css()).isEqualTo(css);
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches",
            List.of(patch("css", css, "p{color:blue}")))))
            .containsEntry("valid", true);
        assertThat(copy.hasInvalidUpdate()).isFalse();
    }

    @Test
    void preparingThemeEnforcesColorModeWithoutConsumingToolBudgetOrSummaries() {
        var reads = new AtomicInteger();
        var context = new ThemeStyleContext("sample", "Sample", "SUPPORTED", List.of(), List.of(), "",
            new ThemeStyleContext.ColorMode("explicit", List.of(), List.of()));
        var copy = new PageWorkingCopy(
            new PageDocument(1, "code", null, null, "<p>old</p>", "p{color:red}", "Title", ""),
            new ContentPolicy(), Mono.fromSupplier(() -> {
                reads.incrementAndGet();
                return context;
            }));
        for (int i = 0; i <= PageWorkingCopy.MAX_CALLS; i++) {
            assertThat(copy.prepareTheme().block()).isSameAs(context);
        }
        assertThat(reads).hasValue(1);
        assertThat(copy.summaries()).isEmpty();
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches", List.of(
            patch("css", "p{color:red}", "@media(prefers-color-scheme:dark){p{color:blue}}")))))
            .containsEntry("valid", false);
        assertThat(copy.document().css()).isEqualTo("p{color:red}");
        for (int i = 1; i < PageWorkingCopy.MAX_CALLS; i++) {
            copy.read();
        }
        assertThat(copy.exhausted()).isFalse();
        assertThatThrownBy(copy::read).isInstanceOf(IllegalStateException.class);
        var byteLimited = new PageWorkingCopy(copy.document(), new ContentPolicy(), Mono.just(context));
        byteLimited.consume("x".repeat(PageWorkingCopy.MAX_BYTES));
        assertThat(byteLimited.prepareTheme().block()).isSameAs(context);
        assertThat(byteLimited.exhausted()).isFalse();
        assertThat(byteLimited.summaries()).isEmpty();
    }

    @Test
    void cssChangeDoesNotReadThemeImplicitly() {
        var reads = new AtomicInteger();
        var copy = new PageWorkingCopy(
            new PageDocument(1, "code", null, null, "<p>old</p>", "p{color:red}", "Title", ""),
            new ContentPolicy(), Mono.fromSupplier(() -> {
                reads.incrementAndGet();
                return ThemeStyleContext.unavailable();
            }));
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches",
            List.of(patch("css", "red", "blue")))))
            .containsEntry("valid", true);
        assertThat(reads).hasValue(0);
    }

    @Test
    void steerQueueDrainsPendingExactlyOnce() {
        var copy = copy("<p>old</p>", "");
        assertThat(copy.drainSteers()).isEmpty();
        copy.queueSteer("先改标题");
        copy.queueSteer("再缩短一点");
        assertThat(copy.drainSteers()).containsExactly("先改标题", "再缩短一点");
        assertThat(copy.drainSteers()).isEmpty();
    }

    private static PageWorkingCopy copy(String html, String css) {
        return new PageWorkingCopy(new PageDocument(1, "code", null, null, html, css, "Title", ""),
            new ContentPolicy());
    }

    private static String hash(PageWorkingCopy copy) {
        return DocumentHash.of(copy.document().html(), copy.document().css());
    }

    private static Map<String, Object> patch(String field, String oldText, String newText) {
        return Map.of("field", field, "oldText", oldText, "newText", newText);
    }

    @Test
    void themeIsLazyCachedPerRunAndOnlySummariesPersist() {
        var reads = new AtomicInteger();
        var context = new ThemeStyleContext("sample", "Sample", "SUPPORTED",
            List.of(new ThemeContextExtractor.Source("templates/layout.html", "layout", "theme-source-marker")),
            List.of(), ":root{--accent:red}");
        var document = new PageDocument(1, "code", null, null, "<p>old</p>", "", "Title", "");
        var copy = new PageWorkingCopy(document, new ContentPolicy(), Mono.fromSupplier(() -> {
            reads.incrementAndGet();
            return context;
        }));
        assertThat(reads).hasValue(0);
        for (int i = 0; i < PageWorkingCopy.MAX_CALLS; i++) {
            assertThat(copy.readTheme().block()).isSameAs(context);
        }
        assertThat(reads).hasValue(1);
        assertThat(copy.document()).isSameAs(document);
        assertThat(copy.summaries()).hasSize(PageWorkingCopy.MAX_CALLS);
        assertThat(JsonUtils.objectToJson(copy.summaries())).contains("get_theme_context")
            .doesNotContain("theme-source-marker", "--accent");
        assertThatThrownBy(() -> copy.readTheme().block()).isInstanceOf(IllegalStateException.class);
        assertThat(copy.exhausted()).isTrue();
    }

    @Test
    void themeOutputCountsTowardsExistingByteBudget() {
        var copy = new PageWorkingCopy(new PageDocument(1, "code", null, null, "", "", "Title", ""),
            new ContentPolicy());
        copy.consume("x".repeat(PageWorkingCopy.MAX_BYTES));
        assertThatThrownBy(() -> copy.readTheme().block()).isInstanceOf(IllegalStateException.class);
        assertThat(copy.exhausted()).isTrue();
        assertThat(copy.summaries()).isEmpty();
    }
}
