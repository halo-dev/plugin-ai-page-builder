package run.halo.aipagebuilder.ai;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.aipagebuilder.theme.ThemeStyleContext;
import run.halo.app.infra.utils.JsonUtils;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;

/** One run, one page. Tools never receive a client or a page identifier. */
public final class PageWorkingCopy {
    static final int MAX_CALLS = 20;
    static final int MAX_BYTES = 2 * 1024 * 1024;
    private final ContentPolicy policy;
    private final Mono<ThemeStyleContext> themeContext;
    private PageDocument document;
    private int calls;
    private int bytes;
    private boolean exhausted;
    private AiGenerationException.Reason budgetReason;
    private boolean invalidUpdate;
    private ThemeStyleContext themeSnapshot;
    private PageReferenceTools.Session references;
    private PageMemoryTools memory;
    private boolean memoryUnavailable;
    private Mono<Void> memoryCompletion = Mono.empty();
    private final Map<String, ChatApi.Tool> memoryOutcomes = new LinkedHashMap<>();

    synchronized void memory(PageMemoryTools memory) {
        this.memory = memory;
    }

    synchronized void memoryUnavailable() {
        memoryUnavailable = true;
        summaries.add(new ChatApi.Tool(PageMemoryTools.NAME, "error", "记忆读取失败，本轮未使用记忆"));
    }

    synchronized String memoryReference() {
        return memory != null ? memory.reference() : memoryUnavailable
            ? "Site memory could not be loaded. Do not claim there are no saved facts. "
                + "Briefly report memory is unavailable this turn; continue the page task."
            : "";
    }

    synchronized boolean hasMemory() {
        return memory != null;
    }

    synchronized Mono<Void> closeMemory() {
        return memory == null ? Mono.empty() : memory.close().then(memoryCompletion);
    }

    synchronized Map<String, ChatApi.Tool> memoryOutcomes() {
        return Map.copyOf(memoryOutcomes);
    }

    public Mono<Map<String, Object>> updateMemory(Map<String, Object> input) {
        return updateMemory(null, input);
    }

    public Mono<Map<String, Object>> updateMemory(String toolCallId, Map<String, Object> input) {
        return Mono.defer(() -> {
            synchronized (this) {
                call();
                consume(JsonUtils.objectToJson(input));
                var result = memory.execute(input).doOnNext(output -> {
                    synchronized (this) {
                        var outcome = new ChatApi.Tool(PageMemoryTools.NAME,
                            output.containsKey("error") ? "error" : "completed",
                            String.valueOf(output.get("summary")));
                        summaries.add(outcome);
                        if (toolCallId != null) memoryOutcomes.put(toolCallId, outcome);
                        consume(JsonUtils.objectToJson(output));
                    }
                }).cache();
                memoryCompletion = Mono.when(memoryCompletion, result.then()).onErrorComplete();
                return result;
            }
        });
    }
    private List<ChatApi.Annotation> annotations = List.of();
    private final List<String> pendingSteers = new ArrayList<>();
    final ActiveTimeBudget timeBudget = new ActiveTimeBudget();
    private java.util.function.Function<ChatApi.Question, Mono<ChatApi.Question>> questionHandler;

    void questions(java.util.function.Function<ChatApi.Question, Mono<ChatApi.Question>> handler) {
        this.questionHandler = handler;
    }

    public Mono<Object> askUser(Map<String, Object> input) {
        return Mono.defer(() -> {
            synchronized (this) { call(); consume(JsonUtils.objectToJson(input)); }
            ChatApi.Question question;
            try {
                question = UserQuestions.parse(input);
            } catch (ResponseStatusException error) {
                return Mono.just(Map.of("error", error.getReason()));
            }
            if (questionHandler == null) return Mono.error(new IllegalStateException("提问不可用"));
            return questionHandler.apply(question).map(answered -> {
                synchronized (this) {
                    consume(JsonUtils.objectToJson(answered));
                    summaries.add(new ChatApi.Tool("ask_user", "completed", "用户已回答问题"));
                }
                if (memory == null) return (Object) answered;
                return (Object) Map.of("question", answered, "memoryReference", memory.reference());
            });
        });
    }
    private final List<ChatApi.Tool> summaries = new ArrayList<>();

    public PageWorkingCopy(PageDocument document, ContentPolicy policy) {
        this(document, policy, Mono.just(ThemeStyleContext.unavailable()));
    }

    public PageWorkingCopy(PageDocument document, ContentPolicy policy,
        Mono<ThemeStyleContext> themeContext) {
        this.document = document;
        this.policy = policy;
        this.themeContext = themeContext.cache();
    }

