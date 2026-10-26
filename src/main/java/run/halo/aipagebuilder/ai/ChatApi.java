package run.halo.aipagebuilder.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import run.halo.aipagebuilder.page.PageApi.PageDetail;

/** Public chat contracts intentionally have no Foundation SDK types. */
public final class ChatApi {
    private ChatApi() { }

    @Schema(name = "PageChatRequest", requiredProperties = {"runId", "version", "modelName", "prompt"})
    public record Request(String runId, Long version, String modelName, String prompt,
                          String baseHash, List<Annotation> annotations, String sessionId,
                          String resumeFromRunId, Long checkpointVersion) {
        public Request(String runId, Long version, String modelName, String prompt,
            String baseHash, List<Annotation> annotations, String sessionId) {
            this(runId, version, modelName, prompt, baseHash, annotations, sessionId, null, null);
        }
        public Request(String runId, Long version, String modelName, String prompt,
            String baseHash, List<Annotation> annotations) {
            this(runId, version, modelName, prompt, baseHash, annotations, null);
        }

        public Request(String runId, Long version, String modelName, String prompt) {
            this(runId, version, modelName, prompt, null, List.of(), null);
        }
    }

    @Schema(name = "PageAnnotation", requiredProperties = {"id", "path", "tag", "summary", "html", "comment"})
    public record Annotation(String id, List<Integer> path, String tag, String summary,
                             String html, String comment) { }

    @Schema(name = "PageChatDisplayPart", requiredProperties = {"type", "id"})
    public record DisplayPart(String type, String id, String text, String toolName,
                              String status, String summary, Boolean truncated) { }

    @Schema(name = "PageChatTool", requiredProperties = {"name", "status", "summary"})
    public record Tool(String name, String status, String summary) { }

    @Schema(name = "PageChatTurn", requiredProperties = {"runId", "actor", "createdAt", "prompt", "text", "tools", "status"})
    public record Turn(String runId, String actor, Instant createdAt, String prompt, String text,
                       List<Tool> tools, String status, String revision, String error,
                       List<DisplayPart> parts, List<Annotation> annotations,
                       List<Question> questions, List<String> steers, Recovery recovery,
                       @Schema(description = "Reported model usage and bounded resource counters for this run") Usage usage) {
        public Turn(String runId, String actor, Instant createdAt, String prompt, String text,
            List<Tool> tools, String status, String revision, String error, List<DisplayPart> parts,
            List<Annotation> annotations, List<Question> questions, List<String> steers, Recovery recovery) {
            this(runId, actor, createdAt, prompt, text, tools, status, revision, error,
                parts, annotations, questions, steers, recovery, null);
        }

        public Turn withUsage(Usage value) {
            return new Turn(runId, actor, createdAt, prompt, text, tools, status, revision, error,
                parts, annotations, questions, steers, recovery, value);
        }

        public Turn(String runId, String actor, Instant createdAt, String prompt, String text,
            List<Tool> tools, String status, String revision, String error, List<DisplayPart> parts,
            List<Annotation> annotations, List<Question> questions, List<String> steers) {
            this(runId, actor, createdAt, prompt, text, tools, status, revision, error,
                parts, annotations, questions, steers, null);
        }

        public Turn withRecovery(Recovery value) {
            return new Turn(runId, actor, createdAt, prompt, text, tools, status, revision, error,
                parts, annotations, questions, steers, value, usage);
        }
        public Turn {
            steers = steers == null ? List.of() : List.copyOf(steers);
        }

        public Turn(String runId, String actor, Instant createdAt, String prompt, String text,
            List<Tool> tools, String status, String revision, String error,
            List<DisplayPart> parts, List<Annotation> annotations,
            List<Question> questions) {
            this(runId, actor, createdAt, prompt, text, tools, status, revision, error,
                parts, annotations, questions, List.of());
        }

        public Turn(String runId, String actor, Instant createdAt, String prompt, String text,
            List<Tool> tools, String status, String revision, String error,
            List<DisplayPart> parts, List<Annotation> annotations) {
            this(runId, actor, createdAt, prompt, text, tools, status, revision, error,
                parts, annotations, List.of(), List.of());
        }

        public Turn(String runId, String actor, Instant createdAt, String prompt, String text,
                    List<Tool> tools, String status, String revision, String error) {
            this(runId, actor, createdAt, prompt, text, tools, status, revision, error, null, null,
                List.of(), List.of());
        }
    }

    @Schema(name = "PageChatUsage", description = "Reported provider usage; null token totals mean unknown. Context size is UTF-8 bytes, not a tokenizer estimate.")
    public record Usage(String model, String promptVersion, int steps, int reportedSteps,
                         int missingUsageSteps, Long inputTokens, Long outputTokens,
                         long elapsedMillis, long toolBytes, long previewBytes, int maxContextBytes) { }

    @Schema(name = "PageChatSteerRequest", requiredProperties = {"text"})
    public record SteerRequest(String text) { }

