package run.halo.aipagebuilder.ai;

/** Failure raised while running an AI import review. Messages are safe to show to the user. */
public final class AiReviewException extends RuntimeException {
    public enum Reason {
        UNAVAILABLE, TIMEOUT, INVALID_OUTPUT, FAILED
    }

    private final Reason reason;

    public AiReviewException(Reason reason) {
        super(message(reason));
        this.reason = reason;
    }

    public AiReviewException(Reason reason, Throwable cause) {
        super(message(reason), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    private static String message(Reason reason) {
        return switch (reason) {
            case UNAVAILABLE ->
                "AI 审查不可用，请检查 AI Foundation 是否已启用并配置可用的语言模型。";
            case TIMEOUT -> "AI 审查超时，请稍后重试。";
            case INVALID_OUTPUT -> "AI 审查结果无法解析，请重试。";
            case FAILED -> "AI 审查失败，请稍后重试。";
        };
    }
}
