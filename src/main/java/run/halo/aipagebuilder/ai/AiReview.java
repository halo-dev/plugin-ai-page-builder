package run.halo.aipagebuilder.ai;

import java.util.List;

/**
 * Structured security review of an untrusted page archive.
 *
 * <p>This type must stay free of AI Foundation SDK types: it is referenced by the plugin's
 * Spring components, while the SDK-bearing bridge is loaded reflectively.
 */
public record AiReview(Verdict verdict, String summary, List<Finding> findings) {
    public enum Verdict {
        SAFE, SUSPICIOUS, MALICIOUS
    }

    public enum Severity {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    public enum Category {
        MALICIOUS_SCRIPT, DATA_EXFILTRATION, PHISHING, OBFUSCATION, EXTERNAL_RESOURCE,
        PROMPT_INJECTION, CONTENT_POLICY, OTHER
    }

    public record Finding(Severity severity, Category category, String file, String evidence,
                          String explanation) {
    }
}
