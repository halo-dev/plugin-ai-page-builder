package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import run.halo.aifoundation.part.PartType;
import run.halo.aipagebuilder.content.ContentPolicy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.AiModelService;
import run.halo.aifoundation.chat.FinishReason;
import run.halo.aifoundation.chat.GenerateTextRequest;
import run.halo.aifoundation.chat.GenerateTextResult;
import run.halo.aifoundation.chat.LanguageModel;
import run.halo.aifoundation.chat.StreamTextResult;
import run.halo.aifoundation.exception.DefaultModelNotConfiguredException;
import run.halo.aifoundation.exception.StructuredOutputValidationException;
import run.halo.aifoundation.part.TextStreamPart;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

class FoundationBridgeTest {
    private final ExtensionGetter extensions = mock(ExtensionGetter.class);
    private final AiModelService service = mock(AiModelService.class);
    private final LanguageModel model = mock(LanguageModel.class);
    private final FoundationBridge bridge = new FoundationBridge(extensions);

    @Test
    void resolvesEnabledServiceForEveryCallIncludingReenable() {
        when(extensions.getEnabledExtension(AiModelService.class))
            .thenReturn(Mono.just(service), Mono.empty(), Mono.just(service));
        assertThat(bridge.available().block()).isTrue();
        assertThat(bridge.available().block()).isFalse();
        assertThat(bridge.available().block()).isTrue();
    }

    @Test
    void reviewUsesDefaultModelAndConvertsStructuredOutput() {
        when(extensions.getEnabledExtension(AiModelService.class)).thenReturn(Mono.just(service));
        when(service.languageModel()).thenReturn(Mono.just(model));
        var captured = new AtomicReference<GenerateTextRequest>();
        when(model.generateText(any(GenerateTextRequest.class))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            var result = new GenerateTextResult();
            result.setFinishReason(FinishReason.STOP);
            result.setOutput(java.util.Map.of("verdict", "SUSPICIOUS", "summary", "发现外部请求",
                "findings", java.util.List.of(java.util.Map.of("severity", "HIGH",
                    "category", "DATA_EXFILTRATION", "file", "script.js",
                    "evidence", "fetch('https://example.com')", "explanation", "向外部发送数据"))));
            return Mono.just(result);
        });

        var review = bridge.review("system", "prompt").block();

