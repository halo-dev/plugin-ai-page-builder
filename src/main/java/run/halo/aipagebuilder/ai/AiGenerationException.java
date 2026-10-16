package run.halo.aipagebuilder.ai;

final class AiGenerationException extends RuntimeException {
    enum Reason {
        STEP_LIMIT("step-limit", "本轮已达到 20 个模型步骤，草稿未更新。可继续生成。"),
        CHECKPOINT_FAILED("checkpoint", "进度保存失败，已停止生成。请检查存储后从上次已保存的进度继续。"),
        MODEL_UNAVAILABLE("model-resolution",
            "AI 模型不可用，请检查 AI Foundation 是否已启用，并配置可用的默认或指定文本模型。"),
        CALL_FAILED("model-call",
            "AI 生成失败：模型调用未成功，请检查供应商连接、凭据和模型配置后重试。"),
        TRUNCATED("model-finish",
            "本次输出被截断，自动恢复次数已用完。可从已保存的进度继续生成。"),
        RUN_TIMEOUT("run-timeout",
            "本轮生成达到 10 分钟运行时限，草稿未更新。可从已保存的进度继续生成。"),
        RESPONSE_TIMEOUT("response-timeout",
            "模型或工具连续 120 秒没有返回新事件，等待响应超时，未保存修改；请检查连接或稍后重试。"),
        TIMEOUT("upstream-timeout",
            "模型服务或上游连接报告超时，未保存修改；这不是插件的 10 分钟整轮截止，请检查服务状态后重试。"),
        TOOL_LIMIT("tool-limit", "本轮工具调用达到 40 次上限，草稿未更新。可从已保存的进度继续生成。"),
        DATA_LIMIT("data-limit", "本轮工具数据与流式输出累计超过 2 MiB，未保存修改；请减少参考资料或缩小修改范围。"),
        TOOL_LOOP_INCOMPLETE("tool-loop", "工具循环未正常完成，草稿未更新。请检查工具执行结果后继续。"),
        STREAM_INCOMPLETE("stream", "模型响应流未正常结束，未保存修改；请检查连接或稍后重试。"),
        DISPLAY_LIMIT("display-limit", "本轮助手文字超过 16000 字符，未保存修改；请要求模型简短回复并通过工具修改源码。"),
        STORAGE_LIMIT("storage-limit", "本轮对话记录超过 256 KiB 存储上限，未保存修改；请减少批注或缩短回复。"),
        INVALID_UPDATE("tool-validation", "最后一次页面修改未通过校验，未保存修改；请查看工具错误并让模型修正。"),
        FORMAT("candidate-json",
            "AI 返回的页面格式不正确，请重试，或简化要求并注明仅生成静态页面。"),
        HTML_UNSUPPORTED("candidate-html-policy",
            "AI 生成的 HTML 不受支持，请仅生成正文片段，移除脚本、事件属性、SVG、iframe 和文档外壳后重试。"),
        CSS_UNSUPPORTED("candidate-css-policy",
            "AI 生成的 CSS 不受支持，请使用普通样式、@media 或 @supports，移除导入、字体声明、动画和嵌套选择器后重试。"),
        OUTPUT_LIMIT("output-limit",
            "AI 生成内容超过页面长度限制，请减少页面内容或分批描述修改要求后重试。"),
        INVALID_REQUEST("request-validation",
            "生成要求或当前页面内容无效，请填写生成要求并缩短过长的内容后重试。");

        final String stage;
        final String message;

        Reason(String stage, String message) {
            this.stage = stage;
            this.message = message;
        }
    }

    private final Reason reason;

    AiGenerationException(Reason reason) {
        this(reason, null);
    }

    AiGenerationException(Reason reason, Throwable cause) {
        super(reason.message, cause);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
