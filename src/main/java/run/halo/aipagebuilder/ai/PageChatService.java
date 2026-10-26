package run.halo.aipagebuilder.ai;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import run.halo.aipagebuilder.model.PageChatCheckpoint;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.aipagebuilder.Json;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageChatSession;
import run.halo.aipagebuilder.page.PageApi.PageDetail;
import run.halo.aipagebuilder.page.PageService;

@Service
public class PageChatService {
    static final int MAX_TURNS = 20;
    static final int MAX_BYTES = 256 * 1024;
    static final Duration RUN_TIMEOUT = Duration.ofMinutes(10);
    private final ReactiveExtensionClient client;
    private final PageService pages;
    private final PageCheckpoints checkpoints;
    private final ConversationTurns conversations;
    private final AiGateway gateway;
    private final ContentPolicy policy;
    private final run.halo.aipagebuilder.theme.ThemeContextService themes;
    private final PageReferenceTools referenceTools;
    private final run.halo.aipagebuilder.memory.SiteMemoryService memories;
    private final PageRunCoordinator runs = new PageRunCoordinator();
    private final Map<String, Mono<PageChatSession>> sessionLoads = new java.util.concurrent.ConcurrentHashMap<>();

    public PageChatService(ReactiveExtensionClient client, PageService pages, AiGateway gateway,
        ContentPolicy policy, run.halo.aipagebuilder.theme.ThemeContextService themes) {
        this(client, pages, gateway, policy, themes, null);
    }

    public PageChatService(ReactiveExtensionClient client, PageService pages, AiGateway gateway,
        ContentPolicy policy, run.halo.aipagebuilder.theme.ThemeContextService themes,
        PageReferenceTools referenceTools) {
        this(client, pages, gateway, policy, themes, referenceTools, null);
    }

    @Autowired
    public PageChatService(ReactiveExtensionClient client, PageService pages, AiGateway gateway,
        ContentPolicy policy, run.halo.aipagebuilder.theme.ThemeContextService themes,
        PageReferenceTools referenceTools, run.halo.aipagebuilder.memory.SiteMemoryService memories) {
        this.memories = memories;
        this.referenceTools = referenceTools;
        this.themes = themes;
        this.client = client;
        this.checkpoints = new PageCheckpoints(client);
        this.conversations = new ConversationTurns(client, checkpoints);
        this.pages = pages;
        this.gateway = gateway;
        this.policy = policy;
    }

    public Mono<ChatApi.Session> session(String name) {
        return session(name, null, null, MAX_TURNS);
    }

    public Mono<ChatApi.Session> session(String name, String sessionId, String before, int size) {
        if (size < 1 || size > 100 || before != null && before.length() > 200) {
            return Mono.error(badRequest("size 必须为 1–100，before 必须为返回的游标"));
        }
        return pages.get(name).then(load(name)).flatMap(this::recover)
            .flatMap(coordinator -> conversations.selected(name, sessionId, coordinator).flatMap(session ->
                conversations.history(session.getMetadata().getName(), before, size).map(history -> {
                    var turns = new ArrayList<>(history.turns());
                    var summary = summary(session, coordinator);
                    var current = coordinator.getSpec().getActive();
                    if (before == null && current != null
                        && summary.sessionId().equals(activeSessionId(coordinator))) {
                        var local = runs.get(name);
                        if (local != null && local.request.runId().equals(current.runId())
                            && local.sessionId.equals(summary.sessionId())) {
                            var snapshot = contextWindow(List.of(local.turn()));
                            if (!snapshot.isEmpty()) current = snapshot.getFirst();
                        }
                        var activeTurn = current;
                        turns.removeIf(turn -> turn.runId().equals(activeTurn.runId()));
                        turns.add(activeTurn);
                    }
                    return new ChatApi.Session(name, summary.version(), turns, summary.activeRun(),
                        new ChatApi.ModelContextWindow(MAX_TURNS, MAX_BYTES), summary.sessionId(), summary.title(),
                        summary.createdAt(), summary.updatedAt(), history.hasMore(), history.nextBefore());
                }))).flatMap(value -> pages.get(name).flatMap(page ->
                    client.fetch(PageChatSession.class, name).flatMap(coordinator ->
                        checkpoints.load(value.sessionId()).map(checkpoint -> value.withRecovery(
                            PageCheckpoints.summary(checkpoint, page,
                                coordinator.getSpec().getActive() != null)))))
                    .defaultIfEmpty(value));
    }

    public Mono<ChatApi.SessionList> sessions(String name) {
        return pages.get(name).then(load(name)).flatMap(this::recover).flatMap(coordinator ->
            conversations.sessions(name)
                .map(session -> summary(session, coordinator)).collectList().map(ChatApi.SessionList::new));
    }

    public Mono<ChatApi.SessionSummary> createSession(String name, ChatApi.CreateSession request) {
        var title = request == null || request.title() == null ? "新会话" : request.title();
        return Mono.defer(() -> {
            validateTitle(title);
            return pages.get(name).then(load(name)).flatMap(coordinator ->
                pages.withLivePage(name, () -> conversations.create(name, UUID.randomUUID().toString(), title.strip()))
                    .map(session -> summary(session, coordinator)));
        });
    }

