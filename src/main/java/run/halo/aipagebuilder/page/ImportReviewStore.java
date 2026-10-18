package run.halo.aipagebuilder.page;

import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * In-memory store for completed import reviews. Reviews expire and are bound to their actor.
 * Malicious verdicts are retained by archive hash until their TTL, without a separate capacity
 * cap, so an active deny record is never evicted early. In-flight reviews are tracked so an
 * import cannot race a pending review.
 */
@Component
public class ImportReviewStore {
    static final int MAX_ENTRIES = 128;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> malicious = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    synchronized void put(String actor, String archiveHash, PageApi.ImportReview review) {
        var now = Instant.now();
        entries.entrySet().removeIf(item -> item.getValue().review().expiresAt().isBefore(now));
        malicious.entrySet().removeIf(item -> item.getValue().isBefore(now));
        evictOldest(entries);
        var entry = new Entry(actor, archiveHash, review);
        entries.put(review.reviewId(), entry);
        if ("malicious".equals(review.verdict())) {
            malicious.put(archiveHash, review.expiresAt());
        }
    }

    void beginReview(String archiveHash) {
        inFlight.computeIfAbsent(archiveHash, key -> new AtomicInteger()).incrementAndGet();
    }

    void endReview(String archiveHash) {
        inFlight.computeIfPresent(archiveHash,
            (key, count) -> count.decrementAndGet() == 0 ? null : count);
    }

    boolean isReviewing(String archiveHash) {
        return inFlight.containsKey(archiveHash);
    }

    synchronized ImportBlock importBlock(String archiveHash) {
        var expiresAt = malicious.get(archiveHash);
        if (expiresAt != null && expiresAt.isBefore(Instant.now())) {
            malicious.remove(archiveHash, expiresAt);
            expiresAt = null;
        }
        if (expiresAt != null) {
            return ImportBlock.MALICIOUS;
        }
        return inFlight.containsKey(archiveHash) ? ImportBlock.REVIEWING : ImportBlock.NONE;
    }

    enum ImportBlock {
        NONE, MALICIOUS, REVIEWING
    }

    private static void evictOldest(ConcurrentHashMap<String, Entry> map) {
        if (map.size() >= MAX_ENTRIES) {
            map.entrySet().stream()
                .min(Comparator.comparing(item -> item.getValue().review().expiresAt()))
                .ifPresent(item -> map.remove(item.getKey(), item.getValue()));
        }
    }

    Entry get(String reviewId) {
        var entry = entries.get(reviewId);
        if (entry == null) {
            return null;
        }
        if (entry.review().expiresAt().isBefore(Instant.now())) {
            entries.remove(reviewId, entry);
            return null;
        }
        return entry;
    }

    Instant getMalicious(String archiveHash) {
        var expiresAt = malicious.get(archiveHash);
        if (expiresAt == null) {
            return null;
        }
        if (expiresAt.isBefore(Instant.now())) {
            malicious.remove(archiveHash, expiresAt);
            return null;
        }
        return expiresAt;
    }

    int size() {
        return entries.size();
    }

    record Entry(String actor, String archiveHash, PageApi.ImportReview review) {
    }
}
