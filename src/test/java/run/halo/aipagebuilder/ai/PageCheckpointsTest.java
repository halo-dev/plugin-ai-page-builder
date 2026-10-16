package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.aipagebuilder.model.PageChatCheckpoint;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

class PageCheckpointsTest {
    @Test
    void newSnapshotWaitsForDeletedResourceToBeCollectedBeforeReusingItsName() {
        var client = mock(ReactiveExtensionClient.class);
        var tombstone = new PageChatCheckpoint();
        var metadata = new Metadata();
        metadata.setDeletionTimestamp(Instant.now());
        tombstone.setMetadata(metadata);
        var existing = new AtomicReference<>(tombstone);
        when(client.fetch(eq(PageChatCheckpoint.class), anyString())).thenAnswer(ignored ->
            Mono.defer(() -> Mono.justOrEmpty(existing.get())));
        when(client.create(any(PageChatCheckpoint.class))).thenAnswer(i -> Mono.just(i.getArgument(0)));
        var spec = new PageChatCheckpoint.Spec();
        spec.setSessionId("session");
        var checkpoints = new PageCheckpoints(client);
        assertThat(checkpoints.load("session").block()).isNull();
        StepVerifier.withVirtualTime(() -> checkpoints.save(spec))
            .expectSubscription().expectNoEvent(Duration.ofMillis(100))
            .then(() -> {
                verify(client, never()).create(any(PageChatCheckpoint.class));
                existing.set(null);
            }).thenAwait(Duration.ofMillis(100)).verifyComplete();
        verify(client).create(any(PageChatCheckpoint.class));
        verify(client, never()).update(any(PageChatCheckpoint.class));
    }
}