    @Schema(name = "PageChatRun", requiredProperties = {"runId", "status"})
    public record Run(String runId, String status, String revision, String error, PageDetail page,
                      Question pendingQuestion, Recovery recovery) {
        public Run(String runId, String status, String revision, String error, PageDetail page,
            Question pendingQuestion) {
            this(runId, status, revision, error, page, pendingQuestion, null);
        }

        public Run withRecovery(Recovery value) {
            return new Run(runId, status, revision, error, page, pendingQuestion, value);
        }
        public Run(String runId, String status, String revision, String error, PageDetail page) {
            this(runId, status, revision, error, page, null);
        }
    }

    @Schema(name = "PageChatActiveRun", requiredProperties = {"pageName", "sessionId", "runId", "status", "startedAt"})
    public record ActiveRun(String pageName, String sessionId, String runId, String status,
                            Instant startedAt, Question pendingQuestion) { }

    @Schema(name = "PageChatActiveRunList", requiredProperties = {"items"})
    public record ActiveRunList(List<ActiveRun> items) { }

    @Schema(name = "PageChatRecovery", requiredProperties = {"sourceRunId", "checkpointVersion", "canResume", "updatedAt"})
    public record Recovery(String sourceRunId, Long checkpointVersion, boolean canResume,
                           String blockedReason, Instant updatedAt) { }

    @Schema(name = "PageChatCheckpointDetail", requiredProperties = {"document", "request", "recovery"})
    public record CheckpointDetail(run.halo.aipagebuilder.model.PageDocument document,
                                   Request request, Recovery recovery) { }

    @Schema(name = "PageChatQuestionOption", requiredProperties = {"id", "label"})
    public record QuestionOption(String id, String label) { }

    @Schema(name = "PageChatQuestionItem", requiredProperties = {"id", "title", "options", "allowCustomAnswer"})
    public record QuestionItem(String id, String title, List<QuestionOption> options,
                               boolean allowCustomAnswer) { }

    @Schema(name = "PageChatAnswerItem", requiredProperties = {"questionId"})
    public record AnswerItem(String questionId, String optionId, String text) { }

    @Schema(name = "PageChatAnswerRequest", requiredProperties = {"answers"})
    public record AnswerRequest(List<AnswerItem> answers) { }

    @Schema(name = "PageChatQuestion", requiredProperties = {"id", "questions", "createdAt"})
    public record Question(String id, List<QuestionItem> questions, List<AnswerItem> answers,
                           Instant createdAt, Instant answeredAt) { }

    @Schema(name = "PageChatModelContextWindow", description = "History selected for the model; archived conversations are not deleted by these limits.", requiredProperties = {"maxTurns", "maxBytes"})
    public record ModelContextWindow(int maxTurns, int maxBytes) { }

    @Schema(name = "PageChatSession", requiredProperties = {"pageName", "version", "turns", "modelContextWindow", "sessionId", "title", "hasMore"})
    public record Session(String pageName, Long version, List<Turn> turns, Run activeRun,
                          @Schema(description = "Model history window; does not delete archived turns")
                          ModelContextWindow modelContextWindow, String sessionId, String title, Instant createdAt,
                          Instant updatedAt, boolean hasMore, String nextBefore, Recovery recovery) {
        public Session(String pageName, Long version, List<Turn> turns, Run activeRun,
            ModelContextWindow modelContextWindow, String sessionId, String title, Instant createdAt,
            Instant updatedAt, boolean hasMore, String nextBefore) {
            this(pageName, version, turns, activeRun, modelContextWindow, sessionId, title,
                createdAt, updatedAt, hasMore, nextBefore, null);
        }

        public Session withRecovery(Recovery value) {
            return new Session(pageName, version, turns.stream().map(turn -> turn.withRecovery(
                value != null && turn.runId().equals(value.sourceRunId()) ? value : null)).toList(),
                activeRun, modelContextWindow, sessionId, title, createdAt, updatedAt, hasMore, nextBefore, value);
        }
        public Session(String pageName, Long version, List<Turn> turns, Run activeRun,
            ModelContextWindow modelContextWindow) {
            this(pageName, version, turns, activeRun, modelContextWindow, pageName, "默认会话",
                null, null, false, null);
        }
    }

    @Schema(name = "PageChatSessionSummary", requiredProperties = {"sessionId", "pageName", "title", "version", "createdAt", "updatedAt"})
    public record SessionSummary(String sessionId, String pageName, String title, Long version,
                                 Instant createdAt, Instant updatedAt, Run activeRun) { }

    @Schema(name = "PageChatSessionList", requiredProperties = {"items"})
    public record SessionList(List<SessionSummary> items) { }

    @Schema(name = "CreatePageChatSessionRequest")
    public record CreateSession(String title) { }

    @Schema(name = "RenamePageChatSessionRequest", requiredProperties = {"title", "version"})
    public record RenameSession(String title, Long version) { }

    @Schema(name = "ValidatePageContentRequest", requiredProperties = {"html", "css"})
    public record Content(String html, String css, String js) {
        public Content {
            js = js == null ? "" : js;
        }

        public Content(String html, String css) {
            this(html, css, "");
        }
    }
}
