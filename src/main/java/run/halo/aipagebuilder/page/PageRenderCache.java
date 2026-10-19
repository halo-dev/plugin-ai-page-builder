package run.halo.aipagebuilder.page;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import run.halo.aipagebuilder.Json;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;

/** Plugin-local derived content only; never caches identity, response headers or theme HTML. */
final class PageRenderCache {
    private static final int MAX_ENTRIES = 128;
    private static final long MAX_BYTES = 16 * 1024 * 1024;
    private final ContentPolicy policy;
    private final LinkedHashMap<String, Compiled> entries = new LinkedHashMap<>(16, .75f, true);
    private long bytes;

    PageRenderCache(ContentPolicy policy) { this.policy = policy; }

    Compiled compile(String rootId, PageDocument document) {
        // Hash the complete input so cached validation cannot bypass metadata/schema checks.
        var key = rootId + ":" + hash(Json.toJson(document));
        synchronized (this) {
            var cached = entries.get(key);
            if (cached != null) return cached;
        }
        policy.validate(document);
        var compiled = new Compiled(policy.sanitizeHtml(document.html()),
            policy.renderCss(document.css(), rootId));
        synchronized (this) {
            var previous = entries.put(key, compiled);
            bytes += compiled.bytes() - (previous == null ? 0 : previous.bytes());
            while (entries.size() > MAX_ENTRIES || bytes > MAX_BYTES) {
                var iterator = entries.entrySet().iterator();
                bytes -= iterator.next().getValue().bytes();
                iterator.remove();
            }
        }
        return compiled;
    }

    synchronized void clear() { entries.clear(); bytes = 0; }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    record Compiled(String html, String css) {
        long bytes() { return 2L * (html.length() + css.length()); }
    }
}
