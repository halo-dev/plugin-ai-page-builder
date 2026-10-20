package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import run.halo.aipagebuilder.Json;
import run.halo.aipagebuilder.model.PageChatCheckpoint;
import run.halo.aipagebuilder.model.PageChatSession;
import run.halo.aipagebuilder.model.PageChatTurnRecord;

class ConversationTurnsTest {
    private final ConversationStorage storage = new ConversationStorage();
    private final PageCheckpoints checkpoints = new PageCheckpoints(storage.client);
    private final ConversationTurns conversations = new ConversationTurns(storage.client, checkpoints);

    @BeforeEach
    void setup() {
        storage.install();
    }

    @Test
    void archivedTurnSurvivesTouchFailureAndRetryReleasesReservationOnlyAfterTouch() {
        var coordinator = reserve("session", turn("run"));
        var fail = new AtomicBoolean(true);
        failSessionUpdate("session", fail);

        assertThatThrownBy(() -> conversations.finish(coordinator, turn("run")).block())
            .hasMessage("storage unavailable");
        assertThat(conversations.history("session", null, 20).block().turns())
            .containsExactly(turn("run"));
        assertThat(current().getSpec().getActive().runId()).isEqualTo("run");

        fail.set(false);
        conversations.finish(current(), turn("run")).block();
        assertThat(current().getSpec().getActive()).isNull();
        assertThat(current().getSpec().getActiveSessionId()).isNull();
        verify(storage.client, times(1)).create(any(PageChatTurnRecord.class));
    }

    @Test
    void archivedTurnSurvivesReleaseFailureAndRetryDoesNotArchiveTwice() {
        var coordinator = reserve("session", turn("run"));
        var fail = new AtomicBoolean(true);
        failSessionUpdate("page", fail);

        assertThatThrownBy(() -> conversations.finish(coordinator, turn("run")).block())
            .hasMessage("storage unavailable");
        assertThat(conversations.find("page", "run").block().turn()).isEqualTo(turn("run"));
        assertThat(current().getSpec().getActive().runId()).isEqualTo("run");

        fail.set(false);
        conversations.finish(current(), turn("run")).block();
        assertThat(current().getSpec().getActive()).isNull();
        verify(storage.client, times(1)).create(any(PageChatTurnRecord.class));
    }

    @Test
    void defaultSessionDoesNotAdvanceItsOwnVersionBeforeRelease() {
        var coordinator = reserve("page", turn("run"));
        var version = coordinator.getMetadata().getVersion();

        var finished = conversations.finish(coordinator, turn("run")).block();

        assertThat(finished.getMetadata().getVersion()).isEqualTo(version + 1);
        assertThat(finished.getSpec().getActive()).isNull();
        assertThat(conversations.history("page", null, 20).block().turns())
            .containsExactly(turn("run"));
    }

    @Test
    void staleFinishCannotReleaseANewerReservation() {
        var coordinator = reserve("session", turn("old"));
        var touch = Sinks.<PageChatSession>one();
        doReturn(touch.asMono()).when(storage.client).update(any(PageChatSession.class));
        var finish = conversations.finish(coordinator, turn("old")).toFuture();
        assertThat(finish).isNotDone();
        var newer = current();
        newer.getSpec().setActive(turn("new"));
        newer.getMetadata().setVersion(newer.getMetadata().getVersion() + 1);
        storage.stored.put("page", newer);
        // Resume the delayed touch with ordinary CAS behavior for the release that follows.
        storage.install();
        touch.tryEmitValue(storage.stored.get("session"));

        assertThatThrownBy(finish::join).hasCauseInstanceOf(OptimisticLockingFailureException.class);
        assertThat(current().getSpec().getActive().runId()).isEqualTo("new");
        assertThat(conversations.find("page", "old").block()).isNotNull();
    }

    @Test
    void mismatchedRunDoesNotArchiveOrRelease() {
        var coordinator = reserve("page", turn("current"));

        assertThatThrownBy(() -> conversations.finish(coordinator, turn("old")).block())
            .hasMessageContaining("会话运行已变化");

        assertThat(conversations.history("page", null, 20).block().turns()).isEmpty();
        assertThat(current().getSpec().getActive().runId()).isEqualTo("current");
    }

    @Test
    void migratesAllLegacyTurnsIdempotentlyAndPagesWithoutDroppingHistory() {
        conversations.initialize("page").block();
        var legacy = storage.stored.get("page");
        legacy.getSpec().setPageName(null);
        for (int i = 0; i < 45; i++) {
            legacy.getSpec().getTurns().add(new ChatApi.Turn("r" + i, "admin",
                Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i), "prompt", "text",
                List.of(), "completed", null, null));
        }
        conversations.initialize("page").block();
        var latest = conversations.history("page", null, 20).block();
        assertThat(latest.turns()).hasSize(20);
        assertThat(latest.turns().getFirst().runId()).isEqualTo("r25");
        assertThat(latest.hasMore()).isTrue();
        var older = conversations.history("page", latest.nextBefore(), 20).block();
        assertThat(older.turns()).hasSize(20);
        assertThat(older.turns().getLast().runId()).isEqualTo("r24");
        var oldest = conversations.history("page", older.nextBefore(), 20).block();
        assertThat(oldest.turns()).hasSize(5);
        assertThat(oldest.hasMore()).isFalse();
        assertThat(current().getSpec().getTurns()).isEmpty();
        storage.stored.get("page").getSpec().getTurns().add(latest.turns().getFirst());

