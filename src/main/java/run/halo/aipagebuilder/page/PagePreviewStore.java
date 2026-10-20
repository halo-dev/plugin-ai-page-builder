package run.halo.aipagebuilder.page;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Short-lived capabilities. Raw bearer tokens are returned once and never retained. */
@Component
public class PagePreviewStore {
    public static final String PATH = "/preview/ai-page-builder/";
    static final Duration TTL = Duration.ofMinutes(15);
    static final int CAPACITY = 128;
    private final Map<String, Entry> entries = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public PagePreviewStore() {
        this(Clock.systemUTC());
    }

    PagePreviewStore(Clock clock) {
        this.clock = clock;
    }

    public synchronized Ticket issue(PageApi.PageSummary page, String actor) {
        entries.values().removeIf(entry -> !clock.instant().isBefore(entry.expiresAt())
            || entry.name().equals(page.name()) && entry.actor().equals(actor));
        if (entries.size() >= CAPACITY) {
            entries.entrySet().stream().min(Comparator.comparing(item -> item.getValue()
                .expiresAt()))
                .ifPresent(item -> entries.remove(item.getKey()));
        }
        var bytes = new byte[32];
        random.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var expiry = clock.instant().plus(TTL);
        entries.put(hash(token), new Entry(page.name(), actor, page.version(),
            page.draftRevision(), expiry));
        return new Ticket(token, expiry);
    }

    synchronized Entry get(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        var key = hash(token);
        var entry = entries.get(key);
        if (entry != null && !clock.instant().isBefore(entry.expiresAt())) {
            entries.remove(key);
            return null;
        }
        return entry;
    }

    @jakarta.annotation.PreDestroy
    public synchronized void clear() {
        entries.clear();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public record Ticket(String token, Instant expiresAt) {
        @Override
        public String toString() { return "PreviewTicket[redacted]"; }
    }

    record Entry(String name, String actor, Long version, String revision, Instant expiresAt) { }
}