    public Mono<ChatApi.SessionSummary> renameSession(String name, String sessionId,
        ChatApi.RenameSession request) {
        return Mono.defer(() -> {
            validateTitle(request.title());
            return pages.get(name).then(load(name)).flatMap(this::recover).flatMap(coordinator ->
                conversations.selected(name, sessionId, coordinator).flatMap(session -> {
                    requireVersion(session, request.version());
                    if (session.getSpec().isDeleting()
                        || sessionId.equals(coordinator.getSpec().getDeletingSessionId())
                        || coordinator.getSpec().getActive() != null) {
                        return Mono.error(conflict("页面正在运行或会话正在删除，请稍后重试"));
                    }
                    session.getSpec().setTitle(request.title().strip());
                    session.getSpec().setUpdatedAt(Instant.now());
                    return client.update(session).map(saved -> summary(saved,
                        sessionId.equals(name) ? saved : coordinator));
                }));
        }).onErrorMap(OptimisticLockingFailureException.class, error -> conflict("会话已变化，请重新载入"));
    }

    public Mono<Void> deleteSession(String name, String sessionId, Long version) {
        return pages.get(name).then(load(name)).flatMap(this::recover).flatMap(coordinator ->
            pages.withLivePage(name, () -> conversations.deleteSession(coordinator, sessionId, version)))
            .onErrorMap(OptimisticLockingFailureException.class,
                error -> conflict("会话已变化，请重新载入"));
    }

