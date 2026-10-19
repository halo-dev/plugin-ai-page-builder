package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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

    @Test
    void maliciousReviewSurvivesSafeReviewForTheSameArchive() {
        var expiresAt = Instant.now().plusSeconds(600);
        store.put("admin", "hash", review("malicious", "malicious", expiresAt));
        store.put("other", "hash", review("safe", "safe", expiresAt));

        assertThat(store.getMalicious("hash")).isEqualTo(expiresAt);
        assertThat(store.get("safe")).isNotNull();
    }

    @Test
    void expiredMaliciousReviewIsRemoved() {
        store.put("admin", "hash",
            review("malicious", "malicious", Instant.now().minusSeconds(1)));

        assertThat(store.getMalicious("hash")).isNull();
    }

    @Test
    void maliciousReviewsAreKeptUntilExpiry() {
        var expiresAt = Instant.now().plusSeconds(600);
        for (var index = 0; index <= ImportReviewStore.MAX_ENTRIES; index++) {
            store.put("admin", "hash-" + index,
                review("malicious-" + index, "malicious", expiresAt));
        }

        assertThat(store.getMalicious("hash-0")).isEqualTo(expiresAt);
        assertThat(store.getMalicious("hash-" + ImportReviewStore.MAX_ENTRIES))
            .isEqualTo(expiresAt);
        assertThat(store.importBlock("hash-0"))
            .isEqualTo(ImportReviewStore.ImportBlock.MALICIOUS);
    }

    @Test
    void inFlightReviewsAreTrackedUntilTheyFinish() {
        store.beginReview("hash");
        store.beginReview("hash");
        store.endReview("hash");

        assertThat(store.isReviewing("hash")).isTrue();
        assertThat(store.importBlock("hash"))
            .isEqualTo(ImportReviewStore.ImportBlock.REVIEWING);

        store.endReview("hash");

        assertThat(store.isReviewing("hash")).isFalse();
        assertThat(store.importBlock("hash"))
            .isEqualTo(ImportReviewStore.ImportBlock.NONE);
    }

    @Test
    void concurrentPutsKeepTheStoreAtCapacity() throws Exception {
        for (var index = 0; index < ImportReviewStore.MAX_ENTRIES; index++) {
            store.put("admin", "hash-" + index,
                review("review-" + index, Instant.now().plusSeconds(index + 1)));
        }
        var pool = Executors.newFixedThreadPool(32);
        var start = new CountDownLatch(1);
        var tasks = new ArrayList<java.util.concurrent.Future<?>>();
        for (var index = 0; index < 32; index++) {
            var extra = index;
            tasks.add(pool.submit(() -> {
                start.await();
                store.put("admin", "extra-" + extra,
                    review("extra-" + extra, Instant.now().plusSeconds(1_000 + extra)));
                return null;
            }));
        }
        start.countDown();
        for (var task : tasks) {
            task.get();
        }
        pool.shutdown();

        assertThat(store.size()).isLessThanOrEqualTo(ImportReviewStore.MAX_ENTRIES);
    }

    private static PageApi.ImportReview review(String reviewId, String verdict,
        Instant expiresAt) {
        return new PageApi.ImportReview(reviewId, verdict, "summary", List.of(), "Test", "",
            false, expiresAt);
    }

    private static PageApi.ImportReview review(String reviewId, Instant expiresAt) {
        return review(reviewId, "safe", expiresAt);
    }
}