    public Mono<ThemeStyleContext> prepareTheme() {
        return themeContext.doOnNext(context -> {
            synchronized (this) {
                if (references != null && context.themeName() != null) {
                    references.bindTheme(context.themeName());
                }
                themeSnapshot = context;
            }
        });
    }

    public Mono<ThemeStyleContext> readTheme() {
        return Mono.defer(() -> {
            synchronized (this) { call(); }
            return prepareTheme().doOnNext(context -> {
                synchronized (this) {
                    consume(JsonUtils.objectToJson(context));
                    summaries.add(new ChatApi.Tool("get_theme_context", "completed",
                        "已读取主题样式参考（" + context.layoutState() + "）"));
                }
            });
        });
    }

    public synchronized void references(PageReferenceTools.Session references) {
        this.references = references;
    }

    public Mono<Map<String, Object>> reference(String name, Map<String, Object> input) {
        return Mono.defer(() -> {
            synchronized (this) { call(); consume(JsonUtils.objectToJson(input)); }
            if (references == null) return Mono.just(Map.of("error", "参考工具不可用"));
            return Mono.defer(() -> references.execute(name, input))
                .onErrorResume(error -> error instanceof ResponseStatusException
                    || error instanceof IllegalArgumentException || error instanceof IllegalStateException,
                    error -> {
                        var message = error instanceof ResponseStatusException status
                            ? status.getReason() : error.getMessage();
                        var summary = message == null ? "参考资料读取失败"
                            : message.substring(0, Math.min(300, message.length()));
                        return Mono.just(Map.of("error", summary));
                    })
                .doOnNext(result -> {
                    synchronized (this) {
                        consume(JsonUtils.objectToJson(result));
                        summaries.add(new ChatApi.Tool(name, result.containsKey("error") ? "error" : "completed",
                            result.containsKey("error") ? String.valueOf(result.get("error")) : "已读取参考资料"));
                    }
                });
        });
    }

    public synchronized void annotations(List<ChatApi.Annotation> annotations) {
        this.annotations = List.copyOf(annotations);
    }

    /** Queues a mid-run user instruction; injected at the next model step. */
    synchronized void queueSteer(String text) {
        pendingSteers.add(text);
    }

    /** Steers accepted since the last model step; each is injected exactly once. */
    public synchronized List<String> drainSteers() {
        if (pendingSteers.isEmpty()) return List.of();
        var drained = List.copyOf(pendingSteers);
        pendingSteers.clear();
        return drained;
    }

    public synchronized Map<String, Object> read() {
        call();
        var result = Map.<String, Object>of("html", document.html(), "css", document.css(), "js", document.js(),
            "title", document.title(), "baseHash", baseHash(), "annotations", annotations);
        consume(JsonUtils.objectToJson(result));
        summaries.add(new ChatApi.Tool("read_current_page", "completed", "已读取当前工作副本"));
        return result;
    }

    public synchronized Map<String, Object> update(Map<String, Object> input) {
        call();
        consume(JsonUtils.objectToJson(input));
        if (input == null || !(input.get("html") instanceof String html)
            || !(input.get("css") instanceof String css)) {
            return invalid("update_current_page", "html 和 css 必须是字符串");
        }
        if (input.containsKey("js") && !(input.get("js") instanceof String)) {
            return invalid("update_current_page", "js 必须是字符串");
        }
        return stage("update_current_page", html, css,
            input.get("js") instanceof String js ? js : document.js());
    }

    public synchronized Map<String, Object> patch(Map<String, Object> input) {
        call();
        consume(JsonUtils.objectToJson(input));
        var tool = "patch_current_page";
        if (input == null || !(input.get("baseHash") instanceof String hash)
            || !baseHash().equals(hash)) {
            return invalid(tool, "baseHash 与当前工作副本不一致，请重新读取页面");
        }
        if (!(input.get("patches") instanceof List<?> patches)
            || patches.isEmpty() || patches.size() > 20) {
            return invalid(tool, "patches 必须包含 1–20 项修改");
        }
        var replacements = new ArrayList<Replacement>();
        for (var entry : patches) {
            if (!(entry instanceof Map<?, ?> patch)
                || !(patch.get("field") instanceof String field)
                || !("html".equals(field) || "css".equals(field) || "js".equals(field))
                || !(patch.get("oldText") instanceof String oldText) || oldText.isEmpty()
                || !(patch.get("newText") instanceof String newText)) {
                return invalid(tool, "每项修改须指定 html/css/js 字段、非空 oldText 和字符串 newText");
            }
            var original = switch (field) {
                case "html" -> document.html();
                case "css" -> document.css();
                default -> document.js();
            };
            var start = original.indexOf(oldText);
            if (start < 0 || original.indexOf(oldText, start + 1) >= 0) {
                return invalid(tool, "oldText 必须在原始字段中精确且唯一匹配");
            }
            var end = start + oldText.length();
            for (var replacement : replacements) {
                if (replacement.field().equals(field)
                    && start < replacement.end() && replacement.start() < end) {
                    return invalid(tool, "同批修改的匹配区间不能重叠");
                }
            }
            replacements.add(new Replacement(field, start, end, newText));
        }
        var html = new StringBuilder(document.html());
        var css = new StringBuilder(document.css());
        var js = new StringBuilder(document.js());
        replacements.sort(Comparator.comparingInt(Replacement::start).reversed());
        for (var replacement : replacements) {
            var target = switch (replacement.field()) {
                case "html" -> html;
                case "css" -> css;
                default -> js;
            };
            target.replace(replacement.start(), replacement.end(), replacement.newText());
        }
        return stage(tool, html.toString(), css.toString(), js.toString());
    }

