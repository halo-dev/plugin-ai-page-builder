package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.PageRequest;
import run.halo.aipagebuilder.model.PageChatTurnRecord;
import run.halo.aipagebuilder.Json;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageChatSession;
import run.halo.aipagebuilder.model.PageDocument;
import run.halo.aipagebuilder.page.PageApi;
import run.halo.aipagebuilder.page.PageImportReviewService;
import run.halo.aipagebuilder.page.PageService;

class PageChatServiceTest {
    private final ConversationStorage storage = new ConversationStorage();
    private final Map<String, run.halo.aipagebuilder.model.PageChatCheckpoint> checkpoints = storage.checkpoints;
    private final ReactiveExtensionClient client = storage.client;
    private final PageService pages = mock(PageService.class);
    private final AiGateway gateway = mock(AiGateway.class);
    private final ContentPolicy policy = new ContentPolicy();
    private final Map<String, PageChatSession> stored = storage.stored;
    private final Map<String, PageChatTurnRecord> turns = storage.turns;
    private final run.halo.aipagebuilder.theme.ThemeContextService themes =
        mock(run.halo.aipagebuilder.theme.ThemeContextService.class);
    private final PageChatService service = new PageChatService(client, pages, gateway, policy,
        themes);
    private final String runId = UUID.randomUUID().toString();
    private final PageDetailFixture fixture = new PageDetailFixture();

    @BeforeEach
    void setup() {
        when(themes.current()).thenReturn(Mono.just(
            run.halo.aipagebuilder.theme.ThemeStyleContext.unavailable()));
        when(pages.withLivePage(anyString(), any())).thenAnswer(i ->
            i.<java.util.function.Supplier<Mono<Object>>>getArgument(1).get());
        when(pages.reserveRun(anyString(), any(), any())).thenAnswer(i ->
            i.<java.util.function.Supplier<Mono<Void>>>getArgument(2).get());

        storage.install();
        when(pages.get("page")).thenReturn(Mono.just(fixture.base));
        when(pages.committedAiRevision(anyString(), anyString())).thenReturn(Mono.empty());
        when(gateway.available()).thenReturn(Mono.just(true));
        when(pages.saveAi(eq("page"), eq(1L), any(), eq("admin"), eq(runId), eq("old-revision"), any()))
            .thenReturn(Mono.just(fixture.changed));
    }

    private void useRealPageLock() {
        var page = new run.halo.aipagebuilder.model.ThemePage();
        var metadata = new run.halo.app.extension.Metadata();
        metadata.setName("page");
        metadata.setVersion(1L);
        page.setMetadata(metadata);
        page.setSpec(new run.halo.aipagebuilder.model.ThemePage.Spec());
        when(client.fetch(run.halo.aipagebuilder.model.ThemePage.class, "page"))
            .thenReturn(Mono.just(page));
        // Exercise the production page lock rather than the usual pass-through fixture.
        var coordinatedPages = new PageService(client, policy, mock(PageImportReviewService.class));
        doAnswer(i -> coordinatedPages.withLivePage(i.getArgument(0), i.getArgument(1)))
            .when(pages).withLivePage(anyString(), any());
        doAnswer(i -> coordinatedPages.reserveRun(i.getArgument(0), i.getArgument(1), i.getArgument(2)))
            .when(pages).reserveRun(anyString(), any(), any());
    }

    @Test
    void concurrentFirstReadsShareOneDefaultSessionInitialization() {
        var created = Sinks.<PageChatSession>one();
        var candidate = new AtomicReference<PageChatSession>();
        when(client.create(any(PageChatSession.class))).thenAnswer(invocation -> {
            candidate.set(invocation.getArgument(0));
            return created.asMono();
        });
        var first = service.session("page").toFuture();
        var second = service.session("page").toFuture();
        verify(client, times(1)).create(any(PageChatSession.class));
        candidate.get().getMetadata().setVersion(1L);
        stored.put("page", Json.deepCopy(candidate.get()));
        created.tryEmitValue(candidate.get());
        assertThat(first.join().sessionId()).isEqualTo("page");
        assertThat(second.join().sessionId()).isEqualTo("page");
    }