    public Mono<Flux<Map<String, Object>>> start(String name, ChatApi.Request request, String actor) {
        return Mono.defer(() -> {
            validate(request);
            return pages.get(name).flatMap(page -> {
                if (!Objects.equals(page.page().version(), request.version())) {
                    return Mono.error(conflict("页面已变化，请重新载入后再试"));
                }
                validateAnnotations(request, page);
                return gateway.available().flatMap(available -> available ? Mono.just(page)
                    : Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "请安装并启用 AI Foundation 并配置支持工具调用的模型")));
            }).flatMap(page -> load(name).flatMap(this::recover).flatMap(coordinator -> {
                var existing = runs.get(name);
                if (request.resumeFromRunId() != null && existing != null
                    && existing.actor.equals(actor) && existing.request.equals(request)) {
                    return Mono.just(existing.events.asFlux());
                }
                if (coordinator.getSpec().getActive() != null
                    || coordinator.getSpec().getDeletingSessionId() != null) {
                    return Mono.error(conflict("页面正在运行或删除会话；请稍后重试"));
                }
                return conversations.selected(name, request.sessionId(), coordinator).flatMap(session ->
                    conversations.find(name, request.runId()).hasElement().flatMap(used -> {
                        if (session.getSpec().isDeleting()) return Mono.error(conflict("会话正在删除，请重试删除后再操作"));
                        if (used) return Mono.error(conflict("runId 已使用；请查询运行结果"));
                        return pages.committedAiRun(name, request.runId())
                            .flatMap(commit -> Mono.<Flux<Map<String, Object>>>error(conflict("runId 已提交")))
                            .switchIfEmpty(Mono.defer(() -> {
                                var id = session.getMetadata().getName();
                                if (request.resumeFromRunId() == null) {
                                    return reserve(name, request, actor, page, coordinator, id,
                                        null);
                                }
                                return checkpoints.load(id)
                                    .switchIfEmpty(Mono.error(conflict("可恢复的进度已不存在")))
                                    .flatMap(checkpoint -> {
                                        var spec = checkpoint.getSpec();
                                        var recovery = PageCheckpoints.summary(checkpoint, page, false);
                                        if (!actor.equals(spec.getActor())) return Mono.error(
                                            new ResponseStatusException(HttpStatus.FORBIDDEN, "只能继续自己的运行"));
                                        if (!name.equals(spec.getPageName()) || !recovery.canResume()
                                            || !Objects.equals(request.checkpointVersion(), recovery.checkpointVersion())
                                            || !request.resumeFromRunId().equals(recovery.sourceRunId())
                                            || !request.modelName().equals(spec.getRequest().modelName())
                                            || !request.prompt().equals(spec.getRequest().prompt())
                                            || !Objects.equals(request.annotations(), spec.getRequest().annotations())
                                            || !Objects.equals(request.baseHash(), spec.getRequest().baseHash())) {
                                            return Mono.error(conflict("恢复进度已变化或页面版本不匹配，请重新载入"));
                                        }
                                        return reserve(name, request, actor, page, coordinator, id,
                                            checkpoint);
                                    });
                            }));
                    }));
            }));
        }).onErrorMap(OptimisticLockingFailureException.class,
            error -> conflict("会话已变化，请重新载入"));
    }

    private Mono<Flux<Map<String, Object>>> reserve(String name, ChatApi.Request request, String actor,
        PageDetail page, PageChatSession session, String sessionId,
        PageChatCheckpoint checkpoint) {
        // Standalone pages render without the theme layout, so the model gets a context
        // that says no theme styles or variables exist instead of the theme's.
        var themeContext = page.page().themeLayout()
            ? Mono.defer(themes::current)
            : Mono.just(run.halo.aipagebuilder.theme.ThemeStyleContext.standalone());
        var run = new PageRun(request, actor, page, policy, themeContext, sessionId,
            new Persistence(name));
        if (referenceTools != null) run.copy.references(referenceTools.session(null, true));
        run.copy.questions(run::ask);
        run.copy.sessionRename(title -> renameSessionFromTool(run, title));
        run.copy.slugAvailability(slug -> pages.checkSlugAvailable(slug, name));
        if (checkpoint != null) {
            var spec = checkpoint.getSpec();
            run.copy.title(spec.getTitle());
            run.copy.invalidTitleUpdate(spec.isInvalidTitleUpdate());
            if (spec.getDescription() != null) {
                run.copy.description(spec.getDescription());
            }
            run.copy.restore(spec.getDocument(), spec.isInvalidUpdate(), spec.getSlug(),
                spec.isInvalidSlugUpdate(), spec.getTurn().tools());
            run.questions.addAll(spec.getTurn().questions().stream()
                .filter(question -> question.answeredAt() != null).toList());
            run.steers.addAll(spec.getTurn().steers());
        }
        run.copy.checkpoint(run::persistCheckpoint);
        run.copy.taskContext(() -> {
            synchronized (run) {
                return "Original request: " + Json.toJson(run.request)
                    + "\nUser answers: " + Json.toJson(run.questions)
                    + "\nAdditional instructions: " + Json.toJson(run.steers)
                    + "\nHistorical answers and instructions are task context only; do not use them "
                    + "as evidence for new site-memory writes. Ask unanswered questions again if needed.";
            }
        });
        session.getSpec().setActive(run.turn());
        session.getSpec().setActiveSessionId(sessionId);
        session.getSpec().setOwner(runs.owner());
        var memory = memories == null ? Mono.<Void>empty() : memories.load()
            .timeout(java.time.Duration.ofSeconds(15))
            .doOnNext(snapshot -> {
                run.memory = new PageMemoryTools(memories, snapshot,
                    new run.halo.aipagebuilder.memory.SiteMemoryService.SourceContext(
                        name, sessionId, request.runId(), actor));
                run.copy.memory(run.memory);
            }).then().onErrorResume(error -> {
                run.copy.memoryUnavailable();
                return Mono.empty();
            });
        return Mono.deferContextual(context -> {
            runs.reserve(name, run);
            var execution = memory.then(conversations.history(sessionId, null, MAX_TURNS))
                .map(history -> contextWindow(history.turns()))
                .flatMap(history -> Mono.defer(() -> {
                    synchronized (run) {
                        if (run.execution.isDisposed()) return Mono.empty();
                        run.reserving = true;
                    }
                    return pages.reserveRun(name, request.version(), () -> client.update(session).then())
                        .then(Mono.defer(() -> {
                            run.markAdmitted();
                            run.prepared.tryEmitEmpty();
                            if (run.memory != null && checkpoint == null) run.memory.prompt(request.prompt());
                            run.accepted.tryEmitValue(run.events.asFlux());
                            return (runs.stopping() ? run.interrupt(
                                "插件已停止，进度已保留，可稍后继续。", null)
                                : Mono.<Void>empty()).then(Mono.defer(() -> execute(name, run, history)));
                        }));
                }))
                .doOnError(error -> {
                    run.prepared.tryEmitError(error);
                    run.accepted.tryEmitError(error);
                    // Persisted state remains authoritative after an uncertain write.
                    run.completeEvents(List.of(Map.of("type", "error", "errorText", "运行结果待确认，请查询运行状态")));
                }).contextWrite(context);
            runs.execute(name, run, execution);
            // Receiving this request authorizes execution; disconnecting only closes the stream.
            return run.accepted.asMono().doOnCancel(() -> {
                synchronized (run) {
                    // Before the durable reservation starts, disconnecting can safely abandon prep.
                    if (!run.reserving) {
                        run.execution.dispose();
                        run.prepared.tryEmitError(new java.util.concurrent.CancellationException());
                        runs.release(name, run);
                    }
                }
            });
        });
    }

    /** Runs owned by this process, for the cross-page list. */
    public Mono<ChatApi.ActiveRunList> activeRuns() {
        return Mono.fromSupplier(() -> new ChatApi.ActiveRunList(runs.snapshot().entrySet().stream()
            .filter(entry -> entry.getValue().admitted())
            .map(entry -> entry.getValue().summary(entry.getKey()))
            .sorted(java.util.Comparator.comparing(ChatApi.ActiveRun::startedAt))
            .toList()));
    }

    /** Accepts a run without keeping an observer attached; execution is independent of SSE. */
    public Mono<Void> startBackground(String name, ChatApi.Request request, String actor) {
        return start(name, request, actor).then();
    }

    public void start() {
        runs.start();
    }

    @jakarta.annotation.PreDestroy
    public void stop() {
        runs.stop((name, run) ->
            run.interrupt("插件已停止，进度已保留，可稍后继续。", null));
    }

    private Mono<Map<String, Object>> renameSessionFromTool(PageRun run, String title) {
        synchronized (run) {
            // The default session shares the coordinator record with run state updates,
            // so a rename must not interleave with steer/cancel optimistic writes.
            return run.exclusive(run.settleTransition()
                .then(Mono.defer(() -> updateSessionTitle(run, title))));
        }
    }

    private Mono<Map<String, Object>> updateSessionTitle(PageRun run, String title) {
        return client.fetch(PageChatSession.class, run.sessionId)
            .filter(session -> session.getMetadata().getDeletionTimestamp() == null)
            .switchIfEmpty(Mono.error(conflict("会话不存在")))
            .flatMap(session -> {
                session.getSpec().setTitle(title);
                session.getSpec().setUpdatedAt(Instant.now());
                return client.update(session).map(saved -> {
                    Map<String, Object> result = new java.util.LinkedHashMap<>();
                    result.put("valid", true);
                    result.put("renamed", true);
                    result.put("title", saved.getSpec().getTitle());
                    return result;
                });
            })
            .onErrorResume(OptimisticLockingFailureException.class,
                error -> Mono.just(Map.of("valid", false, "renamed", false,
                    "error", "会话名称更新冲突，请稍后重试")));
    }

    public Mono<ChatApi.Run> answer(String name, String runId, String questionId,
        ChatApi.AnswerRequest request, String actor) {
        return pages.get(name).then(Mono.defer(() -> {
            var run = runs.get(name);
            if (run == null || !run.request.runId().equals(runId)) {
                return Mono.error(conflict("运行已结束或问题已过期"));
            }
            return run.answer(questionId, request, actor).map(turn -> result(turn, null));
        })).onErrorMap(OptimisticLockingFailureException.class,
            error -> conflict("会话已变化，请重新载入"))
            .onErrorMap(RunTransitionException.class, PageChatService::transitionError);
    }

    /** Queues a mid-run instruction; injected at the next model step or a bounded continuation. */
    public Mono<ChatApi.Run> steer(String name, String runId, ChatApi.SteerRequest request,
        String actor) {
        final var text = request == null || request.text() == null ? "" : request.text().strip();
        if (text.isEmpty() || text.length() > 2000) {
            return Mono.error(badRequest("补充指令需要 1–2000 字符"));
        }
        return pages.get(name).then(Mono.defer(() -> {
            var run = runs.get(name);
            if (run == null || !run.request.runId().equals(runId)) {
                return Mono.error(conflict("运行已结束，请作为新消息发送"));
            }
            return run.steer(text, actor).map(turn -> result(turn, null));
        })).onErrorMap(OptimisticLockingFailureException.class,
            error -> conflict("会话已变化，请重新载入"))
            .onErrorMap(RunTransitionException.class, PageChatService::transitionError);
    }

    private static ResponseStatusException transitionError(RunTransitionException error) {
        return switch (error.kind()) {
            case CONFLICT -> conflict(error.getMessage());
            case FORBIDDEN -> new ResponseStatusException(HttpStatus.FORBIDDEN, error.getMessage());
        };
    }

    private Mono<Void> execute(String name, PageRun run, List<ChatApi.Turn> history) {
        var timeoutSignal = Sinks.<Void>empty();
        var timeout = run.copy.timeBudget.deadline(RUN_TIMEOUT, AiGenerationException.Reason.RUN_TIMEOUT)
            .doOnError(timeoutSignal::tryEmitError).subscribe();
        return run.copy.prepareThemeBrief().then(run.copy.validateRestoredSlug())
            .takeUntilOther(timeoutSignal.asMono())
            .takeUntilOther(run.cancel.asMono())
            // Cancellation is provisional until its write succeeds; it may still roll back.
            .then(run.settleThen(() ->
                run.is(PageRun.State.CANCELLED) || run.is(PageRun.State.INTERRUPTED)
                    ? Mono.defer(() -> run.finish(null))
                    : run.persistCheckpoint().then(Mono.defer(() -> converse(name, run, history,
                        run.request.prompt(), timeoutSignal.asMono())))))
            .onErrorResume(error -> {
                if (run.terminalPersisted()) return Mono.error(error);
                if (run.saveStarted()
                    && (run.is(PageRun.State.COMMITTING) || run.is(PageRun.State.COMPLETED))
                    && !(error instanceof ResponseStatusException status
                    && status.getStatusCode().value() == 409)) {
                    // Do not claim failure when an update may have reached storage.
                    return pages.committedAiRun(name, run.request.runId()).flatMap(commit -> {
                        run.complete(commit.revision());
                        return pages.get(name).flatMap(page -> run.finish(page)).thenReturn(true);
                    }).switchIfEmpty(Mono.defer(() -> {
                        // A COMPLETED state already means saveAi returned successfully; only the
                        // commit lookup is missing, so keep the success instead of reclassifying.
                        if (!run.is(PageRun.State.COMPLETED)) {
                            run.fail("保存未完成，已核对页面未关联本次运行，请重新载入后再试");
                        }
                        return run.finish(null).thenReturn(false);
                    })).then();
                }
                // Cancellation can still fail to persist and roll back its provisional status.
                // Classify model failures only after that transition has settled, and decide in
                // the same monitor section that finds no pending transition.
                return run.settleThen(() -> {
                    if (!run.is(PageRun.State.CANCELLED)
                        && !run.is(PageRun.State.INTERRUPTED)) {
                        var reason = failureReason(error, run.copy);
                        var message = error instanceof ResponseStatusException status
                            && status.getStatusCode().value() == 409
                            ? conflictMessage(status) : reason.message;
                        switch (reason) {
                            case STEP_LIMIT, TOOL_LIMIT, DATA_LIMIT, CONTEXT_LIMIT, TOKEN_LIMIT,
                                TRUNCATED, RUN_TIMEOUT -> run.incomplete(message);
                            default -> run.fail(message);
                        }
                        org.slf4j.LoggerFactory.getLogger(PageChatService.class).warn(
                            "Page chat failed: {}, reason={}, exception={}",
                            run.copy.trace(), reason.name(), error.getClass().getName());
                    }
                    // finish() closes edits eagerly, so build it after the lock is released.
                    return Mono.defer(() -> run.finish(null));
                });
            }).doFinally(signal -> timeout.dispose());
    }

    private Mono<Void> converse(String name, PageRun run, List<ChatApi.Turn> history, String prompt,
        Mono<Void> runTimeout) {
        return gateway.chat(run.request.modelName(), history, prompt, run.copy)
            .takeUntilOther(runTimeout)
            .takeUntilOther(run.cancel.asMono())
            .takeUntilOther(run.failure.asMono().flatMap(Mono::error))
            .doOnNext(chunk -> {
                if ("text-delta".equals(chunk.get("type"))) {
                    var delta = String.valueOf(chunk.getOrDefault("delta", ""));
                    if (run.text.length() + delta.length() > 16_000) {
                        throw new AiGenerationException(AiGenerationException.Reason.DISPLAY_LIMIT);
                    }
                    run.text.append(delta);
                }
                run.display.accept(chunk);
                if (!run.emitEvent(chunk)) {
                    throw new IllegalStateException("消息流已关闭或消费过慢");
                }
                if ("tool-output-available".equals(chunk.get("type"))
                    && chunk.get("output") instanceof Map<?, ?> output
                    && Boolean.TRUE.equals(output.get("valid")) && Boolean.TRUE.equals(output.get("staged"))) {
                    var toolCallId = String.valueOf(chunk.get("toolCallId"));
                    var tool = run.display.toolName(toolCallId);
                    if ("patch_current_page".equals(tool) || "update_current_page".equals(tool)) {
                        var document = run.copy.document();
                        var hash = DocumentHash.of(document.html(), document.css(), document.js());
                        if (hash.equals(output.get("baseHash"))) {
                            var data = Map.of("runId", run.request.runId(), "toolCallId", toolCallId,
                                "baseHash", hash, "html", document.html(), "css", document.css(), "js", document.js());
                            if (run.copy.preview(Json.toJson(data)) && !run.emitEvent(Map.of("type", "data-page-staged",
                                "name", "page-staged", "id", toolCallId, "data", data))) {
                                throw new IllegalStateException("临时预览流已关闭");
                            }
                        }
                    }
                }
            }).then(run.settleThen(() -> {
                List<String> steers;
                if (run.is(PageRun.State.CANCELLED)
                    || run.is(PageRun.State.INTERRUPTED)) {
                    return Mono.defer(() -> run.finish(null));
                }
                // A steer that missed the last model step gets a bounded continuation call.
                steers = run.copy.drainContinuationSteers();
                if (steers.isEmpty()) run.markCommitting();
                if (!steers.isEmpty()) {
                    var missed = steers;
                    return Mono.defer(() -> converse(name, run,
                        continuedHistory(run, history, missed.size()),
                        FoundationBridge.steerMessage(missed), runTimeout));
                }
                return commit(name, run);
            }));
    }

    private static List<ChatApi.Turn> continuedHistory(PageRun run, List<ChatApi.Turn> history,
        int newSteers) {
        String text;
        List<ChatApi.Tool> tools;
        List<ChatApi.Question> questions;
        List<String> earlierSteers;
        synchronized (run) {
            text = run.text.toString();
            tools = run.copy.summaries();
            questions = List.copyOf(run.questions);
            earlierSteers = List.copyOf(run.steers.subList(0, run.steers.size() - newSteers));
        }
        var prompt = earlierSteers.isEmpty() ? run.request.prompt() : run.request.prompt()
            + "\n\nAdditional instructions sent while you were working:\n"
            + String.join("\n", earlierSteers);
        var continued = new ArrayList<>(history);
        continued.add(new ChatApi.Turn(run.request.runId(), run.actor, run.createdAt, prompt, text,
            tools, "completed", null, null, null, null, questions, List.of()));
        return List.copyOf(continued);
    }

    private Mono<Void> commit(String name, PageRun run) {
        return Mono.defer(() -> {
            synchronized (run) {
                if (run.is(PageRun.State.CANCELLED) || run.is(PageRun.State.INTERRUPTED)) {
                    return run.finish(null);
                }
                if (run.copy.checkpointFailed()) {
                    return Mono.error(new AiGenerationException(AiGenerationException.Reason.CHECKPOINT_FAILED));
                }
                if (run.copy.exhausted()) {
                    return Mono.error(new IllegalStateException("工具预算已耗尽"));
                }
                if (run.copy.hasInvalidUpdate()) {
                    return Mono.error(new AiGenerationException(AiGenerationException.Reason.INVALID_UPDATE));
                }
                run.markCommitting();
            }
            return run.persistActive().then(Mono.defer(() -> {
                var slugChanged = !Objects.equals(run.base.page().slug(), run.copy.slug());
                var titleChanged = !Objects.equals(run.base.page().title(), run.copy.title());
                var descriptionChanged = !Objects.equals(
                    java.util.Objects.requireNonNullElse(run.base.page().description(), ""),
                    run.copy.description());
                if (Objects.equals(run.base.document(), run.copy.document()) && !slugChanged
                    && !titleChanged && !descriptionChanged) {
                    return Mono.just(run.base);
                }
                run.markSaveStarted();
                return pages.saveAi(name, run.request.version(), run.copy.document(),
                    run.actor, run.request.runId(), run.base.page().draftRevision(),
                    slugChanged ? run.copy.slug() : null, titleChanged ? run.copy.title() : null,
                    descriptionChanged ? run.copy.description() : null);
            })).flatMap(page -> {
                run.complete(!Objects.equals(page.page().draftRevision(),
                    run.base.page().draftRevision()) ? page.page().draftRevision() : null);
                return run.finish(page);
            });
        });
    }

    static AiGenerationException.Reason failureReason(Throwable error, PageWorkingCopy copy) {
        var budgetReason = copy.budget.exhaustionReason();
        if (budgetReason != null) return budgetReason;
        for (var cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof AiGenerationException known) return known.reason();
            if (cause instanceof java.util.concurrent.TimeoutException) return AiGenerationException.Reason.TIMEOUT;
            if (cause.getCause() == cause) break;
        }
        return AiGenerationException.Reason.CALL_FAILED;
    }

    public Mono<ChatApi.CheckpointDetail> checkpoint(String name, String sessionId, String actor) {
        return pages.get(name).flatMap(page -> load(name).flatMap(this::recover)
            .flatMap(coordinator -> conversations.selected(name, sessionId, coordinator)
                .flatMap(session -> checkpoints.load(session.getMetadata().getName()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "没有可恢复的进度")))
                .flatMap(value -> {
                    if (!actor.equals(value.getSpec().getActor())) return Mono.error(
                        new ResponseStatusException(HttpStatus.FORBIDDEN, "只能查看自己的恢复进度"));
                    return Mono.just(new ChatApi.CheckpointDetail(value.getSpec().getDocument(),
                        value.getSpec().getRequest(), PageCheckpoints.summary(value, page,
                            coordinator.getSpec().getActive() != null)));
                })));
    }

    /** Builds one checkpoint snapshot; the run owns serialization and failure handling. */
    private Mono<Void> checkpointWrite(String name, PageRun run) {
        synchronized (run) {
            var snapshot = new PageChatCheckpoint.Spec();
            snapshot.setPageName(name);
            snapshot.setSessionId(run.sessionId);
            snapshot.setActor(run.actor);
            snapshot.setRequest(run.request);
            snapshot.setBaseRevision(run.base.page().draftRevision());
            snapshot.setDocument(run.copy.document());
            snapshot.setInvalidUpdate(run.copy.hasInvalidContentUpdate());
            snapshot.setSlug(run.copy.slug());
            snapshot.setTitle(run.copy.title());
            snapshot.setDescription(run.copy.description());
            snapshot.setInvalidTitleUpdate(run.copy.hasInvalidTitleUpdate());
            snapshot.setInvalidSlugUpdate(run.copy.hasInvalidSlugUpdate());
            snapshot.setTurn(run.turn());
            snapshot.setSteps(run.copy.budget.steps());
            snapshot.setToolCalls(run.copy.calls());
            snapshot.setRecoveries(run.copy.budget.recoveries());
            return checkpoints.save(snapshot);
        }
    }

    private Mono<Void> persistActive(String name, PageRun run) {
        return client.fetch(PageChatSession.class, name).flatMap(session -> {
            requireRun(session, run.request.runId());
            session.getSpec().setActive(run.turn());
            requireBoundedActive(session);
            return client.update(session);
        }).switchIfEmpty(Mono.error(conflict("会话不存在"))).then();
    }

    public Mono<ChatApi.Run> result(String name, String runId) {
        return pages.get(name).flatMap(page -> load(name).flatMap(this::recover).flatMap(session -> {
            var current = session.getSpec().getActive();
            if (current != null && current.runId().equals(runId)) {
                return pages.committedAiRun(name, runId)
                    .map(commit -> new ChatApi.Run(runId, "completed", commit.revision(), null, page))
                    .defaultIfEmpty(result(current, null));
            }
            return conversations.find(name, runId)
                .flatMap(record -> {
                    var turn = record.turn();
                    var result = result(turn, "completed".equals(turn.status()) ? page : null);
                    return checkpoints.load(record.sessionId())
                        .filter(value -> runId.equals(value.getSpec().getRequest().runId()))
                        .map(value -> result.withRecovery(PageCheckpoints.summary(value, page,
                            session.getSpec().getActive() != null))).defaultIfEmpty(result);
                })
                .switchIfEmpty(pages.committedAiRun(name, runId)
                    .map(commit -> new ChatApi.Run(runId, "completed", commit.revision(), null, page)))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "运行记录不存在")));
        }));
    }

    public Mono<ChatApi.Run> cancel(String name, String runId) {
        return pages.get(name).then(Mono.defer(() -> {
            var run = runs.get(name);
            if (run != null && run.request.runId().equals(runId)) {
                return run.cancel().then(Mono.fromSupplier(() -> new ChatApi.Run(runId,
                    run.turn().status(), run.revision(), run.error(), null)));
            }
            return result(name, runId);
        }));
    }

    PageRunCoordinator runs() {
        return runs;
    }

    /** Binds a run to this service's storage and session operations. */
    private final class Persistence implements RunPersistence {
        private final String name;

        private Persistence(String name) {
            this.name = name;
        }

        @Override
        public Mono<Void> active(PageRun run) {
            return persistActive(name, run);
        }

        @Override
        public Mono<Void> checkpoint(PageRun run) {
            return checkpointWrite(name, run);
        }

        @Override
        public Mono<Void> recordFinished(PageRun run, ChatApi.Turn turn) {
            return client.fetch(PageChatSession.class, name)
                .switchIfEmpty(Mono.error(conflict("会话不存在")))
                .flatMap(session -> conversations.finish(session, turn)).then();
        }

        @Override
        public Mono<Void> deleteCheckpoint(PageRun run) {
            return checkpoints.delete(run.sessionId);
        }

        @Override
        public Mono<Void> checkpointAvailable(PageRun run) {
            return checkpoints.available(run.sessionId, run.request.runId());
        }
    }


    private Mono<PageChatSession> load(String name) {
        return client.fetch(PageChatSession.class, name)
            .filter(session -> session.getMetadata().getDeletionTimestamp() == null)
            .flatMap(session -> {
                var spec = session.getSpec();
                if (name.equals(spec.getPageName()) && spec.getTurns().isEmpty()
                    && spec.getDeletingSessionId() == null) {
                    return Mono.just(session);
                }
                return loadWithWrites(name);
            }).switchIfEmpty(Mono.defer(() -> loadWithWrites(name)));
    }

    private Mono<PageChatSession> loadWithWrites(String name) {
        // Concurrent first reads share initialization, but each caller owns its resource snapshot.
        return Mono.defer(() -> sessionLoads.computeIfAbsent(name, key ->
            initializeSession(key).doOnTerminate(() -> sessionLoads.remove(key)).cache()))
            .flatMap(ignored -> client.fetch(PageChatSession.class, name));
    }

    private Mono<PageChatSession> initializeSession(String name) {
        return pages.withLivePage(name, () -> conversations.initialize(name));
    }

    private Mono<PageChatSession> recover(PageChatSession session) {
        var turn = session.getSpec().getActive();
        var name = session.getMetadata().getName();
        if (turn == null || runs.owner().equals(session.getSpec().getOwner()) && runs.contains(name)) {
            return Mono.just(session);
        }
        // Recovery may delete the old run's checkpoint. Serialize with reservation and
        // reread inside the lock so an already recovered snapshot cannot clear a newer run.
        return pages.withLivePage(name, () -> client.fetch(PageChatSession.class, name)
            .switchIfEmpty(Mono.error(conflict("会话不存在"))).flatMap(this::recoverLocked));
    }

    private Mono<PageChatSession> recoverLocked(PageChatSession session) {
        var turn = session.getSpec().getActive();
        var name = session.getMetadata().getName();
        if (turn == null || runs.owner().equals(session.getSpec().getOwner()) && runs.contains(name)) {
            return Mono.just(session);
        }
        var terminal = List.of("completed", "cancelled", "interrupted", "incomplete", "failed")
            .contains(turn.status());
        return (terminal ? Mono.just(turn) : pages.committedAiRun(name, turn.runId()).map(commit -> new ChatApi.Turn(
                turn.runId(), turn.actor(), turn.createdAt(), turn.prompt(), turn.text(), turn.tools(),
                "completed", commit.revision(), null, turn.parts(), turn.annotations(), turn.questions(), turn.steers()).withUsage(turn.usage()))
            .switchIfEmpty(Mono.defer(() -> {
                if (runs.owner().equals(session.getSpec().getOwner()) && "committing".equals(turn.status())) {
                    return Mono.empty(); // An uncertain local write may still be in flight.
                }
                return Mono.just(new ChatApi.Turn(turn.runId(), turn.actor(), turn.createdAt(),
                    turn.prompt(), turn.text(), turn.tools(),
                    "cancelled".equals(turn.status()) ? "cancelled" : "interrupted", null,
                    "服务重启或运行中断，未自动继续调用模型", turn.parts(), turn.annotations(), turn.questions(), turn.steers()).withUsage(turn.usage()));
            }))).flatMap(recovered -> {
                var sessionId = activeSessionId(session);
                return ("completed".equals(recovered.status()) || "cancelled".equals(turn.status())
                    ? checkpoints.delete(sessionId) : checkpoints.available(sessionId, turn.runId()))
                    .then(Mono.defer(() -> conversations.finish(session, recovered)));
            }).defaultIfEmpty(session);
    }

    static List<ChatApi.Turn> contextWindow(List<ChatApi.Turn> history) {
        var turns = new ArrayList<>(history.subList(Math.max(0, history.size() - MAX_TURNS), history.size()));
        while (!turns.isEmpty() && Json.toJson(turns)
            .getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            turns.removeFirst();
        }
        return List.copyOf(turns);
    }

    private static void requireBoundedActive(PageChatSession session) {
        if (Json.toJson(session.getSpec().getActive())
            .getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new AiGenerationException(AiGenerationException.Reason.STORAGE_LIMIT);
        }
    }

    private static String activeSessionId(PageChatSession coordinator) {
        var id = coordinator.getSpec().getActiveSessionId();
        return id == null ? coordinator.getMetadata().getName() : id;
    }

    private static ChatApi.SessionSummary summary(PageChatSession session, PageChatSession coordinator) {
        var spec = session.getSpec();
        var id = session.getMetadata().getName();
        var current = coordinator.getSpec().getActive();
        return new ChatApi.SessionSummary(id, spec.getPageName(), spec.getTitle(),
            session.getMetadata().getVersion(), spec.getCreatedAt(), spec.getUpdatedAt(),
            current != null && id.equals(activeSessionId(coordinator)) ? result(current, null) : null);
    }

    private static void validateTitle(String title) {
        if (!bounded(title, 100)) throw badRequest("会话标题需要 1–100 字符");
    }

    private static void requireVersion(PageChatSession session, Long version) {
        if (version == null) throw badRequest("version 必填");
        if (!version.equals(session.getMetadata().getVersion())) throw conflict("会话已变化，请重新载入");
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ChatApi.Run result(ChatApi.Turn turn, PageDetail page) {
        var pending = "waiting_for_user".equals(turn.status()) && turn.questions() != null
            && !turn.questions().isEmpty() ? turn.questions().getLast() : null;
        return new ChatApi.Run(turn.runId(), turn.status(), turn.revision(), turn.error(), page, pending);
    }

    private static void requireRun(PageChatSession session, String runId) {
        if (session.getSpec().getActive() == null
            || !runId.equals(session.getSpec().getActive().runId())) {
            throw conflict("会话运行已变化");
        }
    }

    private static void validate(ChatApi.Request request) {
        if (request == null || request.runId() == null || request.version() == null
            || request.modelName() == null || request.modelName().isBlank()
            || request.modelName().length() > 200 || request.prompt() == null
            || request.prompt().isBlank() || request.prompt().length() > 8000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 runId、version、modelName 和 1–8000 字符 prompt");
        }
        try {
            if (!UUID.fromString(request.runId()).toString().equals(request.runId())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runId 必须为标准 UUID");
        }
    }

    private static void validateAnnotations(ChatApi.Request request, PageDetail page) {
        var annotations = request.annotations();
        if (annotations == null || annotations.isEmpty()) return;
        if (annotations.size() > 5 || Json.toJson(annotations).getBytes(StandardCharsets.UTF_8).length > 48 * 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批注最多5项，总大小不能超过48 KiB");
        }
        if (!DocumentHash.of(page.document().html(), page.document().css(), page.document().js()).equals(request.baseHash())) {
            throw conflict("批注对应的页面内容已变化，请重新选择元素");
        }
        var ids = new java.util.HashSet<String>();
        for (var annotation : annotations) {
            if (annotation == null || !bounded(annotation.id(), 64) || !ids.add(annotation.id())
                || !bounded(annotation.tag(), 32) || !bounded(annotation.comment(), 2000)
                || annotation.summary() == null || annotation.summary().length() > 300
                || annotation.html() == null || annotation.html().length() > 4000
                || annotation.path() == null || annotation.path().isEmpty() || annotation.path().size() > 64
                || annotation.path().stream().anyMatch(index -> index == null || index < 0 || index > 100_000)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批注目标或内容无效，请重新选择元素");
            }
        }
    }

    private static boolean bounded(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static String conflictMessage(ResponseStatusException error) {
        return switch (java.util.Objects.requireNonNullElse(error.getReason(), "")) {
            case "Page changed; reload before saving" -> "页面已变化，请重新载入（409）";
            case "Slug already exists" -> "访问路径已被占用，请选择其他路径后继续生成";
            case "" -> "保存发生冲突，请重新载入后检查页面与访问路径（409）";
            default -> error.getReason();
        };
    }

}
