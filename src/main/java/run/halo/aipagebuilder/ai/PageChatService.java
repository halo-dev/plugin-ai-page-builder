package run.halo.aipagebuilder.ai;

import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.isNull;
import static run.halo.app.extension.index.query.Queries.lessThan;
import static run.halo.app.extension.index.query.Queries.or;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.router.selector.FieldSelector;
import run.halo.aipagebuilder.model.PageChatTurnRecord;
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
    static final int MAX_STEERS = 10;
    static final int MAX_CONTINUATIONS = 3;
    static final Duration RUN_TIMEOUT = Duration.ofMinutes(10);
    private final ReactiveExtensionClient client;
    private final PageService pages;
    private final PageCheckpoints checkpoints;
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
            .flatMap(coordinator -> selected(name, sessionId, coordinator).flatMap(session ->
                history(session.getMetadata().getName(), before, size + 1).map(records -> {
                    boolean hasMore = records.size() > size;
                    var visible = records.subList(0, Math.min(size, records.size()));
                    var turns = new ArrayList<>(visible.stream()
                        .map(record -> record.getSpec().getTurn()).toList().reversed());
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
                        summary.createdAt(), summary.updatedAt(), hasMore,
                        hasMore ? visible.getLast().getSpec().getSortKey() : null);
                }))).flatMap(value -> pages.get(name).flatMap(page ->
                    client.fetch(PageChatSession.class, name).flatMap(coordinator ->
                        checkpoints.load(value.sessionId()).map(checkpoint -> value.withRecovery(
                            PageCheckpoints.summary(checkpoint, page,
                                coordinator.getSpec().getActive() != null)))))
                    .defaultIfEmpty(value));
    }

    public Mono<ChatApi.SessionList> sessions(String name) {
        return pages.get(name).then(load(name)).flatMap(this::recover).flatMap(coordinator ->
            client.listAll(PageChatSession.class, sessionOptions(name),
                    Sort.by(Sort.Order.desc("spec.updatedAt"), Sort.Order.asc("metadata.name")))
                .map(session -> summary(session, coordinator)).collectList().map(ChatApi.SessionList::new));
    }

    public Mono<ChatApi.SessionSummary> createSession(String name, ChatApi.CreateSession request) {
        var title = request == null || request.title() == null ? "新会话" : request.title();
        return Mono.defer(() -> {
            validateTitle(title);
            return pages.get(name).then(load(name)).flatMap(coordinator ->
                pages.withLivePage(name, () -> client.create(newSession(name, UUID.randomUUID().toString(), title.strip())))
                    .map(session -> summary(session, coordinator)));
        });
    }

    public Mono<ChatApi.SessionSummary> renameSession(String name, String sessionId,
        ChatApi.RenameSession request) {
        return Mono.defer(() -> {
            validateTitle(request.title());
            return pages.get(name).then(load(name)).flatMap(this::recover).flatMap(coordinator ->
                selected(name, sessionId, coordinator).flatMap(session -> {
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
            pages.withLivePage(name, () -> selected(name, sessionId, coordinator).flatMap(session -> {
                requireVersion(session, version);
                if (coordinator.getSpec().getActive() != null) {
                    return Mono.error(conflict("请先结束当前会话运行"));
                }
                if (coordinator.getSpec().getDeletingSessionId() != null
                    && !sessionId.equals(coordinator.getSpec().getDeletingSessionId())) {
                    return Mono.error(conflict("其他会话正在删除，请稍后重试"));
                }
                coordinator.getSpec().setDeletingSessionId(sessionId);
                // Reserve against start's CAS before deleting any history.
                return client.update(coordinator).flatMap(this::completeSessionDeletion).then();
            }))).onErrorMap(OptimisticLockingFailureException.class,
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
                return selected(name, request.sessionId(), coordinator).flatMap(session ->
                    findTurn(name, request.runId()).hasElement().flatMap(used -> {
                        if (session.getSpec().isDeleting()) return Mono.error(conflict("会话正在删除，请重试删除后再操作"));
                        if (used) return Mono.error(conflict("runId 已使用；请查询运行结果"));
                        return pages.committedAiRevision(name, request.runId())
                            .flatMap(revision -> Mono.<Flux<Map<String, Object>>>error(conflict("runId 已提交")))
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
        var run = new PageRun(request, actor, page, policy, themeContext, sessionId);
        if (referenceTools != null) run.copy.references(referenceTools.session(null, true));
        run.copy.questions(question -> ask(name, run, question));
        run.copy.sessionRename(title -> renameSessionFromTool(run, title));
        if (checkpoint != null) {
            var spec = checkpoint.getSpec();
            run.copy.restore(spec.getDocument(), spec.isInvalidUpdate(), spec.getSlug(),
                spec.isInvalidSlugUpdate(), spec.getTurn().tools());
            run.questions.addAll(spec.getTurn().questions().stream()
                .filter(question -> question.answeredAt() != null).toList());
            run.steers.addAll(spec.getTurn().steers());
        }
        run.copy.checkpoint(() -> saveCheckpoint(name, run));
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
            var execution = memory.then(history(sessionId, null, MAX_TURNS))
                .map(records -> contextWindow(records.stream()
                    .map(record -> record.getSpec().getTurn()).toList().reversed()))
                .flatMap(history -> Mono.defer(() -> {
                    synchronized (run) {
                        if (run.execution.isDisposed()) return Mono.empty();
                        run.reserving = true;
                    }
                    return pages.reserveRun(name, request.version(), () -> client.update(session).then())
                        .then(Mono.defer(() -> {
                            run.prepared.tryEmitEmpty();
                            if (run.memory != null && checkpoint == null) run.memory.prompt(request.prompt());
                            run.accepted.tryEmitValue(run.events.asFlux());
                            return (runs.stopping() ? interrupt(name, run, "插件已停止，进度已保留，可稍后继续。")
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

    @jakarta.annotation.PreDestroy
    public void stop() {
        runs.stop((name, run) -> interrupt(name, run, "插件已停止，进度已保留，可稍后继续。"));
    }

    private Mono<Void> interrupt(String name, PageRun run, String message) {
        return interrupt(name, run, message, null);
    }

    private Mono<Void> interrupt(String name, PageRun run, String message, String questionId) {
        return Mono.defer(() -> {
            synchronized (run) {
                if (run.transition != null) {
                    return run.transition.onErrorComplete()
                        .then(Mono.defer(() -> interrupt(name, run, message, questionId)));
                }
                // An expired timer may already be executing when an answer cancels it.
                if (questionId != null && (!"waiting_for_user".equals(run.status)
                    || run.questions.isEmpty() || !questionId.equals(run.questions.getLast().id()))) {
                    return Mono.empty();
                }
                if (!"running".equals(run.status) && !"waiting_for_user".equals(run.status)) {
                    return Mono.empty(); // In particular, never cancel an uncertain commit.
                }
                var previous = run.status;
                run.status = "interrupted";
                run.error = message;
                run.transition = persistActive(name, run).doOnSuccess(ignored -> {
                    synchronized (run) {
                        run.transition = null;
                        run.waiting.update(null);
                        run.cancel.tryEmitEmpty();
                    }
                }).doOnError(error -> {
                    synchronized (run) {
                        run.status = previous;
                        run.error = null;
                        run.transition = null;
                    }
                }).cache();
                return run.transition;
            }
        });
    }

    private Mono<Map<String, Object>> renameSessionFromTool(PageRun run, String title) {
        Mono<Map<String, Object>> update;
        synchronized (run) {
            // The default session shares the coordinator record with run state updates,
            // so a rename must not interleave with steer/cancel optimistic writes.
            var previous = run.transition;
            var barrierRef = new AtomicReference<Mono<Void>>();
            update = (previous == null ? Mono.<Void>empty() : previous.onErrorComplete())
                .then(Mono.defer(() -> updateSessionTitle(run, title)))
                // Clear the barrier inside the shared write: a cancelled subscriber must not
                // release steer/cancel while the cached update is still running.
                .doFinally(signal -> {
                    synchronized (run) {
                        if (run.transition == barrierRef.get()) run.transition = null;
                    }
                })
                .cache();
            // A failed rename must not surface as a failure of stop/steer waiting on it.
            var barrier = update.then().onErrorComplete();
            barrierRef.set(barrier);
            run.transition = barrier;
        }
        return update;
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

    private Mono<ChatApi.Question> ask(String name, PageRun run, ChatApi.Question question) {
        return Mono.defer(() -> {
            synchronized (run) {
                if (!"running".equals(run.status)) return Mono.error(conflict("运行不接受新的问题"));
                run.status = "waiting_for_user";
                run.questions.add(question);
                run.answer = Sinks.one();
                run.copy.timeBudget.pause();
                run.transition = persistActive(name, run).then(saveCheckpoint(name, run)).doOnSuccess(ignored -> {
                    synchronized (run) {
                        run.transition = null;
                        runs.awaitAnswer(run, () -> interrupt(name, run,
                            "等待回答已超过 24 小时，进度已保留，可继续生成。", question.id()));
                        run.emitEvent(Map.of("type", "data-user-question",
                            "name", "user-question", "id", question.id(), "data",
                            Map.of("runId", run.request.runId(), "question", question)));
                    }
                }).cache();
                return run.transition.then(run.answer.asMono())
                    .doOnError(error -> run.failure.tryEmitValue(error));
            }
        });
    }

    public Mono<ChatApi.Run> answer(String name, String runId, String questionId,
        ChatApi.AnswerRequest request, String actor) {
        return pages.get(name).then(Mono.defer(() -> {
            var run = runs.get(name);
            if (run == null || !run.request.runId().equals(runId)) {
                return Mono.error(conflict("运行已结束或问题已过期"));
            }
            synchronized (run) {
                if (!run.actor.equals(actor)) {
                    return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "只能回答自己的运行"));
                }
                if (!"waiting_for_user".equals(run.status) || run.transition != null
                    || run.answering || run.questions.isEmpty()) {
                    return Mono.error(conflict("问题已回答或运行状态已变化"));
                }
                var pending = run.questions.getLast();
                if (!pending.id().equals(questionId) || pending.answeredAt() != null) {
                    return Mono.error(conflict("问题已过期或已回答"));
                }
                var answered = UserQuestions.answer(pending, request);
                run.answering = true;
                run.waiting.update(null);
                run.status = "running";
                run.questions.set(run.questions.size() - 1, answered);
                run.transition = persistActive(name, run).then(saveCheckpoint(name, run)).doOnSuccess(ignored -> {
                    synchronized (run) {
                        run.transition = null;
                        run.status = "running";
                        run.answering = false;
                        run.emitEvent(Map.of("type", "data-user-answer",
                            "name", "user-answer", "id", answered.id(), "data",
                            Map.of("runId", runId, "question", answered)));
                        run.copy.timeBudget.resume();
                        if (run.memory != null) run.memory.answered(answered);
                        run.answer.tryEmitValue(answered);
                    }
                }).doOnError(error -> {
                    synchronized (run) {
                        run.transition = null;
                        run.answer.tryEmitError(error);
                    }
                }).cache();
                return run.transition.then(Mono.fromSupplier(() -> result(run.turn(), null)));
            }
        })).onErrorMap(OptimisticLockingFailureException.class,
            error -> conflict("会话已变化，请重新载入"));
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
            synchronized (run) {
                if (run.transition != null) {
                    return run.transition.then(Mono.defer(() -> steer(name, runId, request, actor)));
                }
                if (!run.actor.equals(actor)) {
                    return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "只能补充自己的运行"));
                }
                if (!"running".equals(run.status) && !"waiting_for_user".equals(run.status)) {
                    return Mono.error(conflict("运行已结束，请作为新消息发送"));
                }
                if (run.steers.size() >= MAX_STEERS) {
                    return Mono.error(conflict("本轮补充指令已达上限，请等本轮结束后继续"));
                }
                run.steers.add(text);
                var index = run.steers.size() - 1;
                run.transition = persistActive(name, run).then(saveCheckpoint(name, run)).doOnSuccess(ignored -> {
                    synchronized (run) {
                        if (run.memory != null) run.memory.steer(index, text);
                        run.copy.queueSteer(text);
                        run.transition = null;
                        run.emitEvent(Map.of("type", "data-steer", "name", "steer",
                            "id", run.request.runId() + "-steer-" + index, "data",
                            Map.of("runId", run.request.runId(), "index", index, "text", text)));
                    }
                }).doOnError(error -> {
                    synchronized (run) {
                        run.steers.remove(index);
                        run.transition = null;
                    }
                }).cache();
                return run.transition.then(Mono.fromSupplier(() -> result(run.turn(), null)));
            }
        })).onErrorMap(OptimisticLockingFailureException.class,
            error -> conflict("会话已变化，请重新载入"));
    }

    private Mono<Void> execute(String name, PageRun run, List<ChatApi.Turn> history) {
        var timeoutSignal = Sinks.<Void>empty();
        var timeout = run.copy.timeBudget.deadline(RUN_TIMEOUT, AiGenerationException.Reason.RUN_TIMEOUT)
            .doOnError(timeoutSignal::tryEmitError).subscribe();
        return saveCheckpoint(name, run).then(Mono.defer(() ->
                "interrupted".equals(run.status) ? finish(name, run, null) : converse(name, run, history,
                run.request.prompt(), timeoutSignal.asMono())))
            .onErrorResume(error -> {
                if (run.terminalPersisted) return Mono.error(error);
                if (run.saveStarted && ("committing".equals(run.status) || "completed".equals(run.status))
                    && !(error instanceof ResponseStatusException status
                    && status.getStatusCode().value() == 409)) {
                    // Do not claim failure when an update may have reached storage.
                    return pages.committedAiRevision(name, run.request.runId()).flatMap(revision -> {
                        run.revision = revision;
                        run.status = "completed";
                        return pages.get(name).flatMap(page -> finish(name, run, page)).thenReturn(true);
                    }).switchIfEmpty(Mono.defer(() -> {
                        run.status = "failed";
                        run.error = "保存未完成，已核对页面未关联本次运行，请重新载入后再试";
                        return finish(name, run, null).thenReturn(false);
                    })).then();
                }
                // Cancellation can still fail to persist and roll back its provisional status.
                // Classify model failures only after that transition has settled.
                return Mono.defer(() -> {
                    synchronized (run) {
                        return run.transition == null ? Mono.<Void>empty() : run.transition.onErrorComplete();
                    }
                }).then(Mono.defer(() -> {
                    synchronized (run) {
                        if (!"cancelled".equals(run.status) && !"interrupted".equals(run.status)) {
                            var reason = failureReason(error, run.copy);
                            run.status = switch (reason) {
                                case STEP_LIMIT, TOOL_LIMIT, DATA_LIMIT, CONTEXT_LIMIT, TOKEN_LIMIT, TRUNCATED, RUN_TIMEOUT -> "incomplete";
                                default -> "failed";
                            };
                            run.error = error instanceof ResponseStatusException status
                                && status.getStatusCode().value() == 409 ? "页面已变化，请重新载入（409）"
                                : reason.message;
                            org.slf4j.LoggerFactory.getLogger(PageChatService.class).warn(
                                "Page chat failed: {}, reason={}, exception={}",
                                run.copy.trace(), reason.name(), error.getClass().getName());
                        }
                    }
                    return finish(name, run, null);
                }));
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
            }).then(Mono.defer(() -> {
                synchronized (run) {
                    return run.transition == null ? Mono.<Void>empty()
                        : run.transition.onErrorComplete();
                }
            })).then(Mono.defer(() -> {
                List<String> steers;
                synchronized (run) {
                    if ("cancelled".equals(run.status) || "interrupted".equals(run.status)) {
                        return finish(name, run, null);
                    }
                    // A steer that missed the last model step gets a bounded continuation call.
                    steers = run.continuations < MAX_CONTINUATIONS && !run.copy.exhausted()
                        ? run.copy.drainSteers() : List.of();
                    if (!steers.isEmpty()) run.continuations++;
                    else run.status = "committing";
                }
                if (!steers.isEmpty()) {
                    return converse(name, run, continuedHistory(run, history, steers.size()),
                        FoundationBridge.steerMessage(steers), runTimeout);
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
                if ("cancelled".equals(run.status) || "interrupted".equals(run.status)) {
                    return finish(name, run, null);
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
                run.status = "committing";
            }
            return persistActive(name, run).then(Mono.defer(() -> {
                var slugChanged = !Objects.equals(run.base.page().slug(), run.copy.slug());
                if (Objects.equals(run.base.document(), run.copy.document()) && !slugChanged) {
                    return Mono.just(run.base);
                }
                run.saveStarted = true;
                return pages.saveAi(name, run.request.version(), run.copy.document(),
                    run.actor, run.request.runId(), run.base.page().draftRevision(),
                    slugChanged ? run.copy.slug() : null);
            })).flatMap(page -> {
                run.status = "completed";
                if (!Objects.equals(page.page().draftRevision(), run.base.page().draftRevision())) {
                    run.revision = page.page().draftRevision();
                }
                return finish(name, run, page);
            });
        });
    }

    static AiGenerationException.Reason failureReason(Throwable error, PageWorkingCopy copy) {
        if (copy.exhausted()) return copy.budgetReason();
        for (var cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof AiGenerationException known) return known.reason();
            if (cause instanceof java.util.concurrent.TimeoutException) return AiGenerationException.Reason.TIMEOUT;
            if (cause.getCause() == cause) break;
        }
        return AiGenerationException.Reason.CALL_FAILED;
    }

    public Mono<ChatApi.CheckpointDetail> checkpoint(String name, String sessionId, String actor) {
        return pages.get(name).flatMap(page -> load(name).flatMap(this::recover)
            .flatMap(coordinator -> selected(name, sessionId, coordinator)
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

    private Mono<Void> saveCheckpoint(String name, PageRun run) {
        return Mono.defer(() -> {
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
                snapshot.setInvalidSlugUpdate(run.copy.hasInvalidSlugUpdate());
                snapshot.setTurn(run.turn());
                snapshot.setSteps(run.copy.budget.steps());
                snapshot.setToolCalls(run.copy.calls());
                snapshot.setRecoveries(run.copy.budget.recoveries());
                run.checkpointWrites = run.checkpointWrites.then(checkpoints.save(snapshot))
                    .doOnError(error -> {
                        run.copy.markCheckpointFailed();
                        run.failure.tryEmitValue(error);
                    }).cache();
                return run.checkpointWrites;
            }
        });
    }

    private Mono<Void> persistActive(String name, PageRun run) {
        return client.fetch(PageChatSession.class, name).flatMap(session -> {
            requireRun(session, run.request.runId());
            session.getSpec().setActive(run.turn());
            requireBoundedActive(session);
            return client.update(session);
        }).switchIfEmpty(Mono.error(conflict("会话不存在"))).then();
    }

    private Mono<Void> finish(String name, PageRun run, PageDetail page) {
        return run.copy.closeMemory().then(run.copy.closeEdits()).then(Mono.defer(() -> {
            synchronized (run) {
                return run.transition == null ? Mono.<Void>empty() : run.transition.onErrorComplete();
            }
        })).then(Mono.defer(() -> run.checkpointWrites.onErrorComplete()))
            .then(Mono.defer(() -> persistActive(name, run)))
            .doOnSuccess(ignored -> run.terminalPersisted = true)
            .then(Mono.defer(() -> {
                if ("completed".equals(run.status) || "cancelled".equals(run.status)) {
                    return checkpoints.delete(run.sessionId);
                }
                var save = run.copy.checkpointFailed() ? Mono.<Void>empty()
                    : saveCheckpoint(name, run).onErrorComplete();
                return save.then(checkpoints.available(run.sessionId, run.request.runId()));
            }))
            .then(client.fetch(PageChatSession.class, name)).flatMap(session -> {
            requireRun(session, run.request.runId());
            return archive(name, run.sessionId, run.turn())
                .then(touchSession(name, run.sessionId)).then(Mono.defer(() -> {
                    session.getSpec().setActive(null);
                    session.getSpec().setActiveSessionId(null);
                    session.getSpec().setUpdatedAt(Instant.now());
                    return client.update(session);
                }));
        }).switchIfEmpty(Mono.error(conflict("会话不存在"))).doOnSuccess(saved -> {
            org.slf4j.LoggerFactory.getLogger(PageChatService.class).info(
                "Page chat finished: {}, status={}, usage={}", run.copy.trace(), run.status, run.copy.usage());
            var terminalEvents = new ArrayList<Map<String, Object>>();
            if ("completed".equals(run.status)) {
                if (run.revision != null && page != null) {
                    terminalEvents.add(Map.of("type", "data-page-committed",
                        "name", "page-committed", "id", run.request.runId(), "data", page));
                }
                terminalEvents.add(Map.of("type", "finish", "finishReason", "stop"));
            } else if ("cancelled".equals(run.status)) {
                terminalEvents.add(Map.of("type", "abort"));
            } else {
                terminalEvents.add(Map.of("type", "error", "errorText", run.error));
            }
            run.completeEvents(terminalEvents);
        }).then();
    }

    public Mono<ChatApi.Run> result(String name, String runId) {
        return pages.get(name).flatMap(page -> load(name).flatMap(this::recover).flatMap(session -> {
            var current = session.getSpec().getActive();
            if (current != null && current.runId().equals(runId)) {
                return pages.committedAiRevision(name, runId)
                    .map(revision -> new ChatApi.Run(runId, "completed", revision, null, page))
                    .defaultIfEmpty(result(current, null));
            }
            return findTurn(name, runId)
                .flatMap(record -> {
                    var turn = record.getSpec().getTurn();
                    var result = result(turn, "completed".equals(turn.status()) ? page : null);
                    return checkpoints.load(record.getSpec().getSessionId())
                        .filter(value -> runId.equals(value.getSpec().getRequest().runId()))
                        .map(value -> result.withRecovery(PageCheckpoints.summary(value, page,
                            session.getSpec().getActive() != null))).defaultIfEmpty(result);
                })
                .switchIfEmpty(pages.committedAiRevision(name, runId)
                    .map(revision -> new ChatApi.Run(runId, "completed", revision, null, page)))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "运行记录不存在")));
        }));
    }

    public Mono<ChatApi.Run> cancel(String name, String runId) {
        return pages.get(name).then(Mono.defer(() -> {
            var run = runs.get(name);
            if (run != null && run.request.runId().equals(runId)) {
                synchronized (run) {
                    if (run.transition != null) {
                        return run.transition.then(Mono.defer(() -> cancel(name, runId)));
                    }
                    if ("running".equals(run.status) || "waiting_for_user".equals(run.status)) {
                        var previousStatus = run.status;
                        run.status = "cancelled";
                        // Persist intent before signalling the stream or acknowledging cancellation.
                        // Checkpoint writes may still be draining when the process stops.
                        run.transition = persistActive(name, run).doOnSuccess(ignored -> {
                            synchronized (run) {
                                run.transition = null;
                                run.waiting.update(null);
                                run.cancel.tryEmitEmpty();
                            }
                        }).doOnError(error -> {
                            synchronized (run) {
                                run.status = previousStatus;
                                run.transition = null;
                            }
                        }).cache();
                        return run.transition.then(Mono.defer(run.copy::closeMemory)).then(Mono.fromSupplier(() ->
                            new ChatApi.Run(runId, run.status, run.revision, run.error, null)));
                    }
                    return run.copy.closeMemory().then(Mono.fromSupplier(() ->
                        new ChatApi.Run(runId, run.status, run.revision, run.error, null)));
                }
            }
            return result(name, runId);
        }));
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
        return pages.withLivePage(name, () -> client.fetch(PageChatSession.class, name)
            .filter(session -> session.getMetadata().getDeletionTimestamp() == null)
            .switchIfEmpty(Mono.defer(() -> client.create(newSession(name, name, "默认会话"))
                .onErrorResume(error -> error instanceof OptimisticLockingFailureException
                    || error instanceof DataIntegrityViolationException,
                    error -> client.fetch(PageChatSession.class, name))))
            .flatMap(session -> {
                if (session.getSpec().getPageName() != null && !name.equals(session.getSpec().getPageName())) {
                    return Mono.error(conflict("默认会话名称冲突"));
                }
                return migrate(name, session).flatMap(this::completeSessionDeletion);
            }));
    }

    private Mono<PageChatSession> completeSessionDeletion(PageChatSession coordinator) {
        var deletingId = coordinator.getSpec().getDeletingSessionId();
        if (deletingId == null) return Mono.just(coordinator);
        var name = coordinator.getMetadata().getName();
        return client.listAll(PageChatTurnRecord.class, turnOptions(deletingId), Sort.unsorted())
            .concatMap(client::delete).then(checkpoints.delete(deletingId))
            .then(Mono.defer(() -> deletingId.equals(name) ? Mono.empty()
                : client.fetch(PageChatSession.class, deletingId)
                    .filter(session -> session.getMetadata().getDeletionTimestamp() == null
                        && name.equals(session.getSpec().getPageName()))
                    .flatMap(client::delete).then()))
            .then(client.fetch(PageChatSession.class, name)).flatMap(latest -> {
                if (!deletingId.equals(latest.getSpec().getDeletingSessionId())) return Mono.just(latest);
                latest.getSpec().setDeletingSessionId(null);
                if (deletingId.equals(name)) {
                    latest.getSpec().setTitle("默认会话");
                    latest.getSpec().setUpdatedAt(Instant.now());
                }
                return client.update(latest);
            });
    }

    private Mono<PageChatSession> selected(String name, String sessionId, PageChatSession coordinator) {
        if (sessionId == null || sessionId.equals(name)) return Mono.just(coordinator);
        return client.fetch(PageChatSession.class, sessionId)
            .filter(session -> session.getMetadata().getDeletionTimestamp() == null
                && name.equals(session.getSpec().getPageName()))
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在")))
            .flatMap(session -> migrate(name, session));
    }

    private Mono<PageChatSession> migrate(String name, PageChatSession session) {
        var spec = session.getSpec();
        var id = session.getMetadata().getName();
        boolean initialize = spec.getPageName() == null;
        if (!initialize && spec.getTurns().isEmpty()) return Mono.just(session);
        return Flux.fromIterable(List.copyOf(spec.getTurns()))
            .concatMap(turn -> archive(name, id, turn)).then(Mono.defer(() -> {
                if (initialize) {
                    spec.setPageName(name);
                    spec.setTitle("默认会话");
                    spec.setCreatedAt(session.getMetadata().getCreationTimestamp() == null
                        ? Instant.now() : session.getMetadata().getCreationTimestamp());
                    spec.setUpdatedAt(spec.getCreatedAt());
                }
                spec.getTurns().clear();
                return client.update(session);
            }));
    }

    private static PageChatSession newSession(String name, String id, String title) {
        var session = new PageChatSession();
        var metadata = new Metadata();
        metadata.setName(id);
        session.setMetadata(metadata);
        var spec = new PageChatSession.Spec();
        spec.setPageName(name);
        spec.setTitle(title);
        spec.setCreatedAt(Instant.now());
        spec.setUpdatedAt(spec.getCreatedAt());
        session.setSpec(spec);
        return session;
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
        return (terminal ? Mono.just(turn) : pages.committedAiRevision(name, turn.runId()).map(revision -> new ChatApi.Turn(
                turn.runId(), turn.actor(), turn.createdAt(), turn.prompt(), turn.text(), turn.tools(),
                "completed", revision, null, turn.parts(), turn.annotations(), turn.questions(), turn.steers()).withUsage(turn.usage()))
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
                    .then(archive(name, sessionId, recovered)).then(touchSession(name, sessionId))
                    .then(Mono.defer(() -> {
                        session.getSpec().setActive(null);
                        session.getSpec().setActiveSessionId(null);
                        session.getSpec().setUpdatedAt(Instant.now());
                        return client.update(session);
                    }));
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

    private Mono<List<PageChatTurnRecord>> history(String sessionId, String before, int size) {
        var options = turnOptions(sessionId);
        if (before != null) {
            options.setFieldSelector(options.getFieldSelector().andQuery(lessThan("spec.sortKey", before)));
        }
        return client.listBy(PageChatTurnRecord.class, options,
            PageRequestImpl.of(1, size, Sort.by(Sort.Order.desc("spec.sortKey"))))
            .map(result -> result.getItems());
    }

    private Mono<PageChatTurnRecord> findTurn(String name, String runId) {
        var options = turnPageOptions(name);
        options.setFieldSelector(options.getFieldSelector().andQuery(equal("spec.runId", runId)));
        return client.listBy(PageChatTurnRecord.class, options,
                PageRequestImpl.of(1, 1, Sort.unsorted()))
            .flatMap(result -> Mono.justOrEmpty(result.getItems().stream().findFirst()));
    }

    private Mono<PageChatTurnRecord> archive(String name, String sessionId, ChatApi.Turn turn) {
        var record = new PageChatTurnRecord();
        var metadata = new Metadata();
        metadata.setName(turnName(sessionId, turn.runId()));
        record.setMetadata(metadata);
        var spec = new PageChatTurnRecord.Spec();
        spec.setPageName(name);
        spec.setSessionId(sessionId);
        spec.setRunId(turn.runId());
        spec.setSortKey(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'")
            .withZone(java.time.ZoneOffset.UTC).format(turn.createdAt()) + "/" + turn.runId());
        spec.setTurn(turn);
        record.setSpec(spec);
        return client.fetch(PageChatTurnRecord.class, metadata.getName())
            .switchIfEmpty(Mono.defer(() -> client.create(record).onErrorResume(
                error -> error instanceof OptimisticLockingFailureException
                    || error instanceof DataIntegrityViolationException,
                error -> client.fetch(PageChatTurnRecord.class, metadata.getName())
                    .switchIfEmpty(Mono.error(error)))));
    }

    static String turnName(String sessionId, String runId) {
        return UUID.nameUUIDFromBytes((sessionId + "/" + runId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private Mono<Void> touchSession(String name, String sessionId) {
        if (name.equals(sessionId)) return Mono.empty();
        return client.fetch(PageChatSession.class, sessionId).flatMap(session -> {
            session.getSpec().setUpdatedAt(Instant.now());
            return client.update(session);
        }).then();
    }

    public static ListOptions sessionOptions(String name) {
        return new ListOptions().setFieldSelector(FieldSelector.of(or(equal("spec.pageName", name),
            and(equal("metadata.name", name), isNull("spec.pageName"))))
            .andQuery(isNull("metadata.deletionTimestamp")));
    }

    public static ListOptions turnPageOptions(String name) {
        return new ListOptions().setFieldSelector(FieldSelector.of(equal("spec.pageName", name))
            .andQuery(isNull("metadata.deletionTimestamp")));
    }

    private static ListOptions turnOptions(String sessionId) {
        return new ListOptions().setFieldSelector(FieldSelector.of(equal("spec.sessionId", sessionId))
            .andQuery(isNull("metadata.deletionTimestamp")));
    }

    /** Called after the page is deleted; requires no PageChatService bean dependency. */
    public static Mono<Void> deletePageHistory(ReactiveExtensionClient client, String name) {
        return client.listAll(PageChatTurnRecord.class, turnPageOptions(name), Sort.unsorted())
            .concatMap(client::delete)
            .thenMany(client.listAll(PageChatCheckpoint.class, turnPageOptions(name), Sort.unsorted()))
            .concatMap(client::delete)
            .thenMany(client.listAll(PageChatSession.class, sessionOptions(name), Sort.unsorted()))
            .concatMap(client::delete).then();
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

}