        assertThat(review.verdict()).isEqualTo(AiReview.Verdict.SUSPICIOUS);
        assertThat(review.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(AiReview.Severity.HIGH);
            assertThat(finding.category()).isEqualTo(AiReview.Category.DATA_EXFILTRATION);
            assertThat(finding.file()).isEqualTo("script.js");
        });
        assertThat(captured.get().getSystem()).isEqualTo("system");
        assertThat(captured.get().getPrompt()).isEqualTo("prompt");
        assertThat(captured.get().getOutput().getOutputClass()).isEqualTo(AiReview.class);
        assertThat(captured.get().getTemperature()).isEqualTo(0.1);
        assertThat(captured.get().getMaxOutputTokens()).isEqualTo(3000);
        assertThat(captured.get().getMaxRetries()).isEqualTo(1);
        org.mockito.Mockito.verify(service).languageModel();
    }

    @Test
    void reviewUsesSelectedModelWhenProvided() {
        when(extensions.getEnabledExtension(AiModelService.class)).thenReturn(Mono.just(service));
        when(service.languageModel("model-a")).thenReturn(Mono.empty());

        assertThatThrownBy(() -> bridge.review("model-a", "system", "prompt").block())
            .isInstanceOfSatisfying(AiReviewException.class, error -> assertThat(error.reason())
                .isEqualTo(AiReviewException.Reason.UNAVAILABLE));

        org.mockito.Mockito.verify(service).languageModel("model-a");
        org.mockito.Mockito.verify(service, never()).languageModel();
    }

    @Test
    void reviewFailsClosedWhenOutputIsNotUsable() {
        when(extensions.getEnabledExtension(AiModelService.class)).thenReturn(Mono.just(service));
        when(service.languageModel()).thenReturn(Mono.just(model));
        when(model.generateText(any(GenerateTextRequest.class))).thenAnswer(invocation -> {
            var result = new GenerateTextResult();
            result.setFinishReason(FinishReason.LENGTH);
            result.setOutput(java.util.Map.of("verdict", "SAFE", "summary", "",
                "findings", java.util.List.of()));
            return Mono.just(result);
        });
        assertThatThrownBy(() -> bridge.review("s", "p").block())
            .isInstanceOfSatisfying(AiReviewException.class, error -> assertThat(error.reason())
                .isEqualTo(AiReviewException.Reason.INVALID_OUTPUT));

        when(model.generateText(any(GenerateTextRequest.class))).thenReturn(Mono.error(
            new StructuredOutputValidationException("private-output")));
        assertThatThrownBy(() -> bridge.review("s", "p").block())
            .isInstanceOfSatisfying(AiReviewException.class, error -> assertThat(error.reason())
                .isEqualTo(AiReviewException.Reason.INVALID_OUTPUT))
            .hasMessageNotContaining("private-output");

        when(model.generateText(any(GenerateTextRequest.class))).thenReturn(Mono.error(
            new DefaultModelNotConfiguredException("private-provider")));
        assertThatThrownBy(() -> bridge.review("s", "p").block())
            .isInstanceOfSatisfying(AiReviewException.class, error -> assertThat(error.reason())
                .isEqualTo(AiReviewException.Reason.UNAVAILABLE))
            .hasMessageNotContaining("private-provider");
    }

    @Test
    void reviewRequiresAnEnabledServiceAndDefaultModel() {
        when(extensions.getEnabledExtension(AiModelService.class)).thenReturn(Mono.empty());
        assertThatThrownBy(() -> bridge.review("s", "p").block())
            .isInstanceOfSatisfying(AiReviewException.class, error -> assertThat(error.reason())
                .isEqualTo(AiReviewException.Reason.UNAVAILABLE));

        when(extensions.getEnabledExtension(AiModelService.class)).thenReturn(Mono.just(service));
        when(service.languageModel()).thenReturn(Mono.empty());
        assertThatThrownBy(() -> bridge.review("s", "p").block())
            .isInstanceOfSatisfying(AiReviewException.class, error -> assertThat(error.reason())
                .isEqualTo(AiReviewException.Reason.UNAVAILABLE));
    }

    @Test
    void reviewTimesOutAsTimeoutReason() {
        when(extensions.getEnabledExtension(AiModelService.class)).thenReturn(Mono.just(service));
        when(service.languageModel()).thenReturn(Mono.just(model));
        when(model.generateText(any(GenerateTextRequest.class))).thenReturn(Mono.never());
        reactor.test.StepVerifier.withVirtualTime(() -> bridge.review("s", "p"))
            .expectSubscription().thenAwait(java.time.Duration.ofSeconds(120))
            .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(
                AiReviewException.class, known -> assertThat(known.reason())
                    .isEqualTo(AiReviewException.Reason.TIMEOUT)))
            .verify(java.time.Duration.ofSeconds(5));
    }

    @Test
    void chatUsesServerToolsAndSdkChunksButWithholdsFinalFinish() {
        var request = setup(Flux.just(TextStreamPart.textDelta("t", "done"),
            TextStreamPart.finish(FinishReason.STOP, "stop", null)));
        var copy = new PageWorkingCopy(new run.halo.aipagebuilder.model.PageDocument(1, "code",
            null, null, "<p>old</p>", "", "Title", ""), new ContentPolicy());
        var chunks = bridge.chat("model", java.util.List.of(), "change", copy).collectList().block();
        assertThat(chunks).extracting(value -> value.get("type")).containsExactly("text-delta");
        assertThat(request.get().getTools()).extracting(tool -> tool.getName())
            .containsExactly("get_theme_context", "read_current_page", "set_page_slug", "update_page_title", "patch_current_page", "update_current_page",
                "ask_user", "list_theme_files", "read_theme_file", "list_posts", "list_single_pages",
                "list_ai_pages", "read_ai_page", "get_site_profile", "get_navigation", "read_content", "search_attachments",
                "fetch_webpage", "search_public_apis", "get_public_api");
        assertThat(request.get().getTools()).allSatisfy(tool -> assertThat(tool.getExecutor()).isNotNull());
        assertThat(request.get().getStopWhen()).isNotNull();
        assertThat(request.get().getMaxRetries()).isZero();
        setup(Flux.just(TextStreamPart.finish(FinishReason.TOOL_CALLS, "tool_calls", null)));
        assertThatThrownBy(() -> bridge.chat("model", java.util.List.of(), "change", copy).blockLast())
            .isInstanceOf(AiGenerationException.class);
        setup(Flux.empty());
        assertThatThrownBy(() -> bridge.chat("model", java.util.List.of(), "change", copy).blockLast())
            .isInstanceOf(AiGenerationException.class);
    }

    @Test
    void executesSemanticPathToolAgainstTheCurrentWorkingCopy() {
        var captured = setup(Flux.just(TextStreamPart.finish(FinishReason.STOP, "stop", null)));
        var copy = copy();
        bridge.chat("model", java.util.List.of(), "公司介绍页", copy).blockLast();
        var tool = captured.get().getTools().stream()
            .filter(item -> "set_page_slug".equals(item.getName())).findFirst().orElseThrow();
        var stored = reactor.core.publisher.Sinks.<Void>empty();
        copy.checkpoint(stored::asMono);
        var result = tool.getExecutor().execute(run.halo.aifoundation.tool.ToolExecutionContext.builder()
            .input(java.util.Map.of("slug", "company/about")).build()).toFuture();
        assertThat(result).isNotDone();
        stored.tryEmitEmpty();
        assertThat(result.join()).isEqualTo(java.util.Map.of("valid", true, "staged", true,
            "slug", "company/about"));
        assertThat(copy.slug()).isEqualTo("company/about");
        assertThat(captured.get().getSystem()).contains("canSetSlug=true", "never use random");
    }

    @Test
    void executesPublicApiToolsThroughExistingReferenceBudget() {
        var captured = setup(Flux.just(TextStreamPart.finish(FinishReason.STOP, "stop", null)));
        var session = mock(PageReferenceTools.Session.class);
        var copy = copy();
        copy.references(session);
        var before = copy.document();
        bridge.chat("model", java.util.List.of(), "最新文章", copy).blockLast();
        for (var name : java.util.List.of("search_public_apis", "get_public_api")) {
            var arguments = java.util.Map.<String, Object>of("query", "posts");
            var expected = java.util.Map.<String, Object>of("items", java.util.List.of());
            when(session.execute(name, arguments)).thenReturn(Mono.just(expected));
            var tool = captured.get().getTools().stream().filter(item -> name.equals(item.getName()))
                .findFirst().orElseThrow();
            var result = tool.getExecutor().execute(run.halo.aifoundation.tool.ToolExecutionContext.builder()
                .input(arguments).build()).block();
            assertThat(result).isEqualTo(expected);
        }
        assertThat(copy.document()).isEqualTo(before);
        assertThat(copy.summaries()).extracting(ChatApi.Tool::name)
            .contains("search_public_apis", "get_public_api");
        assertThat(captured.get().getSystem()).contains("credentials: 'omit'", "get_public_api");
    }

    @Test
    void injectsBoundMemoryAndRegistersItsToolWithoutChangingPageState() {
        var request = setup(Flux.just(TextStreamPart.finish(FinishReason.STOP, "stop", null)));
        var tools = new PageMemoryTools(mock(run.halo.aipagebuilder.memory.SiteMemoryService.class),
            PageMemoryToolsTest.snapshot(1),
            new run.halo.aipagebuilder.memory.SiteMemoryService.SourceContext("p", "s", "r", "admin"));
        tools.prompt("公司电话 0571-12345678");
        var copy = copy();
        copy.memory(tools);
        bridge.chat("model", java.util.List.of(), "公司电话 0571-12345678", copy).blockLast();
        assertThat(request.get().getTools()).extracting(tool -> tool.getName()).contains("update_site_memory");
        assertThat(run.halo.app.infra.utils.JsonUtils.objectToJson(request.get().getMessages()))
            .contains("currentRunSources", "r/prompt", "0571-12345678");
        assertThat(request.get().getMessages().getLast().getRole().toString()).isEqualTo("USER");
        assertThat(request.get().getSystem())
            .contains("continue deleting in batches", "add and update are forbidden",
                "A batch containing delete must contain only delete operations")
            .doesNotContain("delete closes memory writes");
        assertThat(copy.hasInvalidUpdate()).isFalse();
    }

    @Test
    void unavailableMemoryAlsoKeepsTheFinalMessageAsUser() {
        var request = setup(Flux.just(TextStreamPart.finish(FinishReason.STOP, "stop", null)));
        var copy = copy();
        copy.memoryUnavailable();
        bridge.chat("model", java.util.List.of(), "生成页面", copy).blockLast();
        assertThat(request.get().getMessages().getLast().getRole().toString()).isEqualTo("USER");
        assertThat(request.get().getMessages().toString()).contains("could not be loaded", "生成页面");
    }

    @Test
    void silentProviderTimesOutAndCancelsWithoutSdkTimeoutRace() {
        var request = setup(Flux.never());
        reactor.test.StepVerifier.withVirtualTime(() -> bridge.chat("model", java.util.List.of(), "change", copy()))
            .expectSubscription().expectNoEvent(java.time.Duration.ofSeconds(119))
            .thenAwait(java.time.Duration.ofSeconds(1))
            .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(AiGenerationException.class,
                known -> assertThat(known.reason()).isEqualTo(AiGenerationException.Reason.RESPONSE_TIMEOUT)))
            .verify(java.time.Duration.ofSeconds(5));
        assertThat(request.get().getTimeouts()).isNull();
        assertThat(request.get().getCancellationToken().isCancellationRequested()).isTrue();
    }

    @Test
    void registeredAskUserExecutorWaitsAndReturnsStructuredAnswerBeforeContinuation() {
        var clock = reactor.test.scheduler.VirtualTimeScheduler.getOrSet();
        try {
            var captured = new AtomicReference<GenerateTextRequest>();
            var answer = reactor.core.publisher.Sinks.<ChatApi.Question>one();
            var copy = copy();
            copy.questions(question -> {
                copy.timeBudget.pause();
                return answer.asMono().doOnNext(ignored -> copy.timeBudget.resume());
            });
            setup(Flux.empty());
            when(model.streamText(any())).thenAnswer(invocation -> {
                captured.set(invocation.getArgument(0));
                var tool = captured.get().getTools().stream().filter(t -> t.getName().equals("ask_user"))
                    .findFirst().orElseThrow();
                var source = tool.getExecutor().execute(run.halo.aifoundation.tool.ToolExecutionContext.builder()
                    .input(java.util.Map.of("questions", java.util.List.of(java.util.Map.of(
                        "id", "text", "title", "文案", "options", java.util.List.of(), "allowCustomAnswer", true))))
                    .build()).doOnNext(result -> assertThat(result).isInstanceOf(ChatApi.Question.class))
                    .thenMany(Flux.just(TextStreamPart.textDelta("t", "continued"),
                        TextStreamPart.finish(FinishReason.STOP, "stop", null)));
                return new StreamTextResult(source, Flux.empty(), Flux.empty(), Flux.empty(),
                    Mono.empty(), Mono.empty());
            });
            var chunks = new java.util.ArrayList<java.util.Map<String, Object>>();
            var error = new AtomicReference<Throwable>();
            bridge.chat("model", java.util.List.of(), "change", copy).subscribe(chunks::add, error::set);
            clock.advanceTimeBy(java.time.Duration.ofMinutes(20));
            assertThat(error.get()).isNull();
            assertThat(chunks).isEmpty();
            answer.tryEmitValue(new ChatApi.Question("batch", java.util.List.of(), java.util.List.of(),
                java.time.Instant.now(), java.time.Instant.now()));
            assertThat(chunks).extracting(chunk -> chunk.get("type")).containsExactly("text-delta");
            assertThat(error.get()).isNull();
            assertThat(captured.get().getCancellationToken().isCancellationRequested()).isTrue();
        } finally {
            reactor.test.scheduler.VirtualTimeScheduler.reset();
        }
    }

    @Test
    void responseDeadlinePausesForUserAndResumesWithRemainingTime() {
        var clock = reactor.test.scheduler.VirtualTimeScheduler.getOrSet();
        try {
            setup(Flux.never());
            var copy = copy();
            var error = new AtomicReference<Throwable>();
            var subscription = bridge.chat("model", java.util.List.of(), "change", copy)
                .subscribe(ignored -> { }, error::set);
            clock.advanceTimeBy(java.time.Duration.ofSeconds(50));
            copy.timeBudget.pause();
            clock.advanceTimeBy(java.time.Duration.ofMinutes(20));
            assertThat(error.get()).isNull();
            copy.timeBudget.resume();
            clock.advanceTimeBy(java.time.Duration.ofSeconds(69));
            assertThat(error.get()).isNull();
            clock.advanceTimeBy(java.time.Duration.ofSeconds(1));
            assertThat(error.get()).isInstanceOfSatisfying(AiGenerationException.class,
                known -> assertThat(known.reason()).isEqualTo(AiGenerationException.Reason.RESPONSE_TIMEOUT));
            subscription.dispose();
        } finally {
            reactor.test.scheduler.VirtualTimeScheduler.reset();
        }
    }

    @Test
    void upstreamTimeoutRemainsDistinctAndDoesNotExposeProviderDetails() {
        setup(Flux.error(new run.halo.aifoundation.exception.AiGenerationTimeoutException(
            "provider", "private-provider-detail")));
        assertThatThrownBy(() -> bridge.chat("model", java.util.List.of(), "change", copy()).blockLast())
            .isInstanceOfSatisfying(AiGenerationException.class,
                error -> assertThat(error.reason()).isEqualTo(AiGenerationException.Reason.TIMEOUT))
            .hasMessageNotContaining("private-provider-detail");
    }

    @Test
    void everyProviderEventResetsTheResponseDeadline() {
        setup(Flux.defer(() -> Flux.concat(
            Mono.delay(java.time.Duration.ofSeconds(90)).map(tick -> TextStreamPart.textDelta("t", "one")),
            Mono.delay(java.time.Duration.ofSeconds(90)).map(tick -> TextStreamPart.reasoningDelta("r", "two", null)),
            Mono.delay(java.time.Duration.ofSeconds(90)).map(tick -> TextStreamPart.textDelta("t", "three")),
            Mono.just(TextStreamPart.finish(FinishReason.STOP, "stop", null)))));
        reactor.test.StepVerifier.withVirtualTime(() -> bridge.chat("model", java.util.List.of(), "change", copy()))
            .expectSubscription().thenAwait(java.time.Duration.ofSeconds(270))
            .expectNextCount(3).expectComplete().verify(java.time.Duration.ofSeconds(5));
        setup(Flux.defer(() -> Flux.concat(
            Mono.delay(java.time.Duration.ofSeconds(90)).map(tick -> TextStreamPart.textDelta("t", "one")),
            Flux.never())));
        reactor.test.StepVerifier.withVirtualTime(() -> bridge.chat("model", java.util.List.of(), "change", copy()))
            .expectSubscription().thenAwait(java.time.Duration.ofSeconds(90)).expectNextCount(1)
            .expectNoEvent(java.time.Duration.ofSeconds(119)).thenAwait(java.time.Duration.ofSeconds(1))
            .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(AiGenerationException.class,
                known -> assertThat(known.reason()).isEqualTo(AiGenerationException.Reason.RESPONSE_TIMEOUT)))
            .verify(java.time.Duration.ofSeconds(5));
    }

    @Test
    void preservesSafeFinishFailureReasons() {
        setup(Flux.just(TextStreamPart.finish(FinishReason.LENGTH, "private-provider-detail", null)));
        assertThatThrownBy(() -> bridge.chat("model", java.util.List.of(), "change", copy()).blockLast())
            .isInstanceOfSatisfying(AiGenerationException.class,
                error -> assertThat(error.reason()).isEqualTo(AiGenerationException.Reason.TRUNCATED));
        setup(Flux.just(TextStreamPart.finish(FinishReason.TOOL_CALLS, "private-provider-detail", null)));
        assertThatThrownBy(() -> bridge.chat("model", java.util.List.of(), "change", copy()).blockLast())
            .isInstanceOfSatisfying(AiGenerationException.class,
                error -> assertThat(error.reason()).isEqualTo(AiGenerationException.Reason.TOOL_LOOP_INCOMPLETE));
        setup(Flux.empty());
        assertThatThrownBy(() -> bridge.chat("model", java.util.List.of(), "change", copy()).blockLast())
            .isInstanceOfSatisfying(AiGenerationException.class,
                error -> assertThat(error.reason()).isEqualTo(AiGenerationException.Reason.STREAM_INCOMPLETE));
    }

    @Test
    void forwardsProviderReasoningButDoesNotFeedDisplayReasoningBackToModel() {
        var captured = setup(Flux.just(TextStreamPart.reasoningStart("r"),
            TextStreamPart.reasoningDelta("r", "visible provider reasoning", null),
            TextStreamPart.reasoningEnd("r"), TextStreamPart.finish(FinishReason.STOP, "stop", null)));
        var history = java.util.List.of(new ChatApi.Turn("previous", "admin", java.time.Instant.now(),
            "question", "answer", java.util.List.of(), "completed", null, null,
            java.util.List.of(new ChatApi.DisplayPart("reasoning", "r0", "old-reasoning-marker",
                null, null, null, false)), java.util.List.of()));
        var chunks = bridge.chat("model", history, "continue", copy()).collectList().block();
        assertThat(chunks).extracting(chunk -> chunk.get("type"))
            .containsExactly("reasoning-start", "reasoning-delta", "reasoning-end");
        assertThat(captured.get().getMessages().toString()).contains("answer")
            .doesNotContain("old-reasoning-marker");
    }

    @Test
    void includesPersistedQuestionsAndAnswersInFollowingModelContext() {
        var captured = setup(Flux.just(TextStreamPart.finish(FinishReason.STOP, "stop", null)));
        var answered = new ChatApi.Question("batch", java.util.List.of(new ChatApi.QuestionItem(
            "style", "Which visual style?", java.util.List.of(), true)),
            java.util.List.of(new ChatApi.AnswerItem("style", null, "Use generous whitespace")),
            java.time.Instant.now(), java.time.Instant.now());
        var history = PageChatService.contextWindow(java.util.List.of(new ChatApi.Turn("previous", "admin",
            java.time.Instant.now(), "create", "done", java.util.List.of(), "completed", null, null,
            java.util.List.of(), java.util.List.of(), java.util.List.of(answered))));
        bridge.chat("model", history, "continue", copy()).blockLast();
        assertThat(captured.get().getMessages().toString())
            .contains("Which visual style?", "Use generous whitespace");
    }

    @Test
    void toolBudgetsAreStickyEvenWhenAnExecutorExceptionIsHandledByFoundation() {
        var copy = new PageWorkingCopy(new run.halo.aipagebuilder.model.PageDocument(1, "code",
            null, null, "", "", "Title", ""), new ContentPolicy());
        for (int i = 0; i < PageWorkingCopy.MAX_CALLS; i++) { copy.read(); }
        assertThatThrownBy(copy::read).isInstanceOf(IllegalStateException.class);
        setup(Flux.just(TextStreamPart.finish(FinishReason.STOP, "stop", null)));
        assertThatThrownBy(() -> bridge.chat("model", java.util.List.of(), "change", copy).blockLast())
            .isInstanceOf(AiGenerationException.class);
        var large = new PageWorkingCopy(copy.document(), new ContentPolicy());
        assertThatThrownBy(() -> large.consume("a".repeat(PageWorkingCopy.MAX_BYTES + 1)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(large.exhausted()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = FinishReason.class, names = "STOP", mode = EnumSource.Mode.EXCLUDE)
    void everyNonStopFinishIsRejected(FinishReason reason) {
        setup(Flux.just(TextStreamPart.finish(reason, "provider-secret", null)));
        assertThatThrownBy(() -> bridge.chat("model", java.util.List.of(), "change", copy()).blockLast())
            .isInstanceOf(AiGenerationException.class).hasMessageNotContaining("provider-secret");
    }

    @Test
    void rejectsErrorsAbortAndLateEventsAndPropagatesCancellation() {
        for (var parts : java.util.List.of(
            Flux.just(TextStreamPart.error("provider-secret")),
            Flux.just(TextStreamPart.builder().type(PartType.ABORT).build()),
            Flux.just(TextStreamPart.finish(FinishReason.STOP, "stop", null),
                TextStreamPart.textDelta("t", "late")))) {
            setup(parts);
            assertThatThrownBy(() -> bridge.chat("model", java.util.List.of(), "change", copy()).blockLast())
                .isInstanceOf(AiGenerationException.class);
        }
        var captured = setup(Flux.never());
        var subscription = bridge.chat("model", java.util.List.of(), "change", copy()).subscribe();
        subscription.dispose();
        assertThat(captured.get().getCancellationToken().isCancellationRequested()).isTrue();
    }

    @Test
    void truncationContinuesFromValidatedCopyWithoutReplayingToolArgumentsOrFinish() {
        setup(Flux.empty());
        var attempts = new java.util.ArrayList<GenerateTextRequest>();
        var copy = copy();
        var saves = new java.util.concurrent.atomic.AtomicInteger();
        copy.checkpoint(() -> Mono.fromRunnable(saves::incrementAndGet));
        when(model.streamText(any())).thenAnswer(invocation -> {
            GenerateTextRequest request = invocation.getArgument(0);
            attempts.add(request);
            request.getPrepareStep().prepare(run.halo.aifoundation.chat.StepContext.builder()
                .messages(request.getMessages()).build());
            Flux<TextStreamPart> source;
            if (attempts.size() == 1) {
                var tool = request.getTools().stream().filter(t -> "update_current_page".equals(t.getName()))
                    .findFirst().orElseThrow();
                source = tool.getExecutor().execute(run.halo.aifoundation.tool.ToolExecutionContext.builder()
                    .input(java.util.Map.of("html", "<p>verified</p>")).build()).thenMany(Flux.just(
                        TextStreamPart.start("provider-first"),
                        TextStreamPart.textStart("same-id"), TextStreamPart.textDelta("same-id", "first"),
                        TextStreamPart.textEnd("same-id"),
                        TextStreamPart.toolInputStart("partial", "same-tool", "update_current_page"),
                        TextStreamPart.toolInputDelta("partial", "same-tool", "update_current_page", "partial-secret"),
                        TextStreamPart.finish(FinishReason.LENGTH, "length", null)));
            } else {
                assertThat(request.getMessages().toString()).contains("verified", "Discard truncated arguments")
                    .doesNotContain("partial-secret");
                source = Flux.just(TextStreamPart.start("provider-second"), TextStreamPart.textStart("same-id"),
                    TextStreamPart.textDelta("same-id", "second"), TextStreamPart.textEnd("same-id"),
                    TextStreamPart.finish(FinishReason.STOP, "stop", null));
            }
            return new StreamTextResult(source, Flux.empty(), Flux.empty(), Flux.empty(), Mono.empty(), Mono.empty());
        });
        var chunks = bridge.chat("model", java.util.List.of(), "original task", copy).collectList().block();
        assertThat(attempts).hasSize(2);
        assertThat(saves).hasValue(1);
        assertThat(chunks.stream().filter(chunk -> "start".equals(chunk.get("type")))
            .map(chunk -> chunk.get("messageId")).distinct()).hasSize(1);
        assertThat(copy.budget.steps()).isEqualTo(2);
        assertThat(chunks.stream().filter(chunk -> "text-delta".equals(chunk.get("type"))))
            .extracting(chunk -> chunk.get("id")).containsExactly("attempt-1-same-id", "attempt-2-same-id");
        assertThat(chunks).filteredOn(chunk -> "data-generation-recovery".equals(chunk.get("type"))).hasSize(1);
        assertThat(chunks).noneMatch(chunk -> "finish".equals(chunk.get("type")) || "error".equals(chunk.get("type")));
    }

    @Test
    void truncatedToolStepEndsSdkLoopAndUsesBoundedRecovery() {
        setup(Flux.empty());
        var copy = copy();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        when(model.streamText(any())).thenAnswer(invocation -> {
            GenerateTextRequest request = invocation.getArgument(0);
            attempts.incrementAndGet();
            var context = run.halo.aifoundation.chat.StepContext.builder()
                .messages(request.getMessages()).stepIndex(0)
                .step(run.halo.aifoundation.chat.GenerationStep.builder()
                    .finishReason(FinishReason.LENGTH).build()).build();
            request.getPrepareStep().prepare(context);
            // Foundation emits FINISH_STEP before evaluating its internal tool-loop condition.
            var source = Flux.just(TextStreamPart.toolInputStart("input", "cut", "update_current_page"),
                TextStreamPart.toolInputDelta("input", "cut", "update_current_page", "{\"html\":\"cut"),
                TextStreamPart.builder().type(PartType.FINISH_STEP).stepIndex(0)
                    .finishReason(FinishReason.LENGTH).build()).concatWith(Flux.defer(() -> {
                        assertThat(request.getStopWhen().shouldContinue(context)).isFalse();
                        return Flux.just(TextStreamPart.finish(FinishReason.LENGTH, "length", null));
                    }));
            return new StreamTextResult(source, Flux.empty(), Flux.empty(), Flux.empty(), Mono.empty(), Mono.empty());
        });
        var chunks = new java.util.ArrayList<java.util.Map<String, Object>>();
        assertThatThrownBy(() -> bridge.chat("model", java.util.List.of(), "task", copy)
            .doOnNext(chunks::add).blockLast()).isInstanceOfSatisfying(AiGenerationException.class,
                error -> assertThat(error.reason()).isEqualTo(AiGenerationException.Reason.TRUNCATED));
        assertThat(attempts).hasValue(3);
        assertThat(copy.budget.steps()).isEqualTo(3);
        assertThat(copy.budget.recoveries()).isEqualTo(2);
        assertThat(chunks).filteredOn(chunk -> "data-generation-recovery".equals(chunk.get("type"))).hasSize(2);
    }

    @Test
    void manualResumeAndAutomaticRecoveryUseOnlyTheLatestSnapshot() {
        setup(Flux.empty());
        var copy = copy();
        copy.restore(new run.halo.aipagebuilder.model.PageDocument(1, "code", null, null,
            "<p>old checkpoint</p>", "", "Title", ""), false, null, false, java.util.List.of());
        copy.taskContext(() -> "Original request: build a page");
        var attempts = new java.util.ArrayList<GenerateTextRequest>();
        when(model.streamText(any())).thenAnswer(invocation -> {
            GenerateTextRequest request = invocation.getArgument(0);
            attempts.add(request);
            var prompt = request.getMessages().toString();
            assertThat(prompt).containsOnlyOnce("Working copy:").contains("Original request: build a page");
            Flux<TextStreamPart> source;
            if (attempts.size() == 1) {
                assertThat(prompt).contains("old checkpoint");
                source = copy.edit(() -> copy.update(java.util.Map.of("html", "<p>new checkpoint</p>")))
                    .thenMany(Flux.just(TextStreamPart.finish(FinishReason.LENGTH, "length", null)));
            } else {
                assertThat(prompt).contains("new checkpoint").doesNotContain("old checkpoint");
                source = Flux.just(TextStreamPart.finish(FinishReason.STOP, "stop", null));
            }
            return new StreamTextResult(source, Flux.empty(), Flux.empty(), Flux.empty(), Mono.empty(), Mono.empty());
        });
        bridge.chat("model", java.util.List.of(), "build a page", copy).blockLast();
        assertThat(attempts).hasSize(2);
    }

    @Test
    void truncationRecoveryAndSteerCallsShareTheTwentyStepBudget() {
        var captured = setup(Flux.just(TextStreamPart.finish(FinishReason.STOP, "stop", null)));
        var copy = copy();
        bridge.chat("model", java.util.List.of(), "task", copy).blockLast();
        var context = run.halo.aifoundation.chat.StepContext.builder().messages(java.util.List.of()).build();
        for (int i = 1; i <= 20; i++) {
            var prepared = captured.get().getPrepareStep().prepare(context);
            assertThat(captured.get().getStopWhen().shouldContinue(context)).isEqualTo(i < 20);
            if (i >= 18) assertThat(prepared.getMessages().toString()).contains("model steps remain");
        }
        assertThatThrownBy(() -> captured.get().getPrepareStep().prepare(context))
            .isInstanceOfSatisfying(AiGenerationException.class,
                error -> assertThat(error.reason()).isEqualTo(AiGenerationException.Reason.STEP_LIMIT));
        assertThat(copy.budget.recover()).isFalse();
        setup(Flux.just(TextStreamPart.finish(FinishReason.TOOL_CALLS, "tool_calls", null)));
        assertThatThrownBy(() -> bridge.chat("model", java.util.List.of(), "steer", copy).blockLast())
            .isInstanceOfSatisfying(AiGenerationException.class,
                error -> assertThat(error.reason()).isEqualTo(AiGenerationException.Reason.STEP_LIMIT));
    }

    @Test
    void permitsExactlyTwoTruncationRecoveriesAndNeverRetriesNetworkOrValidationFailures() {
        var copy = copy();
        setup(Flux.just(TextStreamPart.finish(FinishReason.LENGTH, "length", null)));
        var chunks = new java.util.ArrayList<java.util.Map<String, Object>>();
        reactor.test.StepVerifier.create(bridge.chat("model", java.util.List.of(), "task", copy)
            .doOnNext(chunks::add)).expectNextCount(2)
            .expectError(AiGenerationException.class).verify();
        org.mockito.Mockito.verify(model, org.mockito.Mockito.times(3)).streamText(any());
        assertThat(copy.budget.recoveries()).isEqualTo(2);
        assertThat(chunks).allMatch(chunk -> "data-generation-recovery".equals(chunk.get("type")));
        org.mockito.Mockito.clearInvocations(model);
        setup(Flux.error(new IllegalStateException("network")));
        assertThatThrownBy(() -> bridge.chat("model", java.util.List.of(), "task", copy()).blockLast())
            .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verify(model).streamText(any());
    }

    private PageWorkingCopy copy() {
        return new PageWorkingCopy(new run.halo.aipagebuilder.model.PageDocument(1, "code", null, null,
            "", "", "Title", ""), new ContentPolicy());
    }

    private AtomicReference<GenerateTextRequest> setup(Flux<TextStreamPart> source) {
        var request = new AtomicReference<GenerateTextRequest>();
        when(extensions.getEnabledExtension(AiModelService.class)).thenReturn(Mono.just(service));
        when(service.languageModel("model")).thenReturn(Mono.just(model));
        when(model.streamText(any())).thenAnswer(invocation -> {
            request.set(invocation.getArgument(0));
            return new StreamTextResult(source, Flux.error(new AssertionError("text view subscribed")),
                Flux.empty(), Flux.empty(), Mono.empty(),
                Mono.error(new AssertionError("result view subscribed")));
        });
        return request;
    }
}
