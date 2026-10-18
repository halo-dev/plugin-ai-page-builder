package run.halo.aipagebuilder.page;

import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory store for completed import reviews. Reviews expire and are bound to their actor.
 * Malicious reviews are retained by archive hash so a re-review cannot downgrade them.
 */
@Component
public class ImportReviewStore {
    static final int MAX_ENTRIES = 128;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Entry> malicious = new ConcurrentHashMap<>();

    synchronized void put(String actor, String archiveHash, PageApi.ImportReview review) {
        var now = Instant.now();
        entries.entrySet().removeIf(item -> item.getValue().review().expiresAt().isBefore(now));
        evictOldest(entries);
        var entry = new Entry(actor, archiveHash, review);
        entries.put(review.reviewId(), entry);
        if ("malicious".equals(review.verdict())) {
            malicious.entrySet().removeIf(
                item -> item.getValue().review().expiresAt().isBefore(now));
            if (!malicious.containsKey(archiveHash)) {
                evictOldest(malicious);
            }
            malicious.put(archiveHash, entry);
        }
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

    Entry getMalicious(String archiveHash) {
        var entry = malicious.get(archiveHash);
        if (entry == null) {
            return null;
        }
        if (entry.review().expiresAt().isBefore(Instant.now())) {
            malicious.remove(archiveHash, entry);
            return null;
        }
        return entry;
    }

    int size() {
        return entries.size();
    }

    record Entry(String actor, String archiveHash, PageApi.ImportReview review) {
    }
}
