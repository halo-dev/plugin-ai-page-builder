package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImportReviewStoreTest {
    private final ImportReviewStore store = new ImportReviewStore();

    @Test
    void expiredEntriesAreNotReturned() {
        var review = review("expired", Instant.now().minusSeconds(1));
        store.put("admin", "hash", review);

        assertThat(store.get("expired")).isNull();
    }

    @Test
    void oldestEntryIsEvictedAtCapacity() {
        for (var index = 0; index < ImportReviewStore.MAX_ENTRIES; index++) {
            store.put("admin", "hash-" + index,
                review("review-" + index, Instant.now().plusSeconds(index)));
        }
        store.put("admin", "hash-extra", review("review-extra", Instant.now().plusSeconds(10_000)));

        assertThat(store.get("review-0")).isNull();
        assertThat(store.get("review-1")).isNotNull();
        assertThat(store.get("review-" + (ImportReviewStore.MAX_ENTRIES - 1))).isNotNull();
        assertThat(store.get("review-extra")).isNotNull();
    }

    private static PageApi.ImportReview review(String reviewId, Instant expiresAt) {
        return new PageApi.ImportReview(reviewId, "safe", "summary", List.of(), "Test", "",
            false, expiresAt);
    }
}