    @Test
    void readingInitializedSessionDoesNotCompeteWithPageWrites() {
        service.session("page").block();
        doReturn(Mono.error(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
            "page write in progress"))).when(pages).withLivePage(anyString(), any());
        assertThat(service.session("page").block().sessionId()).isEqualTo("page");
        assertThat(service.sessions("page").block().items()).hasSize(1);
    }

    @Test
    void completedNoOpSurvivesArchiveFailureWithoutBecomingAnUncertainCommit() {
        when(gateway.chat(anyString(), any(), anyString(), any())).thenReturn(Flux.empty());
        when(client.create(any(PageChatTurnRecord.class)))
            .thenReturn(Mono.error(new IllegalStateException("archive unavailable")));
        service.start("page", request(), "admin").block().collectList().block();
        assertThat(stored.get("page").getSpec().getActive().status()).isEqualTo("completed");
        when(client.create(any(PageChatTurnRecord.class))).thenAnswer(i -> Mono.fromSupplier(() -> {
            PageChatTurnRecord record = i.getArgument(0);
            record.getMetadata().setVersion(1L);
            turns.put(record.getMetadata().getName(), Json.deepCopy(record));
            return record;
        }));
        assertThat(service.result("page", runId).block().status()).isEqualTo("completed");
        assertThat(stored.get("page").getSpec().getActive()).isNull();
        verify(pages, never()).saveAi(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void terminalRecoveryExcludesNewRunUntilOldCheckpointCleanupFinishes() {
        useRealPageLock();
        when(gateway.chat(anyString(), any(), anyString(), any())).thenReturn(Flux.empty());
        when(client.create(any(PageChatTurnRecord.class)))
            .thenReturn(Mono.error(new IllegalStateException("archive unavailable")));
        service.start("page", request(), "admin").block().blockLast();
        assertThat(stored.get("page").getSpec().getActive().status()).isEqualTo("completed");
        when(client.create(any(PageChatTurnRecord.class))).thenAnswer(i -> Mono.fromSupplier(() -> {
            PageChatTurnRecord record = i.getArgument(0);
            record.getMetadata().setVersion(1L);
            turns.put(record.getMetadata().getName(), Json.deepCopy(record));
            return record;
        }));
        var delayed = Sinks.<Void>empty();
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        var type = run.halo.aipagebuilder.model.PageChatCheckpoint.class;
        when(client.fetch(eq(type), anyString())).thenAnswer(i -> {
            String key = i.getArgument(1);
            var fetch = Mono.defer(() -> Mono.justOrEmpty(checkpoints.get(key)).map(Json::deepCopy));
            return Mono.defer(() -> first.compareAndSet(true, false)
                ? delayed.asMono().then(fetch) : fetch);
        });
        var recovery = service.result("page", runId).toFuture();
        assertThat(recovery).isNotDone();
        var next = new ChatApi.Request(UUID.randomUUID().toString(), 1L, "model", "new task");
        assertThatThrownBy(() -> service.start("page", next, "admin").block())
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        delayed.tryEmitEmpty();
        assertThat(recovery.join().status()).isEqualTo("completed");
        waitingGateway(Flux.empty());
        service.start("page", next, "admin").block();
        assertThat(service.result("page", next.runId()).block().status()).isEqualTo("waiting_for_user");
        assertThat(checkpoints.values()).singleElement().satisfies(checkpoint ->
            assertThat(checkpoint.getSpec().getRequest().runId()).isEqualTo(next.runId()));
        service.stop();
    }

    @Test
    void pluginStopInterruptsAndPreservesCheckpointInsteadOfCancelling() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        when(gateway.chat(anyString(), any(), anyString(), any())).thenReturn(
            Flux.<Map<String, Object>>never().doOnCancel(() -> cancelled.set(true)));
        service.start("page", request(), "admin").block();
        service.stop();
        assertThat(cancelled).isTrue();
        assertThat(service.result("page", runId).block().status()).isEqualTo("interrupted");
        assertThat(service.checkpoint("page", "page", "admin").block().recovery().canResume()).isTrue();
        assertThat(stored.get("page").getSpec().getActive()).isNull();
        assertThatThrownBy(() -> service.start("page",
            new ChatApi.Request(UUID.randomUUID().toString(), 1L, "model", "new"), "admin").block())
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void forcedStopRejectsRequestStillPreparingHistory() {
        var readingHistory = new java.util.concurrent.atomic.AtomicBoolean();
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        when(client.listBy(eq(PageChatTurnRecord.class), any(), any())).thenAnswer(i -> {
            PageRequest request = i.getArgument(2);
            return request.getPageSize() > 1
                ? Mono.never().doOnSubscribe(ignored -> readingHistory.set(true))
                    .doOnCancel(() -> cancelled.set(true))
                : Mono.just(new ListResult<>(1, 1, 0, List.of()));
        });
        var accepted = service.start("page", request(), "admin").toFuture();
        assertThat(readingHistory).isTrue();
        assertThat(accepted).isNotDone();

        service.stop();

        assertThat(cancelled).isTrue();
        assertThat(accepted).isCompletedExceptionally();
        assertThatThrownBy(accepted::join).hasCauseInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error.getCause())
                .getStatusCode().value()).isEqualTo(503));
        assertThat(stored.get("page").getSpec().getActive()).isNull();
        verify(gateway, never()).chat(anyString(), any(), anyString(), any());
    }

    @Test
    void forcedStopClosesAcceptedStreamWhenInterruptCannotBePersisted() {
        when(gateway.chat(anyString(), any(), anyString(), any())).thenReturn(Flux.never());
        var events = service.start("page", request(), "admin").block().collectList().toFuture();
        when(client.update(any(PageChatSession.class)))
            .thenReturn(Mono.error(new IllegalStateException("storage temporarily unavailable")));

        service.stop();

        assertThat(events).isDone();
        assertThat(events.join()).singleElement().satisfies(event ->
            assertThat(event).containsEntry("type", "error")
                .containsEntry("errorText", "运行结果待确认，请查询运行状态"));
        assertThat(stored.get("page").getSpec().getActive().status()).isEqualTo("running");
        assertThat(checkpoints).hasSize(1);
        verify(pages, never()).saveAi(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void forcedStopQueuesCompletionBehindInFlightEventDelivery() throws Exception {
        var chunks = Sinks.many().unicast().<Map<String, Object>>onBackpressureBuffer();
        when(gateway.chat(anyString(), any(), anyString(), any())).thenReturn(chunks.asFlux());
        var entered = new java.util.concurrent.CountDownLatch(1);
        var deliver = new java.util.concurrent.CountDownLatch(1);
        var events = service.start("page", request(), "admin").block().doOnNext(event -> {
            if ("text-delta".equals(event.get("type"))) {
                entered.countDown();
                try {
                    if (!deliver.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new AssertionError("event delivery timed out");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            }
        }).collectList().toFuture();
        var emitting = java.util.concurrent.CompletableFuture.runAsync(() ->
            chunks.tryEmitNext(Map.of("type", "text-delta", "id", "text", "delta", "hello")));
        try {
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            when(client.update(any(PageChatSession.class)))
                .thenReturn(Mono.error(new IllegalStateException("storage temporarily unavailable")));
            service.stop();
            assertThat(events).isNotDone();
        } finally {
            deliver.countDown();
        }
        emitting.get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(events.get(2, java.util.concurrent.TimeUnit.SECONDS))
            .extracting(event -> event.get("type")).containsExactly("text-delta", "error");
        assertThat(stored.get("page").getSpec().getActive().status()).isEqualTo("running");
        assertThat(checkpoints).hasSize(1);
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void forcedReleaseDoesNotReplaceInFlightSuccessfulCompletion() throws Exception {
        var chunks = Sinks.many().unicast().<Map<String, Object>>onBackpressureBuffer();
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> Flux.defer(() -> {
            return ((PageWorkingCopy) i.getArgument(3))
                .update(Map.of("html", "<p>new</p>", "css", "")).thenMany(chunks.asFlux());
        }));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var deliver = new java.util.concurrent.CountDownLatch(1);
        var events = service.start("page", request(), "admin").block().doOnNext(event -> {
            if ("data-page-committed".equals(event.get("type"))) {
                entered.countDown();
                try {
                    if (!deliver.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new AssertionError("event delivery timed out");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            }
        }).collectList().toFuture();
        var coordinator = service.runs();
        var run = coordinator.get("page");
        var completing = java.util.concurrent.CompletableFuture.runAsync(chunks::tryEmitComplete);
        try {
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // Exercise the shutdown deadline's release path without waiting another 20 seconds.
            run.execution.dispose();
            coordinator.release("page", run);
            coordinator.release("page", run);
        } finally {
            deliver.countDown();
        }
        completing.get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(events.get(2, java.util.concurrent.TimeUnit.SECONDS))
            .extracting(event -> event.get("type")).containsExactly("data-page-committed", "finish");
        assertThat(service.result("page", runId).block().status()).isEqualTo("completed");
    }

    @Test
    void unansweredQuestionExpiresWithoutDiscardingWork() {
        var clock = reactor.test.scheduler.VirtualTimeScheduler.getOrSet();
        try {
            waitingGateway(Flux.empty());
            service.start("page", request(), "admin").block();
            clock.advanceTimeBy(PageRun.WAIT_TIMEOUT.minusSeconds(1));
            assertThat(service.result("page", runId).block().status()).isEqualTo("waiting_for_user");
            clock.advanceTimeBy(java.time.Duration.ofSeconds(1));
            assertThat(service.result("page", runId).block().status()).isEqualTo("interrupted");
            assertThat(service.checkpoint("page", "page", "admin").block().recovery().canResume()).isTrue();
            assertThat(stored.get("page").getSpec().getActive()).isNull();
            verify(pages, never()).saveAi(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
        } finally { reactor.test.scheduler.VirtualTimeScheduler.reset(); }
    }

    @Test
    void pluginWideAdmissionIsBoundedAndReleasesCompletedRuns() {
        when(pages.get(anyString())).thenReturn(Mono.just(fixture.base));
        when(gateway.chat(anyString(), any(), anyString(), any())).thenReturn(Flux.never());
        for (int i = 0; i < PageRunCoordinator.MAX_ACTIVE_RUNS; i++) {
            service.start("page-" + i, request(), "admin").block();
        }
        assertThatThrownBy(() -> service.start("excess", request(), "admin").block())
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(429));
        service.cancel("page-0", runId).block();
        service.start("excess", request(), "admin").block();
        verify(gateway, times(PageRunCoordinator.MAX_ACTIVE_RUNS + 1))
            .chat(anyString(), any(), anyString(), any());
        service.stop();
    }

    @Test
    void resumedSessionCleanupFinishesAfterAChildDeletionFails() {
        stagedSuccess();
        service.start("page", request(), "admin").block().blockLast();
        var version = service.session("page").block().version();
        when(client.delete(any(PageChatTurnRecord.class)))
            .thenReturn(Mono.error(new IllegalStateException("storage unavailable")));
        assertThatThrownBy(() -> service.deleteSession("page", "page", version).block())
            .isInstanceOf(IllegalStateException.class);
        assertThat(stored.get("page").getSpec().getDeletingSessionId()).isEqualTo("page");
        when(client.delete(any(PageChatTurnRecord.class))).thenAnswer(i -> Mono.fromSupplier(() -> {
            PageChatTurnRecord record = i.getArgument(0);
            turns.remove(record.getMetadata().getName());
            return record;
        }));
        assertThat(service.session("page").block().turns()).isEmpty();
        assertThat(stored.get("page").getSpec().getDeletingSessionId()).isNull();
    }

    @Test
    void sessionDeletionExcludesReadRecoveryAndNewRunsUntilCleanupFinishes() {
        useRealPageLock();
        when(gateway.chat(anyString(), any(), anyString(), any())).thenReturn(Flux.empty());
        service.start("page", request(), "admin").block().blockLast();
        var version = service.session("page").block().version();
        var delayed = Sinks.<PageChatTurnRecord>one();
        var oldRecord = new AtomicReference<PageChatTurnRecord>();
        when(client.delete(any(PageChatTurnRecord.class))).thenAnswer(i -> {
            oldRecord.set(i.getArgument(0));
            return delayed.asMono().doOnNext(record -> turns.remove(record.getMetadata().getName()));
        });

        var deletion = service.deleteSession("page", "page", version).toFuture();
        assertThat(deletion).isNotDone();
        var next = new ChatApi.Request(UUID.randomUUID().toString(), 1L, "model", "new task");
        assertThatThrownBy(() -> service.session("page").block())
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        assertThatThrownBy(() -> service.start("page", next, "admin").block())
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        verify(client, times(1)).delete(any(PageChatTurnRecord.class));
        assertThat(stored.get("page").getSpec().getDeletingSessionId()).isEqualTo("page");

        delayed.tryEmitValue(oldRecord.get());
        deletion.join();
        assertThat(service.session("page").block().turns()).isEmpty();
        waitingGateway(Flux.empty());
        service.start("page", next, "admin").block();
        assertThat(service.result("page", next.runId()).block().status()).isEqualTo("waiting_for_user");
        assertThat(checkpoints.values()).singleElement().satisfies(checkpoint -> {
            assertThat(checkpoint.getSpec().getRequest().runId()).isEqualTo(next.runId());
            assertThat(checkpoint.getSpec().getDocument().html()).isEqualTo("<p>new</p>");
        });
        service.stop();
    }

    @Test
    void initialGenerationCommitsSemanticPathWithTitleAndContent() {
        usePageWithoutPath();
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> Flux.defer(() -> {
            PageWorkingCopy copy = i.getArgument(3);
            assertThat(copy.read()).containsEntry("canSetSlug", true);
            return copy.setTitle(Map.of("title", "关于我们"))
                .then(copy.setSlug(Map.of("slug", "company/about")))
                .then(copy.update(Map.of("html", "<p>About us</p>", "css", "")))
                .thenMany(Flux.empty());
        }));
        var events = service.start("page", request(), "admin").block().collectList().block();
        verify(pages).saveAi(eq("page"), eq(1L),
            org.mockito.ArgumentMatchers.argThat(document -> document.title().equals("关于我们")
                && document.html().equals("<p>About us</p>")),
            eq("admin"), eq(runId), eq("old-revision"), eq("company/about"));
        assertThat(events).extracting(event -> event.get("type")).contains("data-page-committed");
    }

    @Test
    void agentNamesADefaultSessionThroughTheRenameTool() {
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> {
            PageWorkingCopy copy = i.getArgument(3);
            return copy.renameSession(Map.of("title", "首页设计")).thenMany(Flux.empty());
        });
        service.start("page", request(), "admin").block().blockLast();
        assertThat(stored.get("page").getSpec().getTitle()).isEqualTo("首页设计");
    }

    @Test
    void agentOverwritesAnExistingSessionNameThroughTheRenameTool() {
        var created = service.createSession("page", new ChatApi.CreateSession("我的会话")).block();
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> {
            PageWorkingCopy copy = i.getArgument(3);
            return copy.renameSession(Map.of("title", "自动名称")).thenMany(Flux.empty());
        });
        service.start("page", new ChatApi.Request(runId, 1L, "model", "修改", null, List.of(),
            created.sessionId()), "admin").block().blockLast();
        assertThat(stored.get(created.sessionId()).getSpec().getTitle()).isEqualTo("自动名称");
    }

    @Test
    void consecutiveRenamesInOneStepAreAppliedInOrder() {
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> {
            PageWorkingCopy copy = i.getArgument(3);
            return copy.renameSession(Map.of("title", "第一次"))
                .then(copy.renameSession(Map.of("title", "第二次")))
                .thenMany(Flux.empty());
        });
        service.start("page", request(), "admin").block().blockLast();
        assertThat(stored.get("page").getSpec().getTitle()).isEqualTo("第二次");
    }

    @Test
    void cancelledRenameKeepsLaterRenamesBehindItsWrite() {
        var gate = Sinks.<Void>empty();
        var applied = new CopyOnWriteArrayList<String>();
        when(client.update(any(PageChatSession.class))).thenAnswer(i -> {
            PageChatSession session = i.getArgument(0);
            Mono<PageChatSession> apply = Mono.fromSupplier(() -> {
                var previous = stored.get(session.getMetadata().getName());
                if (!previous.getMetadata().getVersion().equals(session.getMetadata().getVersion())) {
                    throw new OptimisticLockingFailureException("CAS");
                }
                session.getMetadata().setVersion(session.getMetadata().getVersion() + 1);
                stored.put(session.getMetadata().getName(), Json.deepCopy(session));
                applied.add(session.getSpec().getTitle());
                return session;
            });
            // Only the first rename's write is held open; the coordinator update must finish.
            return "第一次".equals(session.getSpec().getTitle()) ? gate.asMono().then(apply) : apply;
        });
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> {
            PageWorkingCopy copy = i.getArgument(3);
            copy.renameSession(Map.of("title", "第一次")).subscribe().dispose();
            return copy.renameSession(Map.of("title", "第二次")).thenMany(Flux.empty());
        });
        var events = service.start("page", request(), "admin").block();
        // Cancelling the first subscriber must keep the barrier until its write terminates.
        assertThat(applied).doesNotContain("第一次", "第二次");
        gate.tryEmitEmpty();
        events.blockLast();
        assertThat(applied).containsSubsequence("第一次", "第二次");
    }

    @Test
    void pathOnlyRunIsNotMistakenForAnUnchangedDocument() {
        usePageWithoutPath();
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> Flux.defer(() -> {
            return ((PageWorkingCopy) i.getArgument(3)).setSlug(Map.of("slug", "about"))
                .thenMany(Flux.empty());
        }));
        service.start("page", request(), "admin").block().blockLast();
        verify(pages).saveAi(eq("page"), eq(1L), eq(fixture.base.document()),
            eq("admin"), eq(runId), eq("old-revision"), eq("about"));
    }

    @Test
    void cancelledGenerationDoesNotSaveItsStagedPath() {
        usePageWithoutPath();
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> Flux.defer(() -> {
            return ((PageWorkingCopy) i.getArgument(3)).setSlug(Map.of("slug", "about"))
                .thenMany(Flux.never());
        }));
        service.start("page", request(), "admin").block();
        service.cancel("page", runId).block();
        verify(pages, never()).saveAi(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void restartAndResumePreserveTheStagedSemanticPath() {
        usePageWithoutPath();
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> {
            PageWorkingCopy copy = i.getArgument(3);
            return copy.setSlug(Map.of("slug", "company/about"))
                .thenMany(Flux.never());
        });
        service.start("page", request(), "admin").block();
        var restarted = new PageChatService(client, pages, gateway, policy,
            themes);
        assertThat(restarted.result("page", runId).block().status()).isEqualTo("interrupted");
        var saved = restarted.checkpoint("page", "page", "admin").block();
        var next = UUID.randomUUID().toString();
        when(pages.saveAi(eq("page"), eq(1L), any(), eq("admin"), eq(next), eq("old-revision"), eq("company/about")))
            .thenReturn(Mono.just(fixture.changed));
        doAnswer(i -> {
            PageWorkingCopy copy = i.getArgument(3);
            assertThat(copy.slug()).isEqualTo("company/about");
            assertThat(copy.recoveryContext()).contains("company/about");
            return Flux.empty();
        }).when(gateway).chat(anyString(), any(), anyString(), any());
        restarted.start("page", resumeRequest(saved, next), "admin").block().blockLast();
        assertThat(restarted.result("page", next).block().status()).isEqualTo("completed");
        verify(pages).saveAi(eq("page"), eq(1L), any(), eq("admin"), eq(next), eq("old-revision"), eq("company/about"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void resumedPathValidationIsIndependentFromContentValidation(boolean invalidContent) {
        usePageWithoutPath();
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> {
            PageWorkingCopy copy = i.getArgument(3);
            return copy.setSlug(Map.of("slug", "UPPER"))
                .then(invalidContent ? copy.update(Map.of("html", "<script>bad()</script>")) : Mono.empty())
                .thenMany(Flux.error(new AiGenerationException(AiGenerationException.Reason.TRUNCATED)));
        });
        service.start("page", request(), "admin").block().blockLast();
        var saved = service.checkpoint("page", "page", "admin").block();
        var next = UUID.randomUUID().toString();
        when(pages.saveAi(eq("page"), eq(1L), any(), eq("admin"), eq(next), eq("old-revision"), eq("about")))
            .thenReturn(Mono.just(fixture.changed));
        doAnswer(i -> {
            PageWorkingCopy copy = i.getArgument(3);
            assertThat(copy.hasInvalidUpdate()).isTrue();
            assertThat(copy.hasInvalidSlugUpdate()).isTrue();
            assertThat(copy.hasInvalidContentUpdate()).isEqualTo(invalidContent);
            return copy.setSlug(Map.of("slug", "about"))
                .doOnNext(ignored -> assertThat(copy.hasInvalidUpdate()).isEqualTo(invalidContent))
                .then(copy.update(Map.of("html", "<p>About</p>")))
                .doOnNext(ignored -> assertThat(copy.hasInvalidUpdate()).isFalse()).thenMany(Flux.empty());
        }).when(gateway).chat(anyString(), any(), anyString(), any());
        service.start("page", resumeRequest(saved, next), "admin").block().blockLast();
        assertThat(service.result("page", next).block().status()).isEqualTo("completed");
        verify(pages).saveAi(eq("page"), eq(1L), any(), eq("admin"), eq(next), eq("old-revision"), eq("about"));
    }

    private void usePageWithoutPath() {
        var base = fixture.base.page();
        when(pages.get("page")).thenReturn(Mono.just(new PageApi.PageDetail(
            new PageApi.PageSummary(base.name(), null, base.title(), base.description(), base.version(),
                base.themeLayout(), base.draftRevision(), null, base.updatedAt(), true),
            fixture.base.document())));
    }

    @Test
    void tenMinuteDeadlineStopsAnActiveStreamWithoutSavingStagedChanges() {
        var clock = reactor.test.scheduler.VirtualTimeScheduler.getOrSet();
        try {
            when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> Flux.defer(() -> {
                PageWorkingCopy copy = i.getArgument(3);
                return copy.update(Map.of("html", "<p>unfinished</p>", "css", ""))
                    .thenMany(Flux.interval(java.time.Duration.ofSeconds(30))
                        .map(tick -> Map.<String, Object>of("type", "text-delta", "id", "t", "delta", ".")));
            }));
            var events = new java.util.ArrayList<Map<String, Object>>();
            service.start("page", request(), "admin").block().subscribe(events::add);
            clock.advanceTimeBy(java.time.Duration.ofSeconds(599));
            assertThat(service.result("page", runId).block().status()).isEqualTo("running");
            verify(pages, never()).saveAi(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
            clock.advanceTimeBy(java.time.Duration.ofSeconds(1));
            var result = service.result("page", runId).block();
            assertThat(result.status()).isEqualTo("incomplete");
            assertThat(result.error()).isEqualTo(AiGenerationException.Reason.RUN_TIMEOUT.message);
            assertThat(service.session("page").block().turns().getFirst().error()).isEqualTo(result.error());
            assertThat(events).noneMatch(event -> "data-page-committed".equals(event.get("type")));
            verify(pages, never()).saveAi(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
        } finally {
            reactor.test.scheduler.VirtualTimeScheduler.reset();
        }
    }

    @Test
    void responseTimeoutPersistsItsOwnReasonWithoutSavingStagedChanges() {
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> Flux.defer(() -> {
            PageWorkingCopy copy = i.getArgument(3);
            return copy.update(Map.of("html", "<p>unfinished</p>", "css", ""))
                .thenMany(Flux.error(new AiGenerationException(AiGenerationException.Reason.RESPONSE_TIMEOUT)));
        }));
        service.start("page", request(), "admin").block().blockLast();
        assertThat(service.result("page", runId).block().error())
            .isEqualTo(AiGenerationException.Reason.RESPONSE_TIMEOUT.message);
        verify(pages, never()).saveAi(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void stagesRepairsThenCommitsOnceAndFinishFollowsDurableResult() {
        when(gateway.chat(eq("model"), any(), anyString(), any())).thenAnswer(i -> Flux.defer(() -> {
            PageWorkingCopy copy = i.getArgument(3);
            assertThat(copy.read().get("html")).isEqualTo("<p>old</p>");
            return copy.update(Map.of("html", "<script>bad</script>", "css", ""))
                .doOnNext(result -> assertThat(result).containsEntry("valid", false))
                .then(copy.update(Map.of("html", "<p>new</p>", "css", "")))
                .doOnNext(ignored -> verify(pages, never()).saveAi(anyString(), any(), any(),
                    anyString(), anyString(), anyString(), any()))
                .thenMany(Flux.just(Map.of("type", "text-delta", "id", "t", "delta", "完成")));
        }));
        var events = service.start("page", request(), "admin").block().collectList().block();
        assertThat(events).extracting(value -> value.get("type"))
            .containsExactly("text-delta", "data-page-committed", "finish");
        verify(pages, times(1)).saveAi(eq("page"), eq(1L), any(), eq("admin"), eq(runId), eq("old-revision"), any());
        var session = service.session("page").block();
        assertThat(session.activeRun()).isNull();
        assertThat(session.turns()).hasSize(1);
        assertThat(session.turns().getFirst().tools()).hasSize(3);
        assertThat(Json.toJson(session)).doesNotContain("<p>new</p>", "<script>");
        assertThat(service.result("page", runId).block().status()).isEqualTo("completed");
        assertThatThrownBy(() -> service.start("page", request(), "admin").block())
            .hasMessageContaining("409");
    }

    @Test
    void stagedPreviewIsSeparateFromDurableCommitAndDisplayHistory() {
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> Flux.defer(() -> {
            PageWorkingCopy copy = i.getArgument(3);
            return copy.update(Map.of("html", "<p>new</p>", "css", "")).flatMapMany(output -> Flux.just(
                Map.of("type", "reasoning-delta", "id", "reason", "delta", "provider explanation"),
                Map.of("type", "tool-input-start", "toolCallId", "tool", "toolName", "update_current_page"),
                Map.of("type", "tool-output-available", "toolCallId", "tool", "output", output)));
        }));
        var events = service.start("page", request(), "admin").block().collectList().block();
        assertThat(events).extracting(value -> value.get("type")).containsExactly("reasoning-delta",
            "tool-input-start", "tool-output-available", "data-page-staged", "data-page-committed", "finish");
        verify(pages, times(1)).saveAi(eq("page"), eq(1L), any(), eq("admin"), eq(runId), eq("old-revision"), any());
        assertThat(events.get(3)).containsEntry("name", "page-staged").containsEntry("id", "tool");
        assertThat(events.get(4)).containsEntry("name", "page-committed").containsEntry("id", runId);
        var turn = service.session("page").block().turns().getFirst();
        assertThat(turn.parts()).extracting(ChatApi.DisplayPart::type).containsExactly("reasoning", "tool");
        assertThat(Json.toJson(turn)).contains("provider explanation").doesNotContain("<p>new</p>");
        assertThat(turn.usage().toolBytes()).isPositive();
        assertThat(turn.usage().previewBytes()).isEqualTo(Json.toJson(events.get(3).get("data"))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    @Test
    void memorySurvivesPageFailureAndLoadsInAnotherPage() {
        var memories = mock(run.halo.aipagebuilder.memory.SiteMemoryService.class);
        var snapshot = new java.util.concurrent.atomic.AtomicReference<>(PageMemoryToolsTest.snapshot(1));
        when(memories.load()).thenAnswer(ignored -> Mono.just(snapshot.get()));
        when(memories.apply(any(), any(), any())).thenAnswer(ignored -> {
            var saved = PageMemoryToolsTest.snapshot(2);
            saved.getSpec().setEntries(List.of(new run.halo.aipagebuilder.model.SiteMemory.Entry(
                "phone", "本站公司", "电话", "fact", "0571-12345678", null, java.time.Instant.now())));
            snapshot.set(saved);
            return Mono.just(saved);
        });
        var chat = new PageChatService(client, pages, gateway, policy,
            mock(run.halo.aipagebuilder.theme.ThemeContextService.class), null, memories);
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> {
            PageWorkingCopy copy = i.getArgument(3);
            return copy.updateMemory(PageMemoryToolsTest.input(1, runId + "/prompt", "0571-12345678"))
                .thenMany(Flux.error(new IllegalStateException("model failed")));
        });
        chat.start("page", new ChatApi.Request(runId, 1L, "model", "公司电话 0571-12345678"), "admin")
            .block().blockLast();
        assertThat(chat.session("page").block().turns().getFirst().tools())
            .anySatisfy(tool -> assertThat(tool.status()).isEqualTo("completed"));
        when(pages.get("other")).thenReturn(Mono.just(fixture.base));
        org.mockito.Mockito.reset(gateway);
        when(gateway.available()).thenReturn(Mono.just(true));
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> {
            PageWorkingCopy copy = i.getArgument(3);
            assertThat(copy.memoryReference()).contains("0571-12345678");
            return Flux.empty();
        });
        chat.start("other", new ChatApi.Request(UUID.randomUUID().toString(), 1L, "model", "联系我们"), "admin")
            .block().blockLast();
        verify(memories, times(1)).apply(any(), any(), any());
    }

    @Test
    void stalledInitialMemoryLoadDegradesAndCancelledLoadReleasesReservation() {
        var clock = reactor.test.scheduler.VirtualTimeScheduler.getOrSet();
        try {
            var memories = mock(run.halo.aipagebuilder.memory.SiteMemoryService.class);
            when(memories.load()).thenReturn(Mono.never());
            var chat = new PageChatService(client, pages, gateway, policy,
                mock(run.halo.aipagebuilder.theme.ThemeContextService.class), null, memories);
            when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> {
                PageWorkingCopy copy = i.getArgument(3);
                assertThat(copy.hasMemory()).isFalse();
                assertThat(copy.memoryReference()).contains("could not be loaded");
                return Flux.empty();
            });
            var abandoned = chat.start("page", request(), "admin").subscribe();
            abandoned.dispose();
            var started = chat.start("page", request(), "admin").toFuture();
            clock.advanceTimeBy(java.time.Duration.ofSeconds(15));
            assertThat(started).isCompleted();
            assertThat(started.join().collectList().block()).extracting(event -> event.get("type"))
                .contains("finish");
            assertThat(chat.session("page").block().turns().getFirst().tools())
                .anySatisfy(tool -> assertThat(tool.summary()).contains("记忆读取失败"));
            verify(gateway, times(1)).chat(anyString(), any(), anyString(), any());
        } finally {
            reactor.test.scheduler.VirtualTimeScheduler.reset();
        }
    }

    @Test
    void downstreamCancellationAfterStartupKeepsTheRunningReservation() {
        var memories = mock(run.halo.aipagebuilder.memory.SiteMemoryService.class);
        when(memories.load()).thenReturn(Mono.just(PageMemoryToolsTest.snapshot(1)));
        var chat = new PageChatService(client, pages, gateway, policy,
            mock(run.halo.aipagebuilder.theme.ThemeContextService.class), null, memories);
        when(gateway.chat(anyString(), any(), anyString(), any())).thenReturn(Flux.never());
        var events = new java.util.concurrent.atomic.AtomicReference<Flux<Map<String, Object>>>();
        var response = chat.start("page", request(), "admin")
            .flatMap(stream -> {
                events.set(stream);
                return Mono.never();
            }).subscribe();
        response.dispose();
        assertThat(chat.result("page", runId).block().status()).isEqualTo("running");
        assertThat(chat.cancel("page", runId).block().status()).isEqualTo("cancelled");
        assertThat(events.get().collectList().block()).extracting(event -> event.get("type"))
            .contains("abort");
    }

    @Test
    void cancellationIsDurableBeforeAcknowledgementWhileCheckpointWriteIsPending() {
        when(client.update(any(run.halo.aipagebuilder.model.PageChatCheckpoint.class)))
            .thenReturn(Mono.never());
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> {
            PageWorkingCopy copy = i.getArgument(3);
            return copy.update(Map.of("html", "<p>pending checkpoint</p>"))
                .thenMany(Flux.never());
        });
        service.start("page", request(), "admin").block();
        assertThat(service.cancel("page", runId).block().status()).isEqualTo("cancelled");
        assertThat(stored.get("page").getSpec().getActive().status()).isEqualTo("cancelled");
        assertThat(checkpoints).hasSize(1);
        var restarted = new PageChatService(client, pages, gateway, policy,
            mock(run.halo.aipagebuilder.theme.ThemeContextService.class));
        var result = restarted.result("page", runId).block();
        assertThat(result.status()).isEqualTo("cancelled");
        assertThat(result.recovery()).isNull();
        assertThat(checkpoints).isEmpty();
        verify(pages, never()).saveAi(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void failedCancellationPersistenceIsNotAcknowledgedAndCanBeRetried() {
        when(gateway.chat(anyString(), any(), anyString(), any())).thenReturn(Flux.never());
        service.start("page", request(), "admin").block();
        var failures = new AtomicInteger();
        when(client.update(any(PageChatSession.class))).thenAnswer(i -> Mono.fromSupplier(() -> {
            PageChatSession session = i.getArgument(0);
            if (session.getSpec().getActive() != null
                && "cancelled".equals(session.getSpec().getActive().status())
                && failures.getAndIncrement() == 0) throw new IllegalStateException("storage unavailable");
            session.getMetadata().setVersion(session.getMetadata().getVersion() + 1);
            stored.put(session.getMetadata().getName(), Json.deepCopy(session));
            return session;
        }));
        assertThatThrownBy(() -> service.cancel("page", runId).block())
            .isInstanceOf(IllegalStateException.class);
        assertThat(stored.get("page").getSpec().getActive().status()).isEqualTo("running");
        assertThat(service.cancel("page", runId).block().status()).isEqualTo("cancelled");
        assertThat(service.result("page", runId).block().status()).isEqualTo("cancelled");
    }

    @Test
    void modelFailureDuringFailedCancellationPersistenceIsArchivedAsFailed() {
        Sinks.Many<Map<String, Object>> model = Sinks.many().unicast().onBackpressureBuffer();
        when(gateway.chat(anyString(), any(), anyString(), any())).thenReturn(model.asFlux());
        service.start("page", request(), "admin").block();
        var cancellationWrite = Sinks.<PageChatSession>one();
        when(client.update(any(PageChatSession.class))).thenAnswer(i -> {
            PageChatSession session = i.getArgument(0);
            if (session.getSpec().getActive() != null
                && "cancelled".equals(session.getSpec().getActive().status())) return cancellationWrite.asMono();
            return Mono.fromSupplier(() -> {
                session.getMetadata().setVersion(session.getMetadata().getVersion() + 1);
                stored.put(session.getMetadata().getName(), Json.deepCopy(session));
                return session;
            });
        });
        var response = service.cancel("page", runId).toFuture();
        assertThat(response).isNotDone();
        model.tryEmitError(new AiGenerationException(AiGenerationException.Reason.CALL_FAILED));
        cancellationWrite.tryEmitError(new IllegalStateException("storage unavailable"));
        assertThat(response).isCompletedExceptionally();
        var result = service.result("page", runId).block();
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.error()).isEqualTo(AiGenerationException.Reason.CALL_FAILED.message);
        assertThat(result.recovery().canResume()).isTrue();
    }

    @Test
    void cancellationWaitsForMemoryWriteAndArchivesItsOutcome() {
        var memories = mock(run.halo.aipagebuilder.memory.SiteMemoryService.class);
        when(memories.load()).thenReturn(Mono.just(PageMemoryToolsTest.snapshot(1)));
        var saved = Sinks.<run.halo.aipagebuilder.model.SiteMemory>one();
        when(memories.apply(any(), any(), any())).thenReturn(saved.asMono());
        var chat = new PageChatService(client, pages, gateway, policy,
            mock(run.halo.aipagebuilder.theme.ThemeContextService.class), null, memories);
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> {
            PageWorkingCopy copy = i.getArgument(3);
            return Flux.concat(Flux.just(Map.<String, Object>of("type", "tool-input-start",
                "toolCallId", "memory", "toolName", PageMemoryTools.NAME)),
                copy.updateMemory("memory", PageMemoryToolsTest.input(1, runId + "/prompt", "0571-12345678"))
                    .thenMany(Flux.never()));
        });
        var events = chat.start("page", new ChatApi.Request(runId, 1L, "model", "0571-12345678"), "admin")
            .block().collectList();
        var cancelled = new java.util.concurrent.atomic.AtomicReference<ChatApi.Run>();
        chat.cancel("page", runId).subscribe(cancelled::set);
        assertThat(cancelled.get()).isNull();
        saved.tryEmitValue(PageMemoryToolsTest.snapshot(2));
        assertThat(cancelled.get().status()).isEqualTo("cancelled");
        assertThat(events.block()).extracting(value -> value.get("type")).contains("abort");
        var turn = chat.session("page").block().turns().getFirst();
        assertThat(turn.tools()).singleElement().satisfies(tool ->
            assertThat(tool.status()).isEqualTo("completed"));
        assertThat(turn.parts()).singleElement().satisfies(part -> {
            assertThat(part.id()).isEqualTo("memory");
            assertThat(part.status()).isEqualTo("completed");
            assertThat(part.summary()).isEqualTo(turn.tools().getFirst().summary());
        });
    }

    @Test
    void cancellationFinishesOnMemoryTimeoutAndLateStorageCannotChangeHistory() {
        var clock = reactor.test.scheduler.VirtualTimeScheduler.getOrSet();
        try {
            var memories = mock(run.halo.aipagebuilder.memory.SiteMemoryService.class);
            when(memories.load()).thenReturn(Mono.just(PageMemoryToolsTest.snapshot(1)));
            var saved = Sinks.<run.halo.aipagebuilder.model.SiteMemory>one();
            var storageCancelled = new java.util.concurrent.atomic.AtomicBoolean();
            when(memories.apply(any(), any(), any()))
                .thenReturn(saved.asMono().doOnCancel(() -> storageCancelled.set(true)));
            var chat = new PageChatService(client, pages, gateway, policy,
                mock(run.halo.aipagebuilder.theme.ThemeContextService.class), null, memories);
            when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> {
                PageWorkingCopy copy = i.getArgument(3);
                return Flux.concat(Flux.just(Map.<String, Object>of("type", "tool-input-start",
                    "toolCallId", "memory", "toolName", PageMemoryTools.NAME)),
                    copy.updateMemory("memory", PageMemoryToolsTest.input(1, runId + "/prompt", "0571-12345678"))
                        .thenMany(Flux.never()));
            });
            var events = chat.start("page", new ChatApi.Request(runId, 1L, "model", "0571-12345678"), "admin")
                .block().collectList().toFuture();
            var cancelled = chat.cancel("page", runId).toFuture();
            clock.advanceTimeBy(PageMemoryTools.SAVE_TIMEOUT);
            assertThat(cancelled.join().status()).isEqualTo("cancelled");
            assertThat(events.join()).extracting(event -> event.get("type")).contains("abort");
            var turn = chat.session("page").block().turns().getFirst();
            assertThat(turn.parts()).singleElement().satisfies(part -> {
                assertThat(part.status()).isEqualTo("error");
                assertThat(part.summary()).contains("结果待确认");
            });
            assertThat(storageCancelled.get()).isFalse();
            saved.tryEmitValue(PageMemoryToolsTest.snapshot(2));
            assertThat(chat.session("page").block().turns().getFirst()).isEqualTo(turn);
            org.mockito.Mockito.doReturn(Flux.empty()).when(gateway)
                .chat(anyString(), any(), anyString(), any());
            chat.start("page", new ChatApi.Request(UUID.randomUUID().toString(), 1L, "model", "继续"), "admin")
                .block().blockLast();
            verify(memories, times(1)).apply(any(), any(), any());
        } finally {
            reactor.test.scheduler.VirtualTimeScheduler.reset();
        }
    }

    @Test
    void rejectsStaleAnnotationsBeforeCallingModel() {
        var annotation = new ChatApi.Annotation("a", List.of(0), "p", "old", "<p>old</p>", "修改这一段");
        var annotated = new ChatApi.Request(runId, 1L, "model", "按批注修改", "stale", List.of(annotation));
        assertThatThrownBy(() -> service.start("page", annotated, "admin").block()).hasMessageContaining("409");
        verify(gateway, never()).chat(anyString(), any(), anyString(), any());
        verify(client, never()).create(any(PageChatSession.class));
    }

    @Test
    void passesCurrentAnnotationsToReadButPersistsOnlyDisplaySummary() {
        var annotation = new ChatApi.Annotation("a", List.of(0), "p", "old", "private-dom-marker", "修改这一段");
        var annotated = new ChatApi.Request(runId, 1L, "model", "按批注修改",
            DocumentHash.of(fixture.base.document().html(), fixture.base.document().css()), List.of(annotation));
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> Flux.defer(() -> {
            var copy = (PageWorkingCopy) i.getArgument(3);
            assertThat(copy.read().get("annotations")).isEqualTo(List.of(annotation));
            return Flux.empty();
        }));
        service.start("page", annotated, "admin").block().blockLast();
        var turn = service.session("page").block().turns().getFirst();
        assertThat(turn.annotations().getFirst().comment()).isEqualTo("修改这一段");
        assertThat(turn.annotations().getFirst().path()).isEmpty();
        assertThat(Json.toJson(turn)).doesNotContain("private-dom-marker");
    }

    @Test
    void failureAndCancellationDiscardWorkingCopy() {
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> Flux.defer(() -> {
            return ((PageWorkingCopy) i.getArgument(3))
                .update(Map.of("html", "<p>new</p>", "css", ""))
                .thenMany(Flux.error(new IllegalStateException("provider-secret")));
        }));
        var events = service.start("page", request(), "admin").block().collectList().block();
        assertThat(events).extracting(value -> value.get("type")).containsExactly("error");
        assertThat(events.toString()).doesNotContain("provider-secret");
        verify(pages, never()).saveAi(anyString(), any(), any(), anyString(), anyString(), anyString(), any());

        var nextId = UUID.randomUUID().toString();
        when(gateway.chat(anyString(), any(), anyString(), any())).thenReturn(Flux.never());
        var stream = service.start("page", new ChatApi.Request(nextId, 1L, "model", "修改"), "admin").block();
        assertThat(service.cancel("page", nextId).block().status()).isEqualTo("cancelled");
        assertThat(stream.collectList().block()).extracting(value -> value.get("type")).containsExactly("abort");
        verify(pages, never()).saveAi(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void cancellationAfterCommitBoundaryCannotClaimCancelled() {
        var saved = Sinks.<PageApi.PageDetail>one();
        when(pages.saveAi(eq("page"), eq(1L), any(), eq("admin"), eq(runId), eq("old-revision"), any())).thenReturn(saved.asMono());
        stagedSuccess();
        var stream = service.start("page", request(), "admin").block();
        assertThat(service.cancel("page", runId).block().status()).isEqualTo("committing");
        saved.tryEmitValue(fixture.changed);
        assertThat(stream.collectList().block()).extracting(value -> value.get("type"))
            .containsExactly("data-page-committed", "finish");
        assertThat(service.result("page", runId).block().status()).isEqualTo("completed");
    }

    @Test
    void finishingModelWaitsForPersistedSteerBeforeContinuing() {
        var modelDone = Sinks.<Map<String, Object>>one();
        var persisted = Sinks.<PageChatSession>one();
        var candidate = new java.util.concurrent.atomic.AtomicReference<PageChatSession>();
        var copyRef = new java.util.concurrent.atomic.AtomicReference<PageWorkingCopy>();
        var prompts = new java.util.ArrayList<String>();
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> Flux.defer(() -> {
            copyRef.set(i.getArgument(3));
            prompts.add(i.getArgument(2));
            return prompts.size() == 1 ? modelDone.asMono().flux() : Flux.empty();
        }));
        var events = service.start("page", request(), "admin").block().collectList();
        when(client.update(any(PageChatSession.class))).thenAnswer(i -> {
            PageChatSession session = i.getArgument(0);
            if (session.getSpec().getActive() != null
                && session.getSpec().getActive().steers().size() == 1 && candidate.get() == null) {
                candidate.set(session);
                return persisted.asMono();
            }
            session.getMetadata().setVersion(session.getMetadata().getVersion() + 1);
            stored.put(session.getMetadata().getName(), Json.deepCopy(session));
            return Mono.just(session);
        });
        service.steer("page", runId, new ChatApi.SteerRequest("电话改成 0571-12345678"), "admin").subscribe();
        assertThat(copyRef.get().drainSteers()).isEmpty();
        modelDone.tryEmitEmpty();
        assertThat(prompts).hasSize(1);
        var session = candidate.get();
        session.getMetadata().setVersion(session.getMetadata().getVersion() + 1);
        stored.put("page", Json.deepCopy(session));
        persisted.tryEmitValue(session);
        assertThat(events.block()).extracting(value -> value.get("type")).contains("finish");
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(1)).contains("0571-12345678");
    }

    @Test
    void failedSteerIsNeitherDeliveredNorArchived() {
        var done = Sinks.<Map<String, Object>>one();
        var copyRef = new java.util.concurrent.atomic.AtomicReference<PageWorkingCopy>();
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> {
            copyRef.set(i.getArgument(3));
            return done.asMono().flux();
        });
        var events = service.start("page", request(), "admin").block().collectList();
        when(client.update(any(PageChatSession.class))).thenAnswer(i -> {
            PageChatSession session = i.getArgument(0);
            if (session.getSpec().getActive() != null && !session.getSpec().getActive().steers().isEmpty()) {
                return Mono.error(new IllegalStateException("failed steer save"));
            }
            session.getMetadata().setVersion(session.getMetadata().getVersion() + 1);
            stored.put(session.getMetadata().getName(), Json.deepCopy(session));
            return Mono.just(session);
        });
        assertThatThrownBy(() -> service.steer("page", runId,
            new ChatApi.SteerRequest("不要保存这条失败输入"), "admin").block()).hasMessageContaining("failed steer");
        assertThat(copyRef.get().drainSteers()).isEmpty();
        done.tryEmitEmpty();
        events.block();
        assertThat(service.session("page").block().turns().getFirst().steers()).isEmpty();
    }

    @Test
    void leftoverSteerContinuesTheRunAndCommitsOnce() {
        var prompts = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var gate = Sinks.<Map<String, Object>>one();
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> Flux.defer(() -> {
            PageWorkingCopy copy = i.getArgument(3);
            prompts.add(i.getArgument(2));
            if (prompts.size() == 1) {
                return copy.update(Map.of("html", "<p>new</p>", "css", ""))
                    .thenMany(gate.asMono());
            }
            return Flux.just(Map.of("type", "text-delta", "id", "t", "delta", "收到补充"));
        }));
        var events = service.start("page", request(), "admin").block().collectList();
        var run = service.steer("page", runId, new ChatApi.SteerRequest("标题再短一点"), "admin").block();
        assertThat(run.status()).isEqualTo("running");
        gate.tryEmitValue(Map.of("type", "text-delta", "id", "t", "delta", "初稿完成"));
        assertThat(events.block()).extracting(value -> value.get("type"))
            .containsExactly("data-steer", "text-delta", "text-delta", "data-page-committed", "finish");
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(0)).isEqualTo("修改页面");
        assertThat(prompts.get(1)).contains("标题再短一点");
        verify(pages, times(1)).saveAi(eq("page"), eq(1L), any(), eq("admin"), eq(runId), eq("old-revision"), any());
        assertThat(service.session("page").block().turns().getFirst().steers())
            .containsExactly("标题再短一点");
    }

    @Test
    void fourthLeftoverSteerCommitsWithoutAnotherContinuationOrBudgetFailure() {
        var prompts = new CopyOnWriteArrayList<String>();
        var gates = new CopyOnWriteArrayList<Sinks.One<Map<String, Object>>>();
        var copies = new CopyOnWriteArrayList<PageWorkingCopy>();
        when(gateway.chat(anyString(), any(), anyString(), any()))
            .thenAnswer(i -> Flux.defer(() -> {
                PageWorkingCopy copy = i.getArgument(3);
                copies.add(copy);
                prompts.add(i.getArgument(2));
                var gate = Sinks.<Map<String, Object>>one();
                gates.add(gate);
                return prompts.size() == 1
                    ? copy.update(Map.of("html", "<p>new</p>", "css", "")).thenMany(gate.asMono())
                    : gate.asMono().flux();
            }));
        var events = service.start("page", request(), "admin").block().collectList();
        for (int i = 0; i < 4; i++) {
            assertThat(gates).hasSize(i + 1);
            service.steer("page", runId, new ChatApi.SteerRequest("补充 " + i), "admin").block();
            gates.get(i).tryEmitValue(Map.of("type", "text-delta", "id", "t" + i,
                "delta", "完成"));
        }
        assertThat(events.block()).extracting(value -> value.get("type"))
            .endsWith("data-page-committed", "finish").doesNotContain("error");
        assertThat(prompts).hasSize(4);
        for (int i = 1; i < prompts.size(); i++) {
            assertThat(prompts.get(i)).contains("补充 " + (i - 1)).doesNotContain("补充 3");
        }
        assertThat(copies).allSatisfy(copy -> assertThat(copy).isSameAs(copies.getFirst()));
        assertThat(service.result("page", runId).block().status()).isEqualTo("completed");
        verify(pages, times(1)).saveAi(eq("page"), eq(1L), any(), eq("admin"), eq(runId),
            eq("old-revision"), any());
    }

    @Test
    void steerDuringWaitingForUserIsInjectedIntoABoundedContinuation() {
        var prompts = new java.util.concurrent.CopyOnWriteArrayList<String>();
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> Flux.defer(() -> {
            PageWorkingCopy copy = i.getArgument(3);
            prompts.add(i.getArgument(2));
            if (prompts.size() == 1) {
                return copy.update(Map.of("html", "<p>new</p>", "css", ""))
                    .then(copy.askUser(questionInput())).thenMany(Flux.empty());
            }
            return Flux.just(Map.of("type", "text-delta", "id", "t", "delta", "收到补充"));
        }));
        service.start("page", request(), "admin").block();
        var pending = service.session("page").block().activeRun().pendingQuestion();
        var run = service.steer("page", runId, new ChatApi.SteerRequest("标题再短一点"), "admin").block();
        assertThat(run.status()).isEqualTo("waiting_for_user");
        service.answer("page", runId, pending.id(), answerRequest(), "admin").block();
        assertThat(service.result("page", runId).block().status()).isEqualTo("completed");
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(1)).contains("标题再短一点");
        var turn = service.session("page").block().turns().getFirst();
        assertThat(turn.steers()).containsExactly("标题再短一点");
        assertThat(turn.questions().getFirst().answers()).isEqualTo(answerRequest().answers());
        verify(pages, times(1)).saveAi(eq("page"), eq(1L), any(), eq("admin"), eq(runId), eq("old-revision"), any());
    }

    @Test
    void steerRejectsInvalidInputOtherActorsAndEndedRuns() {
        when(gateway.chat(anyString(), any(), anyString(), any())).thenReturn(Flux.never());
        service.start("page", request(), "admin").block();
        assertThatThrownBy(() -> service.steer("page", runId, new ChatApi.SteerRequest("  "), "admin").block())
            .hasMessageContaining("400");
        assertThatThrownBy(() -> service.steer("page", runId, new ChatApi.SteerRequest("x".repeat(2001)), "admin").block())
            .hasMessageContaining("400");
        assertThatThrownBy(() -> service.steer("page", runId, new ChatApi.SteerRequest("补充"), "someone").block())
            .hasMessageContaining("403");
        assertThatThrownBy(() -> service.steer("page", "old-run", new ChatApi.SteerRequest("补充"), "admin").block())
            .hasMessageContaining("409");
        for (var i = 0; i < PageRun.MAX_STEERS; i++) {
            service.steer("page", runId, new ChatApi.SteerRequest("补充 " + i), "admin").block();
        }
        assertThatThrownBy(() -> service.steer("page", runId, new ChatApi.SteerRequest("过多"), "admin").block())
            .hasMessageContaining("409");
        service.cancel("page", runId).block();
        assertThatThrownBy(() -> service.steer("page", runId, new ChatApi.SteerRequest("补充"), "admin").block())
            .hasMessageContaining("409");
    }

    @Test
    void noEditDoesNotSaveAndVersionConflictDoesNotCommit() {
        when(gateway.chat(anyString(), any(), anyString(), any())).thenReturn(Flux.empty());
        service.start("page", request(), "admin").block().blockLast();
        verify(pages, never()).saveAi(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
        assertThatThrownBy(() -> service.start("page", new ChatApi.Request(UUID.randomUUID().toString(),
            0L, "model", "修改"), "admin").block()).hasMessageContaining("409");
    }

    @Test
    void restartMarksRunInterruptedAndDoesNotResumeModel() {
        when(gateway.chat(anyString(), any(), anyString(), any())).thenReturn(Flux.never());
        service.start("page", request(), "admin").block();
        var restarted = new PageChatService(client, pages, gateway, policy,
        mock(run.halo.aipagebuilder.theme.ThemeContextService.class));
        assertThat(restarted.session("page").block().turns().getFirst().status()).isEqualTo("interrupted");
        verify(gateway, times(1)).chat(anyString(), any(), anyString(), any());
    }

    @Test
    void uncertainSaveIsReconciledByRunIdAndNotRepeated() {
        var reads = new AtomicInteger();
        when(pages.committedAiRevision("page", runId)).thenAnswer(i -> Mono.defer(() ->
            reads.incrementAndGet() == 1 ? Mono.empty() : Mono.just("new-revision")));
        when(pages.saveAi(eq("page"), eq(1L), any(), eq("admin"), eq(runId), eq("old-revision"), any()))
            .thenReturn(Mono.error(new IllegalStateException("connection lost after update")));
        stagedSuccess();
        var events = service.start("page", request(), "admin").block().collectList().block();
        assertThat(events).extracting(value -> value.get("type"))
            .containsExactly("data-page-committed", "finish");
        verify(pages, times(1)).saveAi(eq("page"), eq(1L), any(), eq("admin"), eq(runId), eq("old-revision"), any());
    }

    @Test
    void invalidFinalUpdateDiscardsEarlierValidStaging() {
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> Flux.defer(() -> {
            var copy = (PageWorkingCopy) i.getArgument(3);
            return copy.update(Map.of("html", "<p>new</p>", "css", ""))
                .then(copy.update(Map.of("html", "<script>bad</script>", "css", "")))
                .thenMany(Flux.empty());
        }));
        var events = service.start("page", request(), "admin").block().collectList().block();
        assertThat(events).extracting(value -> value.get("type")).containsExactly("error");
        verify(pages, never()).saveAi(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void detachedExecutionPreservesRequestSecurityContext() {
        when(gateway.chat(anyString(), any(), anyString(), any())).thenReturn(Flux.deferContextual(context -> {
            assertThat(context.<String>get("actor-marker")).isEqualTo("admin");
            return Flux.empty();
        }));
        service.start("page", request(), "admin").contextWrite(context -> context.put("actor-marker", "admin"))
            .block().blockLast();
        assertThat(service.result("page", runId).block().status()).isEqualTo("completed");
    }

    @Test
    void contextWindowKeepsNewestWholeTurnsWithoutMutatingHistory() {
        var history = new java.util.ArrayList<ChatApi.Turn>();
        for (int i = 0; i < 30; i++) {
            history.add(new ChatApi.Turn("r" + i, "admin", Instant.now(),
                "问题".repeat(3000), "答案".repeat(3000), List.of(), "completed", null, null));
        }
        var window = PageChatService.contextWindow(history);
        assertThat(window.size()).isLessThanOrEqualTo(20).isGreaterThan(0);
        assertThat(Json.toJson(window).getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
            .isLessThanOrEqualTo(PageChatService.MAX_BYTES);
        assertThat(window.getLast().runId()).isEqualTo("r29");
        assertThat(history).hasSize(30);
    }

    @Test
    void sessionsIsolateHistoryAndDeletionPreservesPageAndOtherSessions() {
        var one = service.createSession("page", new ChatApi.CreateSession("one")).block();
        var two = service.createSession("page", new ChatApi.CreateSession("two")).block();
        when(gateway.chat(anyString(), any(), anyString(), any())).thenReturn(Flux.empty());
        service.start("page", new ChatApi.Request(runId, 1L, "model", "first", null, List.of(), one.sessionId()), "admin")
            .block().blockLast();
        assertThat(service.session("page", two.sessionId(), null, 20).block().turns()).isEmpty();
        assertThat(service.session("page", one.sessionId(), null, 20).block().turns()).hasSize(1);
        var current = service.session("page", one.sessionId(), null, 20).block();
        assertThatThrownBy(() -> service.deleteSession("page", one.sessionId(), one.version()).block())
            .hasMessageContaining("409");
        service.deleteSession("page", one.sessionId(), current.version()).block();
        assertThat(stored).doesNotContainKey(one.sessionId()).containsKeys("page", two.sessionId());
        assertThat(turns).isEmpty();
        verify(pages, never()).delete(anyString(), any());
        assertThat(service.sessions("page").block().items()).hasSize(2);
    }

    @Test
    void pageWideReservationRejectsSecondSessionAndActiveDeletion() {
        var one = service.createSession("page", new ChatApi.CreateSession("one")).block();
        var two = service.createSession("page", new ChatApi.CreateSession("two")).block();
        when(gateway.chat(anyString(), any(), anyString(), any())).thenReturn(Flux.never());
        service.start("page", new ChatApi.Request(runId, 1L, "model", "first", null, List.of(), one.sessionId()), "admin").block();
        assertThat(service.session("page", one.sessionId(), null, 20).block().activeRun().runId()).isEqualTo(runId);
        assertThat(service.session("page", two.sessionId(), null, 20).block().activeRun()).isNull();
        assertThatThrownBy(() -> service.start("page", new ChatApi.Request(UUID.randomUUID().toString(),
            1L, "model", "second", null, List.of(), two.sessionId()), "admin").block()).hasMessageContaining("409");
        assertThatThrownBy(() -> service.deleteSession("page", one.sessionId(), one.version()).block())
            .hasMessageContaining("409");
        service.cancel("page", runId).block();
    }

    @Test
    void rejectsCrossPageSessionAndDefaultDeletionClearsHistory() {
        service.session("page").block();
        var foreign = Json.deepCopy(stored.get("page"));
        foreign.getMetadata().setName("foreign");
        foreign.getSpec().setPageName("other");
        stored.put("foreign", foreign);
        assertThatThrownBy(() -> service.session("page", "foreign", null, 20).block()).hasMessageContaining("404");
        when(gateway.chat(anyString(), any(), anyString(), any())).thenReturn(Flux.empty());
        service.start("page", request(), "admin").block().blockLast();
        var current = service.session("page").block();
        service.deleteSession("page", "page", current.version()).block();
        assertThat(service.session("page").block().turns()).isEmpty();
        assertThat(stored).containsKeys("page", "foreign");
        assertThat(turns).isEmpty();
    }

    @Test
    void recoversDeletionReservationAfterTargetWasDeleted() {
        service.session("page").block();
        stored.get("page").getSpec().setDeletingSessionId("already-deleted");
        assertThat(service.session("page").block().sessionId()).isEqualTo("page");
        assertThat(stored.get("page").getSpec().getDeletingSessionId()).isNull();
    }

    @Test
    void sendsOnlyNewestTwentyTurnsToModelWhileKeepingAllArchivedTurns() {
        service.session("page").block();
        for (int i = 0; i < 25; i++) {
            stored.get("page").getSpec().getTurns().add(new ChatApi.Turn("r" + i, "admin",
                Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i), "prompt", "answer",
                List.of(), "completed", null, null));
        }
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> {
            List<ChatApi.Turn> history = i.getArgument(1);
            assertThat(history).hasSize(20);
            assertThat(history.getFirst().runId()).isEqualTo("r5");
            return Flux.empty();
        });
        service.start("page", request(), "admin").block().blockLast();
        assertThat(turns).hasSize(26);
        assertThat(stored.get("page").getSpec().getTurns()).isEmpty();
    }

    @Test
    void waitingSurvivesRefreshAndBothDeadlinesThenResumesSameStagedCopy() {
        var clock = reactor.test.scheduler.VirtualTimeScheduler.getOrSet();
        try {
            when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> Flux.defer(() -> {
                PageWorkingCopy copy = i.getArgument(3);
                return copy.update(Map.of("html", "<p>new</p>", "css", ""))
                    .then(copy.askUser(questionInput())).doOnNext(answer -> {
                    assertThat(copy.document().html()).isEqualTo("<p>new</p>");
                    assertThat(stored.get("page").getSpec().getActive().questions().getLast().answers())
                        .hasSize(1);
                }).thenMany(Flux.just(Map.<String, Object>of("type", "text-delta", "id", "t", "delta", "继续")))
                    .timeout(copy.timeBudget.deadline(FoundationBridge.RESPONSE_TIMEOUT,
                        AiGenerationException.Reason.RESPONSE_TIMEOUT), ignored -> Mono.never());
            }));
            var browser = service.start("page", request(), "admin").block().subscribe();
            var firstSession = service.session("page").block();
            var pending = firstSession.activeRun().pendingQuestion();
            assertThat(firstSession.turns()).hasSize(1);
            assertThat(firstSession.turns().getFirst().runId()).isEqualTo(runId);
            assertThat(firstSession.turns().getFirst().status()).isEqualTo("waiting_for_user");
            assertThat(firstSession.turns().getFirst().questions()).containsExactly(pending);
            browser.dispose();
            clock.advanceTimeBy(java.time.Duration.ofMinutes(20));
            assertThat(service.result("page", runId).block().status()).isEqualTo("waiting_for_user");
            assertThat(service.session("page").block().activeRun().pendingQuestion()).isEqualTo(pending);
            verify(pages, never()).saveAi(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
            service.answer("page", runId, pending.id(), answerRequest(), "admin").block();
            assertThat(service.result("page", runId).block().status()).isEqualTo("completed");
            var turn = service.session("page").block().turns().getFirst();
            assertThat(turn.questions().getFirst().answers()).isEqualTo(answerRequest().answers());
            verify(pages).saveAi(eq("page"), eq(1L), any(), eq("admin"), eq(runId), eq("old-revision"), any());
        } finally {
            reactor.test.scheduler.VirtualTimeScheduler.reset();
        }
    }

    @Test
    void latestSessionIncludesLiveTurnWithoutLeakingIntoOtherSessionsOrOlderPages() {
        var selected = service.createSession("page", new ChatApi.CreateSession("selected")).block();
        var other = service.createSession("page", new ChatApi.CreateSession("other")).block();
        when(gateway.chat(anyString(), any(), anyString(), any())).thenReturn(Flux.empty());
        var previousId = UUID.randomUUID().toString();
        service.start("page", new ChatApi.Request(previousId, 1L, "model", "previous", null,
            List.of(), selected.sessionId()), "admin").block().blockLast();
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> {
            PageWorkingCopy copy = i.getArgument(3);
            return Flux.concat(Flux.just(Map.<String, Object>of("type", "text-delta", "id", "t",
                "delta", "提问前的解释")), copy.askUser(questionInput()).thenMany(Flux.just(
                    Map.<String, Object>of("type", "text-delta", "id", "t", "delta", "回答后的解释"))),
                Flux.never());
        });
        service.start("page", new ChatApi.Request(runId, 1L, "model", "current", null,
            List.of(), selected.sessionId()), "admin").block();
        var latest = service.session("page", selected.sessionId(), null, 1).block();
        assertThat(latest.turns()).extracting(ChatApi.Turn::runId).containsExactly(previousId, runId);
        assertThat(latest.turns().getLast().text()).isEqualTo("提问前的解释");
        assertThat(latest.turns().getLast().questions()).containsExactly(latest.activeRun().pendingQuestion());
        assertThat(service.session("page", other.sessionId(), null, 20).block().turns()).isEmpty();
        assertThat(service.session("page", null, null, 20).block().turns()).isEmpty();
        assertThat(service.session("page", selected.sessionId(), "9999", 20).block().turns())
            .extracting(ChatApi.Turn::runId).containsExactly(previousId);
        service.answer("page", runId, latest.activeRun().pendingQuestion().id(), answerRequest(), "admin").block();
        var resumed = service.session("page", selected.sessionId(), null, 20).block();
        assertThat(resumed.turns().getLast().text()).isEqualTo("提问前的解释回答后的解释");
        assertThat(resumed.turns().getLast().questions().getFirst().answers()).isEqualTo(answerRequest().answers());
        service.cancel("page", runId).block();
        assertThat(service.session("page", selected.sessionId(), null, 20).block().turns())
            .extracting(ChatApi.Turn::runId).containsExactly(previousId, runId);
    }

    @Test
    void questionAndAnswerEventsCarrySdkDynamicDataNamesAndStableIds() {
        waitingGateway(Flux.never());
        var events = new java.util.ArrayList<Map<String, Object>>();
        var subscription = service.start("page", request(), "admin").block().subscribe(events::add);
        var question = service.result("page", runId).block().pendingQuestion();
        assertThat(events).singleElement().satisfies(event -> assertThat(event)
            .containsEntry("type", "data-user-question")
            .containsEntry("name", "user-question")
            .containsEntry("id", question.id())
            .containsEntry("data", Map.of("runId", runId, "question", question)));
        service.answer("page", runId, question.id(), answerRequest(), "admin").block();
        assertThat(events).hasSize(2);
        var answered = stored.get("page").getSpec().getActive().questions().getLast();
        assertThat(events.getLast()).containsEntry("type", "data-user-answer")
            .containsEntry("name", "user-answer")
            .containsEntry("id", question.id())
            .containsEntry("data", Map.of("runId", runId, "question", answered));
        service.cancel("page", runId).block();
        subscription.dispose();
    }

    @Test
    void answersRejectOtherActorsWrongPagesStaleQuestionsAndDuplicates() {
        waitingGateway(Flux.never());
        service.start("page", request(), "admin").block();
        var question = service.result("page", runId).block().pendingQuestion();
        when(pages.get("other")).thenReturn(Mono.just(fixture.base));
        assertThatThrownBy(() -> service.answer("other", runId, question.id(), answerRequest(), "admin").block())
            .hasMessageContaining("409");
        assertThatThrownBy(() -> service.answer("page", "old-run", question.id(), answerRequest(), "admin").block())
            .hasMessageContaining("409");
        assertThatThrownBy(() -> service.answer("page", runId, question.id(), answerRequest(), "someone").block())
            .hasMessageContaining("403");
        assertThatThrownBy(() -> service.answer("page", runId, "old-question", answerRequest(), "admin").block())
            .hasMessageContaining("409");
        assertThatThrownBy(() -> service.answer("page", runId, question.id(),
            new ChatApi.AnswerRequest(List.of(new ChatApi.AnswerItem("style", "invented", null))), "admin").block())
            .hasMessageContaining("400");
        assertThat(service.result("page", runId).block().status()).isEqualTo("waiting_for_user");
        service.answer("page", runId, question.id(), answerRequest(), "admin").block();
        assertThatThrownBy(() -> service.answer("page", runId, question.id(), answerRequest(), "admin").block())
            .hasMessageContaining("409");
        service.cancel("page", runId).block();
    }

    @Test
    void cancellingWaitReleasesReservationAndNeverSavesWorkingCopy() {
        waitingGateway(Flux.empty());
        service.start("page", request(), "admin").block();
        var session = service.session("page").block();
        var question = session.activeRun().pendingQuestion();
        assertThatThrownBy(() -> service.deleteSession("page", "page", session.version()).block())
            .hasMessageContaining("409");
        assertThatThrownBy(() -> service.start("page", new ChatApi.Request(UUID.randomUUID().toString(),
            1L, "model", "second"), "admin").block()).hasMessageContaining("409");
        assertThat(service.cancel("page", runId).block().status()).isEqualTo("cancelled");
        assertThat(service.session("page").block().activeRun()).isNull();
        assertThat(service.session("page").block().turns().getFirst().questions()).hasSize(1);
        assertThatThrownBy(() -> service.answer("page", runId, question.id(), answerRequest(), "admin").block())
            .hasMessageContaining("409");
        verify(pages, never()).saveAi(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void totalDeadlineKeepsPreWaitElapsedTimeAfterAnswer() {
        var clock = reactor.test.scheduler.VirtualTimeScheduler.getOrSet();
        try {
            when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> {
                PageWorkingCopy copy = i.getArgument(3);
                return Mono.delay(java.time.Duration.ofSeconds(100))
                    .then(copy.askUser(questionInput())).thenMany(Flux.never());
            });
            service.start("page", request(), "admin").block();
            clock.advanceTimeBy(java.time.Duration.ofSeconds(100));
            var question = service.result("page", runId).block().pendingQuestion();
            clock.advanceTimeBy(java.time.Duration.ofMinutes(20));
            service.answer("page", runId, question.id(), answerRequest(), "admin").block();
            clock.advanceTimeBy(java.time.Duration.ofSeconds(499));
            assertThat(service.result("page", runId).block().status()).isEqualTo("running");
            clock.advanceTimeBy(java.time.Duration.ofSeconds(1));
            assertThat(service.result("page", runId).block().error())
                .isEqualTo(AiGenerationException.Reason.RUN_TIMEOUT.message);
        } finally {
            reactor.test.scheduler.VirtualTimeScheduler.reset();
        }
    }

    @Test
    void restartInterruptsWaitingRunAndPreservesQuestionWithoutReplayingTools() {
        waitingGateway(Flux.empty());
        service.start("page", request(), "admin").block();
        var pending = service.result("page", runId).block().pendingQuestion();
        var restarted = new PageChatService(client, pages, gateway, policy,
            mock(run.halo.aipagebuilder.theme.ThemeContextService.class));
        var recovered = restarted.session("page").block();
        assertThat(recovered.activeRun()).isNull();
        assertThat(recovered.turns().getFirst().status()).isEqualTo("interrupted");
        assertThat(recovered.turns().getFirst().questions()).containsExactly(pending);
        assertThatThrownBy(() -> restarted.answer("page", runId, pending.id(), answerRequest(), "admin").block())
            .hasMessageContaining("409");
        verify(gateway, times(1)).chat(anyString(), any(), anyString(), any());
        verify(pages, never()).saveAi(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void failedQuestionPersistenceFailsRunEvenWhenSdkConvertsToolErrorsToResults() {
        var updates = new AtomicInteger();
        doAnswer(i -> {
            PageChatSession session = i.getArgument(0);
            if (updates.incrementAndGet() == 2) return Mono.error(new IllegalStateException("storage"));
            return Mono.fromSupplier(() -> {
                session.getMetadata().setVersion(session.getMetadata().getVersion() + 1);
                stored.put(session.getMetadata().getName(), Json.deepCopy(session));
                return session;
            });
        }).when(client).update(any(PageChatSession.class));
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> Flux.defer(() -> {
            PageWorkingCopy copy = i.getArgument(3);
            return copy.update(Map.of("html", "<p>new</p>", "css", ""))
                .then(copy.askUser(questionInput())).onErrorResume(error -> Mono.just(Map.of("error", "tool failed")))
                .thenMany(Flux.never());
        }));
        service.start("page", request(), "admin").block();
        assertThat(service.result("page", runId).block().status()).isEqualTo("failed");
        assertThat(service.session("page").block().activeRun()).isNull();
        verify(pages, never()).saveAi(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void failedAnswerPersistenceCannotResumeModelOrSaveStagedContent() {
        waitingGateway(Flux.empty());
        service.start("page", request(), "admin").block();
        var question = service.result("page", runId).block().pendingQuestion();
        var updates = new AtomicInteger();
        doAnswer(i -> {
            PageChatSession session = i.getArgument(0);
            if (updates.incrementAndGet() == 1) return Mono.error(new IllegalStateException("storage"));
            return Mono.fromSupplier(() -> {
                session.getMetadata().setVersion(session.getMetadata().getVersion() + 1);
                stored.put(session.getMetadata().getName(), Json.deepCopy(session));
                return session;
            });
        }).when(client).update(any(PageChatSession.class));
        assertThatThrownBy(() -> service.answer("page", runId, question.id(), answerRequest(), "admin").block())
            .hasMessageContaining("storage");
        assertThat(service.result("page", runId).block().status()).isEqualTo("failed");
        verify(pages, never()).saveAi(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void validatesQuestionShapeAndFreeTextAnswers() {
        var free = UserQuestions.parse(Map.of("questions", List.of(Map.of(
            "id", "copy", "title", "请输入文案", "options", List.of(), "allowCustomAnswer", true))));
        assertThat(UserQuestions.answer(free, new ChatApi.AnswerRequest(List.of(
            new ChatApi.AnswerItem("copy", null, "新文案")))).answers().getFirst().text()).isEqualTo("新文案");
        assertThatThrownBy(() -> UserQuestions.answer(free, new ChatApi.AnswerRequest(List.of(
            new ChatApi.AnswerItem("copy", null, "x".repeat(2001)))))).hasMessageContaining("400");
        assertThatThrownBy(() -> UserQuestions.parse(Map.of("questions", List.of(Map.of(
            "id", "copy", "title", "文案", "options", List.of(), "allowCustomAnswer", false)))))
            .hasMessageContaining("400");
        assertThatThrownBy(() -> UserQuestions.parse(Map.of("questions", List.of(
            questionInput().get("questions"), questionInput().get("questions"),
            questionInput().get("questions"), questionInput().get("questions")))))
            .hasMessageContaining("400");
    }

    @Test
    void persistsValidatedEditsAndResumesWithFreshBudgetsWithoutCommittingPartialWork() {
        org.mockito.Mockito.doAnswer(i -> {
            PageWorkingCopy copy = i.getArgument(3);
            copy.budget.nextStep();
            copy.budget.usage(100, 20);
            copy.budget.recover();
            copy.preview("preview");
            copy.budget.requireContextFits(128);
            for (int continuation = 0; continuation < 3; continuation++) {
                assertThat(copy.budget.tryContinuation(true)).isTrue();
            }
            return copy.update(Map.of("html", "<p>saved progress</p>"))
                .thenMany(Flux.error(new AiGenerationException(AiGenerationException.Reason.STEP_LIMIT)));
        }).when(gateway).chat(anyString(), any(), anyString(), any());
        service.start("page", request(), "admin").block().collectList().block();
        var result = service.result("page", runId).block();
        assertThat(result.status()).isEqualTo("incomplete");
        assertThat(result.recovery().canResume()).isTrue();
        assertThat(service.session("page").block().recovery()).isEqualTo(result.recovery());
        var checkpoint = service.checkpoint("page", "page", "admin").block();
        assertThat(checkpoint.document().html()).isEqualTo("<p>saved progress</p>");
        verify(pages, never()).saveAi(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
        var next = UUID.randomUUID().toString();
        when(pages.saveAi(eq("page"), eq(1L), any(), eq("admin"), eq(next), eq("old-revision"), any()))
            .thenReturn(Mono.just(fixture.changed));
        org.mockito.Mockito.doAnswer(i -> {
            PageWorkingCopy copy = i.getArgument(3);
            assertThat(copy.document().html()).isEqualTo("<p>saved progress</p>");
            assertThat(copy.calls()).isZero();
            assertThat(copy.budget.steps()).isZero();
            assertThat(copy.budget.recoveries()).isZero();
            var usage = copy.usage();
            assertThat(usage.inputTokens()).isNull();
            assertThat(usage.outputTokens()).isNull();
            assertThat(usage.toolBytes()).isZero();
            assertThat(usage.previewBytes()).isZero();
            assertThat(usage.maxContextBytes()).isZero();
            for (int continuation = 0; continuation < 3; continuation++) {
                assertThat(copy.budget.tryContinuation(true)).isTrue();
            }
            assertThat(copy.budget.tryContinuation(true)).isFalse();
            assertThat(i.<String>getArgument(2)).isEqualTo(request().prompt());
            assertThat(copy.restored()).isTrue();
            assertThat(copy.recoveryContext()).contains("Original request", "saved progress");
            return Flux.empty();
        }).when(gateway).chat(anyString(), any(), anyString(), any());
        service.start("page", resumeRequest(checkpoint, next), "admin").block().collectList().block();
        assertThat(service.result("page", next).block().status()).isEqualTo("completed");
        assertThat(checkpoints).isEmpty();
        verify(pages).saveAi(eq("page"), eq(1L), any(), eq("admin"), eq(next), eq("old-revision"), any());
    }

    @Test
    void initialCheckpointFailureDoesNotPromiseRecoveryOrCallTheModel() {
        when(client.create(any(run.halo.aipagebuilder.model.PageChatCheckpoint.class)))
            .thenReturn(Mono.error(new IllegalStateException("storage unavailable")));
        service.start("page", request(), "admin").block().collectList().block();
        var result = service.result("page", runId).block();
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.error()).contains("进度保存失败", "重试").doesNotContain("从上次已保存");
        assertThat(result.recovery()).isNull();
        assertThat(checkpoints).isEmpty();
        verify(gateway, never()).chat(anyString(), any(), anyString(), any());
        verify(pages, never()).saveAi(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void checkpointFailurePreventsToolAcknowledgementAndDraftCommitButKeepsPreviousSnapshot() {
        // Fail only snapshot writes, still allow making the last durable checkpoint available.
        when(client.update(any(run.halo.aipagebuilder.model.PageChatCheckpoint.class))).thenAnswer(i -> {
            run.halo.aipagebuilder.model.PageChatCheckpoint value = i.getArgument(0);
            if (value.getSpec().getDocument().html().contains("not persisted")) return Mono.error(new IllegalStateException("storage"));
            value.getMetadata().setVersion(value.getMetadata().getVersion() + 1);
            checkpoints.put(value.getMetadata().getName(), Json.deepCopy(value));
            return Mono.just(value);
        });
        org.mockito.Mockito.doAnswer(i -> {
            PageWorkingCopy copy = i.getArgument(3);
            return copy.update(Map.of("html", "<p>persisted</p>"))
                .then(copy.update(Map.of("html", "<p>not persisted</p>")))
                .onErrorResume(error -> Mono.empty()).thenMany(Flux.empty());
        }).when(gateway).chat(anyString(), any(), anyString(), any());
        service.start("page", request(), "admin").block().collectList().block();
        assertThat(service.result("page", runId).block().status()).isEqualTo("failed");
        assertThat(service.checkpoint("page", "page", "admin").block().document().html())
            .isEqualTo("<p>persisted</p>");
        verify(pages, never()).saveAi(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void resumeRejectsChangedVersionOwnerModelAndStaleCheckpointWithoutCallingModel() {
        when(gateway.chat(anyString(), any(), anyString(), any())).thenReturn(
            Flux.error(new AiGenerationException(AiGenerationException.Reason.TRUNCATED)));
        service.start("page", request(), "admin").block().collectList().block();
        var saved = service.checkpoint("page", "page", "admin").block();
        var resume = resumeRequest(saved, UUID.randomUUID().toString());
        org.mockito.Mockito.clearInvocations(gateway);
        assertThatThrownBy(() -> service.start("page", resume, "other").block())
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.start("page", new ChatApi.Request(resume.runId(), 1L,
            "different-model", resume.prompt(), resume.baseHash(), resume.annotations(), "page",
            runId, resume.checkpointVersion()), "admin").block()).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.start("page", new ChatApi.Request(resume.runId(), 1L,
            resume.modelName(), resume.prompt(), resume.baseHash(), resume.annotations(), "page",
            runId, -1L), "admin").block()).isInstanceOf(ResponseStatusException.class);
        when(pages.get("page")).thenReturn(Mono.just(fixture.changed));
        assertThat(service.checkpoint("page", "page", "admin").block().recovery().canResume()).isFalse();
        assertThatThrownBy(() -> service.start("page", resume, "admin").block())
            .isInstanceOf(ResponseStatusException.class);
        verify(gateway, never()).chat(anyString(), any(), anyString(), any());
    }

    @Test
    void stickyInvalidUpdateSurvivesManualResumeAndCannotBeCommittedByStop() {
        org.mockito.Mockito.doAnswer(i -> {
            PageWorkingCopy copy = i.getArgument(3);
            return copy.update(Map.of("html", "<p>valid</p>"))
                .then(copy.update(Map.of("html", "<script>bad()</script>")))
                .thenMany(Flux.error(new AiGenerationException(AiGenerationException.Reason.TRUNCATED)));
        }).when(gateway).chat(anyString(), any(), anyString(), any());
        service.start("page", request(), "admin").block().collectList().block();
        var saved = service.checkpoint("page", "page", "admin").block();
        assertThat(saved.document().html()).isEqualTo("<p>valid</p>");
        org.mockito.Mockito.doReturn(Flux.empty()).when(gateway).chat(anyString(), any(), anyString(), any());
        var next = UUID.randomUUID().toString();
        service.start("page", resumeRequest(saved, next), "admin").block().collectList().block();
        assertThat(service.result("page", next).block().error())
            .isEqualTo(AiGenerationException.Reason.INVALID_UPDATE.message);
        verify(pages, never()).saveAi(anyString(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void restartExposesDurableProgressWithoutInvokingModelAndCancelledRunDeletesIt() {
        org.mockito.Mockito.doAnswer(i -> {
            PageWorkingCopy copy = i.getArgument(3);
            return copy.update(Map.of("html", "<p>before restart</p>"))
                .thenMany(Flux.never());
        }).when(gateway).chat(anyString(), any(), anyString(), any());
        service.start("page", request(), "admin").block();
        org.mockito.Mockito.clearInvocations(gateway);
        var restarted = new PageChatService(client, pages, gateway, policy,
            themes);
        assertThat(restarted.result("page", runId).block().status()).isEqualTo("interrupted");
        var saved = restarted.checkpoint("page", "page", "admin").block();
        assertThat(saved.document().html()).isEqualTo("<p>before restart</p>");
        assertThat(saved.recovery().canResume()).isTrue();
        verify(gateway, never()).chat(anyString(), any(), anyString(), any());
        var next = UUID.randomUUID().toString();
        var resume = resumeRequest(saved, next);
        restarted.start("page", resume, "admin").block();
        assertThat(restarted.start("page", resume, "admin").block()).isNotNull();
        verify(gateway, times(1)).chat(anyString(), any(), anyString(), any());
        restarted.cancel("page", next).block();
        assertThat(checkpoints).isEmpty();
    }

    @Test
    void resumedMemoryLoadsFreshFactsButDoesNotReuseHistoricalPromptAsWriteEvidence() {
        var memories = mock(run.halo.aipagebuilder.memory.SiteMemoryService.class);
        when(memories.load()).thenReturn(Mono.just(PageMemoryToolsTest.snapshot(1)));
        var chat = new PageChatService(client, pages, gateway, policy,
            mock(run.halo.aipagebuilder.theme.ThemeContextService.class), null, memories);
        when(gateway.chat(anyString(), any(), anyString(), any())).thenReturn(
            Flux.error(new AiGenerationException(AiGenerationException.Reason.TRUNCATED)));
        chat.start("page", new ChatApi.Request(runId, 1L, "model", "公司电话 0571-12345678"), "admin")
            .block().blockLast();
        var saved = chat.checkpoint("page", "page", "admin").block();
        var next = UUID.randomUUID().toString();
        org.mockito.Mockito.doAnswer(i -> {
            PageWorkingCopy copy = i.getArgument(3);
            assertThat(i.<String>getArgument(2)).contains("0571-12345678");
            assertThat(copy.memoryReference()).doesNotContain(next + "/prompt", runId + "/prompt");
            return copy.updateMemory(PageMemoryToolsTest.input(1, next + "/prompt", "0571-12345678"))
                .doOnNext(output -> assertThat(output).containsKey("error"))
                .thenMany(Flux.error(new AiGenerationException(AiGenerationException.Reason.TRUNCATED)));
        }).when(gateway).chat(anyString(), any(), anyString(), any());
        chat.start("page", resumeRequest(saved, next), "admin").block().blockLast();
        verify(memories, times(2)).load();
        verify(memories, never()).apply(any(), any(), any());
    }

    @Test
    void sessionDeletionRemovesTheIndependentCheckpoint() {
        when(gateway.chat(anyString(), any(), anyString(), any())).thenReturn(
            Flux.error(new AiGenerationException(AiGenerationException.Reason.TRUNCATED)));
        service.start("page", request(), "admin").block().blockLast();
        assertThat(checkpoints).hasSize(1);
        service.deleteSession("page", "page", service.session("page").block().version()).block();
        assertThat(checkpoints).isEmpty();
        assertThat(service.session("page").block().recovery()).isNull();
    }

    @Test
    void completedRunDoesNotExposeCheckpointWhileHaloGarbageCollectionIsPending() {
        when(client.delete(any(run.halo.aipagebuilder.model.PageChatCheckpoint.class))).thenAnswer(i -> {
            run.halo.aipagebuilder.model.PageChatCheckpoint value = i.getArgument(0);
            value.getMetadata().setDeletionTimestamp(Instant.now());
            checkpoints.put(value.getMetadata().getName(), Json.deepCopy(value));
            return Mono.just(value);
        });
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> {
            PageWorkingCopy copy = i.getArgument(3);
            return copy.update(Map.of("html", "<p>complete</p>")).thenMany(Flux.empty());
        });
        service.start("page", request(), "admin").block().blockLast();
        assertThat(checkpoints).hasSize(1);
        assertThat(service.result("page", runId).block().recovery()).isNull();
        assertThat(service.session("page").block().recovery()).isNull();
        assertThatThrownBy(() -> service.checkpoint("page", "page", "admin").block())
            .isInstanceOf(ResponseStatusException.class);
    }

    private ChatApi.Request resumeRequest(ChatApi.CheckpointDetail checkpoint, String next) {
        var request = checkpoint.request();
        return new ChatApi.Request(next, request.version(), request.modelName(), request.prompt(),
            request.baseHash(), request.annotations(), "page", request.runId(),
            checkpoint.recovery().checkpointVersion());
    }

    private void waitingGateway(Flux<Map<String, Object>> afterAnswer) {
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> Flux.defer(() -> {
            PageWorkingCopy copy = i.getArgument(3);
            return copy.update(Map.of("html", "<p>new</p>", "css", ""))
                .then(copy.askUser(questionInput())).thenMany(afterAnswer);
        }));
    }

    private static Map<String, Object> questionInput() {
        return Map.of("questions", List.of(Map.of("id", "style", "title", "选择风格", "options",
            List.of(Map.of("id", "simple", "label", "简洁"), Map.of("id", "rich", "label", "丰富")),
            "allowCustomAnswer", true)));
    }

    private static ChatApi.AnswerRequest answerRequest() {
        return new ChatApi.AnswerRequest(List.of(new ChatApi.AnswerItem("style", "simple", "保留留白")));
    }

    private void stagedSuccess() {
        when(gateway.chat(anyString(), any(), anyString(), any())).thenAnswer(i -> Flux.defer(() -> {
            return ((PageWorkingCopy) i.getArgument(3))
                .update(Map.of("html", "<p>new</p>", "css", "")).thenMany(Flux.empty());
        }));
    }

    private ChatApi.Request request() { return new ChatApi.Request(runId, 1L, "model", "修改页面"); }

    private static final class PageDetailFixture {
        final PageApi.PageDetail base = page("old-revision", 1L, "old");
        final PageApi.PageDetail changed = page("new-revision", 2L, "new");
        private static PageApi.PageDetail page(String revision, Long version, String text) {
            return new PageApi.PageDetail(new PageApi.PageSummary("page", "page", "Title", "", version, true,
                revision, null, Instant.now(), true), new PageDocument(1, "code", "", Map.of(),
                "<p>" + text + "</p>", "", "Title", ""));
        }
    }
}
