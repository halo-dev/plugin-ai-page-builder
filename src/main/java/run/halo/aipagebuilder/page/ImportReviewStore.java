package run.halo.aipagebuilder.page;

import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** In-memory store for completed import reviews. Reviews expire and are bound to their actor. */
@Component
public class ImportReviewStore {
    static final int MAX_ENTRIES = 128;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    void put(String actor, String archiveHash, PageApi.ImportReview review) {
        var now = Instant.now();
        entries.entrySet().removeIf(item -> item.getValue().review().expiresAt().isBefore(now));
        if (entries.size() >= MAX_ENTRIES) {
            entries.entrySet().stream()
                .min(Comparator.comparing(item -> item.getValue().review().expiresAt()))
                .ifPresent(item -> entries.remove(item.getKey(), item.getValue()));
        }
        entries.put(review.reviewId(), new Entry(actor, archiveHash, review));
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

    record Entry(String actor, String archiveHash, PageApi.ImportReview review) {
    }
}