    public synchronized Map<String, Object> setTitle(Map<String, Object> input) {
        call();
        consume(JsonUtils.objectToJson(input));
        var tool = "update_page_title";
        if (input == null || !(input.get("title") instanceof String raw)) {
            return invalid(tool, "title 必须是字符串");
        }
        var title = raw.trim();
        try {
            var changed = !title.equals(document.title());
            var candidate = changed
                ? new PageDocument(document.js().isEmpty() ? 1 : 2, "code", null, null,
                    document.html(), document.css(), title, document.description(), document.js())
                : document;
            policy.validate(candidate);
            document = candidate;
            invalidUpdate = false;
            summaries.add(new ChatApi.Tool(tool, "completed",
                changed ? "已暂存新标题" : "标题未变化"));
            return Map.of("valid", true, "staged", changed, "baseHash", baseHash());
        } catch (ResponseStatusException error) {
            return invalid(tool, error.getReason());
        }
    }

    private Map<String, Object> stage(String tool, String html, String css, String js) {
        try {
            boolean changed = !html.equals(document.html()) || !css.equals(document.css())
                || !js.equals(document.js());
            var candidate = changed
                ? new PageDocument(js.isEmpty() ? 1 : 2, "code", null, null, html, css,
                    document.title(), document.description(), js) : document;
            policy.validate(candidate);
            if (!css.equals(document.css()) && themeSnapshot != null) {
                policy.validateThemeColorMode(css, themeSnapshot);
            }
            document = candidate;
            invalidUpdate = false;
            summaries.add(new ChatApi.Tool(tool, "completed",
                changed ? "已暂存通过校验的修改" : "内容未变化"));
            return Map.of("valid", true, "staged", changed, "baseHash", baseHash());
        } catch (ResponseStatusException error) {
            var result = new LinkedHashMap<>(invalid(tool, error.getReason()));
            result.put("guidance", "The rejected candidate was not staged. Fix syntax errors within "
                + "the supported policy; do not try equivalent workarounds for unsupported capabilities. "
                + "Generated HTML does not support form/input/textarea/select/option/label or "
                + "contenteditable. Use buttons or details/summary for supported interactions; "
                + "ask_user before replacing a required feature. Do not create inputs via JS or "
                + "simulate a textbox. CSS supports ordinary rules, variables, @media and @supports, "
                + "not @keyframes or @font-face. An unresolved failed update prevents this turn from saving.");
            return result;
        }
    }

    private Map<String, Object> invalid(String tool, String message) {
        invalidUpdate = true;
        var summary = message == null ? "内容校验失败" : message.substring(0, Math.min(300, message.length()));
        summaries.add(new ChatApi.Tool(tool, "error", summary));
        return Map.of("valid", false, "staged", false, "error", summary, "baseHash", baseHash());
    }

    private String baseHash() {
        return DocumentHash.of(document.html(), document.css(), document.js());
    }

    private record Replacement(String field, int start, int end, String newText) {
    }

    private void call() {
        if (++calls > MAX_CALLS || exhausted) {
            exhausted = true;
            if (budgetReason == null) budgetReason = AiGenerationException.Reason.TOOL_LIMIT;
            throw new IllegalStateException("工具调用预算已耗尽");
        }
    }

    public synchronized void consume(String value) {
        bytes += value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) {
            exhausted = true;
            if (budgetReason == null) budgetReason = AiGenerationException.Reason.DATA_LIMIT;
            throw new IllegalStateException("本轮输出预算已耗尽");
        }
    }

    public synchronized boolean exhausted() { return exhausted; }
    synchronized AiGenerationException.Reason budgetReason() { return budgetReason; }
    public synchronized boolean hasInvalidUpdate() { return invalidUpdate; }
    public synchronized PageDocument document() { return document; }
    public synchronized List<ChatApi.Tool> summaries() { return List.copyOf(summaries); }
}
