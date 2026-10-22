package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import run.halo.aipagebuilder.Json;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;
import run.halo.aipagebuilder.theme.ThemeContextExtractor;
import run.halo.aipagebuilder.theme.ThemeStyleContext;

class PageWorkingCopyTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repairingTitleOrContentDoesNotClearTheOtherValidationFailure(boolean invalidTitle) {
        var copy = copy("<p>Original</p>", "");
        if (invalidTitle) {
            copy.setTitle(Map.of("title", " ")).block();
            copy.update(Map.of("html", "<p>Changed</p>")).block();
            assertThat(copy.hasInvalidUpdate()).isTrue();
            copy.setTitle(Map.of("title", "Repaired")).block();
        } else {
            copy.update(Map.of("html", "<script>alert(1)</script>")).block();
            copy.setTitle(Map.of("title", "Changed")).block();
            assertThat(copy.hasInvalidUpdate()).isTrue();
            copy.update(Map.of("html", "<p>Repaired</p>")).block();
        }
        assertThat(copy.hasInvalidUpdate()).isFalse();
    }

    @Test
    void partialUpdatesPreserveOmittedFieldsAndEmptyInputDoesNotClearInvalidState() {
        var copy = copy("<p>old</p>", "p{color:red}");
        assertThat(copy.update(Map.of("html", "<p>new</p>")).block()).containsEntry("valid", true);
        assertThat(copy.document().css()).isEqualTo("p{color:red}");
        assertThat(copy.update(Map.of("css", "")).block()).containsEntry("valid", true);
        assertThat(copy.document().html()).isEqualTo("<p>new</p>");
        assertThat(copy.document().css()).isEmpty();
        assertThat(copy.update(Map.of()).block()).containsEntry("valid", false);
        assertThat(copy.hasInvalidUpdate()).isTrue();
        assertThat(copy.update(Map.of("unknown", "")).block()).containsEntry("valid", false);
        assertThat(copy.hasInvalidUpdate()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"update", "patch", "title", "description", "slug"})
    void pageToolAcknowledgementWaitsForDurableCheckpointAndCloseWaitsForWrite(String operation) {
        var copy = copy("<p>old</p>", "");
        var write = reactor.core.publisher.Sinks.<Void>empty();
        copy.checkpoint(write::asMono);
        var acknowledged = new java.util.concurrent.atomic.AtomicBoolean();
        var pending = edit(copy, operation);
        assertThat(copy.calls()).isZero();
        pending.subscribe(output -> acknowledged.set(true));
        assertThat(acknowledged).isFalse();
        var staged = copy.document();
        var slug = copy.slug();
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        copy.closeEdits().subscribe(ignored -> { }, error -> { }, () -> closed.set(true));
        assertThat(closed).isFalse();
        write.tryEmitEmpty();
        assertThat(acknowledged).isTrue();
        assertThat(closed).isTrue();
        assertThatThrownBy(() -> edit(copy, operation).block())
            .isInstanceOf(AiGenerationException.class);
        assertThat(copy.document()).isEqualTo(staged);
        assertThat(copy.slug()).isEqualTo(slug);
        assertThat(copy.calls()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"update", "patch", "title", "description", "slug"})
    void checkpointFailureRejectsQueuedAndLaterEditsWithoutRollingBackMemory(String operation) {
        var copy = copy("<p>old</p>", "");
        var write = reactor.core.publisher.Sinks.<Void>empty();
        copy.checkpoint(write::asMono);
        var first = edit(copy, operation).toFuture();
        var staged = copy.document();
        var slug = copy.slug();
        var queued = copy.setTitle(Map.of("title", "Queued title")).toFuture();
        assertThat(first).isNotDone();
        assertThat(queued).isNotDone();
        assertThat(copy.calls()).isEqualTo(1);

        var failure = new IllegalStateException("checkpoint unavailable");
        write.tryEmitError(failure);

        assertThatThrownBy(first::join).hasCause(failure);
        assertThatThrownBy(queued::join).hasCause(failure);
        assertThat(copy.checkpointFailed()).isTrue();
        assertThatThrownBy(() -> edit(copy, operation).block())
            .isInstanceOf(AiGenerationException.class);
        assertThat(copy.document()).isEqualTo(staged);
        assertThat(copy.slug()).isEqualTo(slug);
        assertThat(copy.calls()).isEqualTo(1);
    }

    @Test
    void queuedEditsWaitForCheckpointWithoutReplayingPreviousMutations() {
        var copy = copy("<p>old</p>", "");
        var write = reactor.core.publisher.Sinks.<Void>empty();
        var snapshots = new ArrayList<PageDocument>();
        var titles = new ArrayList<String>();
        copy.checkpoint(() -> {
            snapshots.add(copy.document());
            titles.add(copy.title());
            return snapshots.size() == 1 ? write.asMono() : Mono.empty();
        });
        var first = copy.update(Map.of("html", "<p>new</p>")).toFuture();
        var second = copy.setTitle(Map.of("title", "New title")).toFuture();
        assertThat(snapshots).hasSize(1);
        assertThat(copy.title()).isEqualTo("Title");
        assertThat(second).isNotDone();

        write.tryEmitEmpty();

        assertThat(first.join()).containsEntry("valid", true);
        assertThat(second.join()).containsEntry("valid", true);
        assertThat(titles).containsExactly("Title", "New title");
        assertThat(snapshots).allSatisfy(document ->
            assertThat(document.html()).isEqualTo("<p>new</p>"));
        assertThat(copy.calls()).isEqualTo(2);
    }

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
        copy.consume("x".repeat(RunBudget.MAX_BYTES
            - Json.toJson(input).getBytes(java.nio.charset.StandardCharsets.UTF_8).length - 1));
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
        assertThat(copy.update(Map.of("html", "<p>new</p>", "css", "")).block())
            .containsEntry("valid", true).containsEntry("staged", true)
            .containsEntry("baseHash", DocumentHash.of("<p>new</p>", ""));
        assertThat(copy.read()).containsEntry("baseHash", hash(copy));
    }

    @Test
    void slugLookupAndCheckpointKeepCancelledSubscribersAndLaterEditsInOrder() {
        var copy = copy("<p>old</p>", "");
        var lookup = reactor.core.publisher.Sinks.<Void>empty();
        var checked = new ArrayList<String>();
        var saved = new ArrayList<String>();
        copy.slugAvailability(slug -> {
            checked.add(slug);
            return "first".equals(slug) ? lookup.asMono() : Mono.empty();
        });
        copy.checkpoint(() -> Mono.fromRunnable(() -> saved.add(copy.slug())));

        copy.setSlug(Map.of("slug", "first")).subscribe().dispose();
        var second = copy.setSlug(Map.of("slug", "second")).toFuture();
        assertThat(checked).containsExactly("first");
        assertThat(saved).isEmpty();
        assertThat(second).isNotDone();
        lookup.tryEmitEmpty();

        assertThat(second.join()).containsEntry("valid", true);
        assertThat(checked).containsExactly("first", "second");
        assertThat(saved).containsExactly("first", "second");
        assertThat(copy.slug()).isEqualTo("second");
    }

    @Test
    void closingWhileSlugLookupIsPendingPreventsLateStaging() {
        var copy = copy("<p>old</p>", "");
        var lookup = reactor.core.publisher.Sinks.<Void>empty();
        var writes = new AtomicInteger();
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        copy.slugAvailability(slug -> lookup.asMono().doOnCancel(() -> cancelled.set(true)));
        copy.checkpoint(() -> Mono.fromRunnable(writes::incrementAndGet));
        var pending = copy.setSlug(Map.of("slug", "activity")).toFuture();
        var closing = copy.closeEdits().toFuture();
        assertThat(closing).isDone();
        assertThat(pending).isCompletedWithValue(null);
        assertThat(cancelled).isTrue();

        lookup.tryEmitEmpty();

        closing.join();
        assertThat(copy.slug()).isNull();
        assertThat(writes).hasValue(0);
    }

    @Test
    void slugLookupFailureIsDurablyInvalidAndAllowsLaterEditsAndRetry() {
        var copy = copy("<p>old</p>", "");
        copy.setSlug(Map.of("slug", "previous")).block();
        var writes = new AtomicInteger();
        copy.checkpoint(() -> Mono.fromRunnable(writes::incrementAndGet));
        copy.slugAvailability(slug -> Mono.error(new IllegalStateException("lookup unavailable")));

        assertThat(copy.setSlug(Map.of("slug", "activity")).block())
            .containsEntry("valid", false).containsEntry("staged", false);

        assertThat(copy.slug()).isEqualTo("previous");
        assertThat(writes).hasValue(1);
        assertThat(copy.hasInvalidSlugUpdate()).isTrue();
        assertThat(copy.checkpointFailed()).isFalse();
        assertThat(copy.update(Map.of("html", "<p>new</p>")).block())
            .containsEntry("valid", true);
        assertThat(copy.hasInvalidUpdate()).isTrue();

        copy.slugAvailability(slug -> Mono.empty());
        assertThat(copy.setSlug(Map.of("slug", "activity")).block())
            .containsEntry("valid", true);
        assertThat(copy.slug()).isEqualTo("activity");
        assertThat(copy.hasInvalidUpdate()).isFalse();
        assertThat(writes).hasValue(3);
    }

    @Test
    void semanticSlugIsStagedWithTitleAndContent() {
        var copy = copy("<p>old</p>", "");
        assertThat(copy.read()).containsEntry("slug", "").containsEntry("canSetSlug", true);
        assertThat(copy.setSlug(Map.of("slug", "  company/about-us  ")).block())
            .containsEntry("valid", true).containsEntry("staged", true);
        copy.setTitle(Map.of("title", "关于我们")).block();
        copy.update(Map.of("html", "<p>About us</p>", "css", "")).block();
        assertThat(copy.read()).containsEntry("slug", "company/about-us")
            .containsEntry("title", "关于我们");
        assertThat(copy.setSlug(Map.of("slug", "company/about-us")).block())
            .containsEntry("staged", false);
        assertThat(copy.hasInvalidUpdate()).isFalse();
    }

    @Test
    void invalidSlugCannotBeHiddenByContentOrTitleUpdates() {
        for (var slug : List.of("", "UPPER", "/about", "console/about", "a".repeat(201))) {
            var copy = copy("<p>old</p>", "");
            assertThat(copy.setSlug(Map.of("slug", slug)).block()).containsEntry("valid", false);
            assertThat(copy.slug()).isNull();
            copy.setTitle(Map.of("title", "About")).block();
            copy.update(Map.of("html", "<p>About</p>", "css", "")).block();
            assertThat(copy.hasInvalidUpdate()).isTrue();
            assertThat(copy.setSlug(Map.of("slug", "about")).block()).containsEntry("valid", true);
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
            assertThat(copy.setSlug(Map.of("slug", "about")).block()).containsEntry("valid", false);
            assertThat(copy.slug()).isEqualTo("custom-path");
        }
        var legacy = new PageWorkingCopy(document, new ContentPolicy(),
            Mono.just(ThemeStyleContext.unavailable()), null, true);
        assertThat(legacy.setSlug(Map.of("slug", "about")).block()).containsEntry("valid", false);
        assertThat(legacy.slug()).isNull();
    }

    @Test
    void setDescriptionStagesTrimmedSeoDescriptionAndReadExposesIt() {
        var copy = copy("<p>old</p>", "");
        assertThat(copy.read()).containsEntry("description", "");
        assertThat(copy.setDescription(Map.of("description", "  了解我们的公司历史与团队故事。  ")).block())
            .containsEntry("valid", true).containsEntry("staged", true);
        assertThat(copy.description()).isEqualTo("了解我们的公司历史与团队故事。");
        assertThat(copy.read()).containsEntry("description", "了解我们的公司历史与团队故事。");
        assertThat(copy.document().html()).isEqualTo("<p>old</p>");
        assertThat(copy.hasInvalidUpdate()).isFalse();
        assertThat(copy.update(Map.of("html", "<p>new</p>", "css", "")).block())
            .containsEntry("valid", true);
        assertThat(copy.description()).isEqualTo("了解我们的公司历史与团队故事。");
        assertThat(copy.summaries()).extracting(ChatApi.Tool::name)
            .filteredOn(name -> !"read_current_page".equals(name))
            .containsExactly("update_page_description", "update_current_page");
    }

    @Test
    void invalidDescriptionIsRejectedWithoutBlockingLaterContentUpdates() {
        var copy = copy("<p>old</p>", "");
        List<Map<String, Object>> invalidInputs = List.of(
            Map.<String, Object>of(),
            Map.<String, Object>of("description", "   "),
            Map.<String, Object>of("description", "x".repeat(1001)),
            Map.<String, Object>of("description", 1));
        for (var input : invalidInputs) {
            assertThat(copy.setDescription(input).block()).containsEntry("valid", false)
                .containsEntry("staged", false);
            assertThat(copy.description()).isEmpty();
            assertThat(copy.hasInvalidUpdate()).isFalse();
        }
        assertThat(copy.update(Map.of("html", "<p>new</p>", "css", "")).block())
            .containsEntry("valid", true);
        assertThat(copy.setDescription(Map.of("description", "关于我们的团队与业务。")).block())
            .containsEntry("valid", true).containsEntry("staged", true);
        assertThat(copy.hasInvalidUpdate()).isFalse();
    }

    @Test
    void existingDescriptionRequiresExplicitReplacement() {
        var copy = copy("<p>old</p>", "");
        copy.description("用户手写的描述");
        copy.existingDescription("用户手写的描述");
        assertThat(copy.setDescription(Map.of("description", "AI 想覆盖的描述")).block())
            .containsEntry("valid", false).containsEntry("staged", false);
        assertThat(copy.description()).isEqualTo("用户手写的描述");
        assertThat(copy.hasInvalidUpdate()).isFalse();
        assertThat(copy.setDescription(Map.of("description", "用户要求的新描述",
            "replaceExisting", true)).block())
            .containsEntry("valid", true).containsEntry("staged", true);
        assertThat(copy.description()).isEqualTo("用户要求的新描述");
    }

    @Test
    void setTitleStagesTrimmedTitleAndSurvivesLaterContentUpdates() {
        var copy = copy("<p>old</p>", "");
        assertThat(copy.setTitle(Map.of("title", "  新标题  ")).block())
            .containsEntry("valid", true).containsEntry("staged", true);
        assertThat(copy.title()).isEqualTo("新标题");
        assertThat(copy.document().html()).isEqualTo("<p>old</p>");
        assertThat(copy.hasInvalidUpdate()).isFalse();
        assertThat(copy.update(Map.of("html", "<p>new</p>", "css", "")).block())
            .containsEntry("valid", true);
        assertThat(copy.title()).isEqualTo("新标题");
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
            assertThat(copy.setTitle(input).block()).containsEntry("valid", false)
                .containsEntry("staged", false);
            assertThat(copy.document()).isSameAs(original);
            assertThat(copy.hasInvalidUpdate()).isTrue();
        }
        assertThat(copy.setTitle(Map.of("title", "恢复")).block())
            .containsEntry("valid", true).containsEntry("staged", true);
        assertThat(copy.hasInvalidUpdate()).isFalse();
        assertThat(copy.setTitle(Map.of("title", "恢复")).block())
            .containsEntry("valid", true).containsEntry("staged", false);
        assertThat(copy.title()).isEqualTo("恢复");
    }

    @Test
    void patchesUseOriginalPositionsAndApplyInDescendingOrderAcrossFields() {
        var copy = copy("<p>one two three</p>", "p{color:red}");
        var result = copy.patch(Map.of("baseHash", hash(copy), "patches", List.of(
            patch("html", "one", "a much longer first value"),
            patch("css", "red", "blue"),
            patch("html", "three", ""),
            patch("html", "two", "one")))).block();
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
            assertThat(copy.patch(input).block()).containsEntry("valid", false)
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
            assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches", patches)).block())
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
                List.of(patch("html", oldText, "new")))).block())
                .containsEntry("valid", false);
            assertThat(copy.document()).isSameAs(original);
        }
        var repeated = copy("<p>old old</p>", "");
        assertThat(repeated.patch(Map.of("baseHash", hash(repeated), "patches",
            List.of(patch("html", "old", "new")))).block())
            .containsEntry("valid", false);
    }

    @Test
    void intersectingAndIdenticalRangesAreRejectedButAdjacentRangesAreAllowed() {
        for (var second : List.of("bc", "ab")) {
            var copy = copy("<p>abc</p>", "");
            var original = copy.document();
            assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches", List.of(
                patch("html", "ab", "X"), patch("html", second, "Y")))).block())
                .containsEntry("valid", false);
            assertThat(copy.document()).isSameAs(original);
        }
        var adjacent = copy("<p>abc</p>", "");
        assertThat(adjacent.patch(Map.of("baseHash", hash(adjacent), "patches", List.of(
            patch("html", "a", "X"), patch("html", "bc", "Y")))).block())
            .containsEntry("valid", true);
        assertThat(adjacent.document().html()).isEqualTo("<p>XY</p>");
    }

    @Test
    void laterPatchCannotMatchTextIntroducedByEarlierPatch() {
        var copy = copy("<p>old</p>", "");
        var original = copy.document();
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches", List.of(
            patch("html", "old", "new"), patch("html", "new", "latest")))).block())
            .containsEntry("valid", false);
        assertThat(copy.document()).isSameAs(original);
    }

    @Test
    void wholeCandidateValidationIsAtomicAndInvalidStateIsStickyUntilSuccess() {
        var copy = copy("<p>old</p>", "p{color:red}");
        var original = copy.document();
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches", List.of(
            patch("html", "old", "new"), patch("css", "red", "expression(alert(1))")))).block())
            .containsEntry("valid", false);
        assertThat(copy.document()).isSameAs(original);
        assertThat(copy.hasInvalidUpdate()).isTrue();
        copy.read();
        copy.readTheme().block();
        assertThat(copy.hasInvalidUpdate()).isTrue();
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches",
            List.of(patch("html", "old", "new")))).block())
            .containsEntry("valid", true);
        assertThat(copy.hasInvalidUpdate()).isFalse();
        var staged = copy.document();
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches",
            List.of(patch("html", "new", "<script>bad</script>")))).block())
            .containsEntry("valid", false);
        assertThat(copy.document()).isSameAs(staged);
        assertThat(copy.hasInvalidUpdate()).isTrue();
        assertThat(copy.update(Map.of("html", "<p>recovered</p>", "css", "p{color:red}")).block())
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
                        List.of(patch("html", "<p>old</p>", html)))).block()
                    : copy.update(Map.of("html", html, "css", "")).block();
                assertThat(result).containsEntry("valid", false).containsEntry("staged", false)
                    .containsEntry("baseHash", originalHash);
                assertThat((String) result.get("guidance"))
                    .contains("ask_user", "do not try equivalent workarounds", "not staged");
                assertThat(copy.document()).isSameAs(original);
                assertThat(copy.hasInvalidUpdate()).isTrue();
                assertThat(copy.update(Map.of("html", "<button type=\"button\">All</button>", "css", "")).block())
                    .containsEntry("valid", true).containsEntry("staged", true);
                assertThat(copy.hasInvalidUpdate()).isFalse();
            }
        }
    }

    @Test
    void successfulPatchInvalidatesOldBaseHash() {
        var copy = copy("<p>old</p>", "");
        var oldHash = hash(copy);
        copy.patch(Map.of("baseHash", oldHash, "patches", List.of(patch("html", "old", "new")))).block();
        assertThat(copy.patch(Map.of("baseHash", oldHash, "patches",
            List.of(patch("html", "new", "latest")))).block())
            .containsEntry("valid", false).containsEntry("baseHash", hash(copy));
        assertThat(copy.document().html()).isEqualTo("<p>new</p>");
    }

    @Test
    void noOpDoesNotNormalizeLegacyDocumentAndCanClearInvalidState() {
        var document = new PageDocument(1, "visual", "legacy", Map.of(), "<p>old</p>", "");
        var copy = new PageWorkingCopy(document, new ContentPolicy());
        copy.update(Map.of("html", "<script>bad</script>", "css", "")).block();
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches",
            List.of(patch("html", "old", "old")))).block())
            .containsEntry("valid", true).containsEntry("staged", false)
            .containsEntry("baseHash", hash(copy));
        assertThat(copy.document()).isSameAs(document);
        assertThat(copy.hasInvalidUpdate()).isFalse();
        assertThat(copy.update(Map.of("html", document.html(), "css", document.css())).block())
            .containsEntry("valid", true).containsEntry("staged", false);
        assertThat(copy.document()).isSameAs(document);
    }

    @Test
    void patchReusesCallAndByteBudgetsWithoutPartialStaging() {
        var copy = copy("<p>old</p>", "");
        for (int i = 0; i < RunBudget.MAX_CALLS; i++) {
            copy.patch(Map.of()).block();
        }
        assertThatThrownBy(() -> copy.patch(Map.of("baseHash", hash(copy), "patches",
            List.of(patch("html", "old", "new")))).block())
            .isInstanceOf(IllegalStateException.class);
        assertThat(copy.exhausted()).isTrue();
        assertThat(copy.document().html()).isEqualTo("<p>old</p>");
        var bytes = copy("<p>old</p>", "");
        bytes.consume("x".repeat(RunBudget.MAX_BYTES));
        assertThatThrownBy(() -> bytes.patch(Map.of("baseHash", hash(bytes), "patches",
            List.of(patch("html", "old", "new")))).block())
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
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches", patches)).block())
            .containsEntry("valid", true);
        assertThat(copy.document().html()).isEqualTo("<p>" + "x".repeat(20) + "</p>");
    }

    @Test
    void changedCssPreparesThemeWhileTextOnlyPatchPreservesLegacyCss() {
        var css = "@media (prefers-color-scheme:dark){p{color:red}}";
        var context = new ThemeStyleContext("sample", "Sample", "SUPPORTED", List.of(), List.of(), "",
            new ThemeStyleContext.ColorMode("explicit", List.of(), List.of()));
        var copy = new PageWorkingCopy(
            new PageDocument(1, "code", null, null, "<p>old</p>", css),
            new ContentPolicy(), Mono.just(context));
        var original = copy.document();
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches", List.of(
            patch("html", "old", "new"), patch("css", "red", "blue")))).block())
            .containsEntry("valid", false);
        assertThat(copy.document()).isSameAs(original);
        assertThat(copy.hasInvalidUpdate()).isTrue();
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches",
            List.of(patch("html", "old", "new")))).block())
            .containsEntry("valid", true).containsEntry("staged", true);
        assertThat(copy.document().css()).isEqualTo(css);
        assertThat(copy.hasInvalidUpdate()).isFalse();
        assertThat(copy.update(Map.of("html", "<p>new</p>", "css", css.replace("red", "blue"))).block())
            .containsEntry("valid", false);
        assertThat(copy.document().css()).isEqualTo(css);
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches",
            List.of(patch("css", css, "p{color:blue}")))).block())
            .containsEntry("valid", true);
        assertThat(copy.hasInvalidUpdate()).isFalse();
    }

    @Test
    void editsPrepareThemeOnceWithoutChargingItsContentOrAnExtraToolCall() {
        var reads = new AtomicInteger();
        var context = new ThemeStyleContext("sample", "Sample", "SUPPORTED", List.of(), List.of(),
            "x".repeat(RunBudget.MAX_BYTES),
            new ThemeStyleContext.ColorMode("explicit", List.of(), List.of()));
        var copy = new PageWorkingCopy(
            new PageDocument(1, "code", null, null, "<p>old</p>", "p{color:red}"),
            new ContentPolicy(), Mono.fromSupplier(() -> {
                reads.incrementAndGet();
                return context;
            }));
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches", List.of(
            patch("css", "p{color:red}", "@media(prefers-color-scheme:dark){p{color:blue}}")))).block())
            .containsEntry("valid", false);
        assertThat(copy.document().css()).isEqualTo("p{color:red}");
        for (int i = 1; i < RunBudget.MAX_CALLS; i++) {
            assertThat(copy.update(Map.of("html", "<p>new</p>")).block())
                .containsEntry("valid", true);
        }
        assertThat(reads).hasValue(1);
        assertThat(copy.summaries()).hasSize(RunBudget.MAX_CALLS)
            .extracting(ChatApi.Tool::name).doesNotContain("get_theme_context");
        assertThat(copy.exhausted()).isFalse();
        assertThatThrownBy(() -> copy.update(Map.of("html", "<p>late</p>")).block())
            .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"update", "patch"})
    void themeFailureDoesNotMutateOrPreventIndependentTitleAndPathEdits(String operation) {
        var reads = new AtomicInteger();
        var copy = new PageWorkingCopy(
            new PageDocument(1, "code", null, null, "<p>old</p>", "p{color:red}"),
            new ContentPolicy(), Mono.defer(() -> {
                reads.incrementAndGet();
                return Mono.error(new IllegalStateException("theme unavailable"));
            }));
        copy.setTitle(Map.of("title", "Before theme failure")).block();
        copy.setSlug(Map.of("slug", "before")).block();
        assertThat(reads).hasValue(0);
        var writes = new AtomicInteger();
        copy.checkpoint(() -> Mono.fromRunnable(writes::incrementAndGet));
        var original = copy.document();
        assertThatThrownBy(() -> edit(copy, operation).block()).hasMessage("theme unavailable");
        assertThat(copy.document()).isSameAs(original);
        assertThat(copy.checkpointFailed()).isFalse();
        assertThat(writes).hasValue(0);
        copy.setTitle(Map.of("title", "After theme failure")).block();
        copy.setSlug(Map.of("slug", "after")).block();
        assertThat(copy.title()).isEqualTo("After theme failure");
        assertThat(copy.slug()).isEqualTo("after");
        assertThat(writes).hasValue(2);
        assertThatThrownBy(() -> edit(copy, operation).block()).hasMessage("theme unavailable");
        assertThat(reads).hasValue(1);
        assertThat(copy.calls()).isEqualTo(4);
    }

    @Test
    void emptyThemeStillStagesSourceAndWaitsForCheckpoint() {
        var copy = new PageWorkingCopy(
            new PageDocument(1, "code", null, null, "<p>old</p>", ""),
            new ContentPolicy(), Mono.empty());
        var write = reactor.core.publisher.Sinks.<Void>empty();
        copy.checkpoint(write::asMono);
        var pending = copy.update(Map.of("css", "p{color:red}")).toFuture();
        assertThat(copy.document().css()).isEqualTo("p{color:red}");
        assertThat(pending).isNotDone();
        write.tryEmitEmpty();
        assertThat(pending.join()).containsEntry("valid", true).containsEntry("staged", true);
    }

    @Test
    void patchShapeErrorsAndRejectedContentKeepDistinctGuidance() {
        var copy = copy("<p>old</p>", "");
        var original = copy.document();
        assertThat(copy.patch(Map.of("baseHash", "stale", "patches",
            List.of(patch("html", "old", "new")))).block())
            .containsEntry("valid", false).doesNotContainKey("guidance");
        assertThat(copy.patch(Map.of("baseHash", hash(copy), "patches",
            List.of(patch("html", "old", "<script>bad</script>")))).block())
            .containsEntry("valid", false).containsKey("guidance");
        assertThat(copy.document()).isSameAs(original);
        assertThat(copy.hasInvalidUpdate()).isTrue();
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
        var copy = new PageWorkingCopy(new PageDocument(1, "code", null, null, html, css),
            new ContentPolicy());
        copy.title("Title");
        return copy;
    }

    private static Mono<Map<String, Object>> edit(PageWorkingCopy copy, String operation) {
        return switch (operation) {
            case "update" -> copy.update(Map.of("html", "<p>new</p>"));
            case "patch" -> copy.patch(Map.of("baseHash", hash(copy), "patches",
                List.of(patch("html", "old", "new"))));
            case "title" -> copy.setTitle(Map.of("title", "New title"));
            case "description" -> copy.setDescription(Map.of("description", "New description"));
            case "slug" -> copy.setSlug(Map.of("slug", "new-path"));
            default -> throw new IllegalArgumentException(operation);
        };
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
        var document = new PageDocument(1, "code", null, null, "<p>old</p>", "");
        var copy = new PageWorkingCopy(document, new ContentPolicy(), Mono.fromSupplier(() -> {
            reads.incrementAndGet();
            return context;
        }));
        assertThat(reads).hasValue(0);
        for (int i = 0; i < RunBudget.MAX_CALLS; i++) {
            assertThat(copy.readTheme().block()).isSameAs(context);
        }
        assertThat(reads).hasValue(1);
        assertThat(copy.document()).isSameAs(document);
        assertThat(copy.summaries()).hasSize(RunBudget.MAX_CALLS);
        assertThat(Json.toJson(copy.summaries())).contains("get_theme_context")
            .doesNotContain("theme-source-marker", "--accent");
        assertThatThrownBy(() -> copy.readTheme().block()).isInstanceOf(IllegalStateException.class);
        assertThat(copy.exhausted()).isTrue();
    }

    @Test
    void themeOutputCountsTowardsExistingByteBudget() {
        var copy = new PageWorkingCopy(new PageDocument(1, "code", null, null, "", ""),
            new ContentPolicy());
        copy.consume("x".repeat(RunBudget.MAX_BYTES));
        assertThatThrownBy(() -> copy.readTheme().block()).isInstanceOf(IllegalStateException.class);
        assertThat(copy.exhausted()).isTrue();
        assertThat(copy.summaries()).isEmpty();
    }
}