        conversations.initialize("page").block();

        assertThat(conversations.history("page", null, 100).block().turns()).hasSize(45);
        assertThat(conversations.find("page", "r0").block().turn().status()).isEqualTo("completed");
    }

    @Test
    void migrationFailureRetainsLegacyTurnsForRetry() {
        conversations.initialize("page").block();
        storage.stored.get("page").getSpec().getTurns().add(turn("old"));
        doReturn(Mono.error(new IllegalStateException("storage"))).when(storage.client)
            .create(any(PageChatTurnRecord.class));

        assertThatThrownBy(() -> conversations.initialize("page").block()).hasMessage("storage");
        assertThat(current().getSpec().getTurns()).containsExactly(turn("old"));

        storage.install();
        conversations.initialize("page").block();
        assertThat(current().getSpec().getTurns()).isEmpty();
        assertThat(conversations.history("page", null, 20).block().turns())
            .containsExactly(turn("old"));
    }

    @Test
    void failedChildDeletionKeepsReservationAndInitializationCompletesIt() {
        var coordinator = reserve("session", turn("run"));
        conversations.finish(coordinator, turn("run")).block();
        saveCheckpoint("session");
        doReturn(Mono.error(new IllegalStateException("delete unavailable"))).when(storage.client)
            .delete(any(PageChatTurnRecord.class));
        var session = conversations.selected("page", "session", current()).block();

        assertThatThrownBy(() -> conversations.deleteSession(current(), "session",
            session.getMetadata().getVersion()).block()).hasMessage("delete unavailable");
        assertThat(current().getSpec().getDeletingSessionId()).isEqualTo("session");
        assertThat(checkpoints.load("session").block()).isNotNull();

        storage.install();
        conversations.initialize("page").block();

        assertThat(current().getSpec().getDeletingSessionId()).isNull();
        assertThat(conversations.history("session", null, 20).block().turns()).isEmpty();
        assertThat(checkpoints.load("session").block()).isNull();
        assertThat(conversations.sessions("page").collectList().block()).hasSize(1);
    }

    @Test
    void defaultSessionDeletionPreservesOtherSessionsAndCanBeFollowedByANewTurn() {
        conversations.finish(reserve("page", turn("default")), turn("default")).block();
        conversations.finish(reserve("other", turn("other")), turn("other")).block();

        conversations.deleteSession(current(), "page", current().getMetadata().getVersion()).block();

        assertThat(conversations.history("page", null, 20).block().turns()).isEmpty();
        assertThat(conversations.history("other", null, 20).block().turns()).containsExactly(turn("other"));
        assertThat(current().getSpec().getTitle()).isEqualTo("默认会话");
        assertThat(current().getSpec().getDeletingSessionId()).isNull();
        conversations.finish(reserve("page", turn("new")), turn("new")).block();
        assertThat(conversations.history("page", null, 20).block().turns()).containsExactly(turn("new"));
    }

    @Test
    void pageCleanupRemovesAllThreeRecordTypesAndPreservesOtherPages() {
        conversations.finish(reserve("session", turn("run")), turn("run")).block();
        saveCheckpoint("session");
        conversations.initialize("other-page").block();

        ConversationTurns.deletePageHistory(storage.client, "page").block();
        ConversationTurns.deletePageHistory(storage.client, "page").block();

        assertThat(conversations.history("session", null, 20).block().turns()).isEmpty();
        assertThat(checkpoints.load("session").block()).isNull();
        assertThat(conversations.sessions("page").collectList().block()).isEmpty();
        assertThat(conversations.sessions("other-page").collectList().block()).hasSize(1);
    }

    private PageChatSession reserve(String sessionId, ChatApi.Turn turn) {
        var coordinator = conversations.initialize("page").block();
        if (!"page".equals(sessionId) && !storage.stored.containsKey(sessionId)) {
            conversations.create("page", sessionId, "Conversation").block();
        }
        coordinator.getSpec().setActive(turn);
        coordinator.getSpec().setActiveSessionId(sessionId);
        return storage.client.update(coordinator).block();
    }

    private PageChatSession current() {
        return storage.client.fetch(PageChatSession.class, "page").block();
    }

    private void failSessionUpdate(String id, AtomicBoolean fail) {
        doAnswer(invocation -> Mono.fromSupplier(() -> {
            PageChatSession session = invocation.getArgument(0);
            if (id.equals(session.getMetadata().getName()) && fail.get()) {
                throw new IllegalStateException("storage unavailable");
            }
            var previous = storage.stored.get(session.getMetadata().getName());
            if (!previous.getMetadata().getVersion().equals(session.getMetadata().getVersion())) {
                throw new OptimisticLockingFailureException("CAS");
            }
            session.getMetadata().setVersion(session.getMetadata().getVersion() + 1);
            storage.stored.put(session.getMetadata().getName(), Json.deepCopy(session));
            return session;
        })).when(storage.client).update(any(PageChatSession.class));
    }

    private void saveCheckpoint(String sessionId) {
        var snapshot = new PageChatCheckpoint.Spec();
        snapshot.setPageName("page");
        snapshot.setSessionId(sessionId);
        snapshot.setRequest(new ChatApi.Request("run", 1L, "model", "prompt"));
        checkpoints.save(snapshot).block();
    }

    private static ChatApi.Turn turn(String runId) {
        return new ChatApi.Turn(runId, "admin", Instant.parse("2026-10-20T00:00:00Z"),
            "prompt", "text", List.of(), "completed", null, null);
    }
}
