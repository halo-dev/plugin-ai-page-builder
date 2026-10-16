package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static run.halo.app.extension.index.query.QueryFactory.equal;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.dao.DuplicateKeyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.publisher.TestPublisher;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;
import run.halo.app.infra.utils.JsonUtils;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;
import run.halo.aipagebuilder.model.ThemePage;
import run.halo.aipagebuilder.model.ThemePageRevision;
import run.halo.aipagebuilder.page.PageApi.CreatePageRequest;
import run.halo.aipagebuilder.page.PageApi.RestoreRequest;
import run.halo.aipagebuilder.page.PageApi.SavePageRequest;

class PageServiceTest {
    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final ContentPolicy policy = mock(ContentPolicy.class);
    private final Map<String, ThemePage> pages = new HashMap<>();
    private final Map<String, ThemePageRevision> revisions = new HashMap<>();
    private final PageService service = new PageService(client, policy);
    private boolean failUpdate;

    @BeforeEach
    void setUp() {
        when(client.listAll(eq(ThemePage.class), any(ListOptions.class), any()))
            .thenAnswer(invocation -> Flux.defer(() -> {
                ListOptions options = invocation.getArgument(1);
                return Flux.fromIterable(pages.values()).filter(page -> options.equals(
                    new ListOptions().setFieldSelector(FieldSelector.of(equal("spec.slug", PagePaths.slug(page))))))
                    .map(JsonUtils::deepCopy);
            }));
        when(client.fetch(eq(run.halo.aipagebuilder.model.PageChatSession.class), anyString()))
            .thenReturn(Mono.empty());
        when(client.listAll(eq(run.halo.aipagebuilder.model.PageChatSession.class), any(ListOptions.class), any()))
            .thenReturn(Flux.empty());
        when(client.listAll(eq(run.halo.aipagebuilder.model.PageChatTurnRecord.class), any(ListOptions.class), any()))
            .thenReturn(Flux.empty());
        when(client.fetch(eq(ThemePage.class), anyString())).thenAnswer(invocation ->
            Mono.defer(() -> Mono.justOrEmpty(pages.get(invocation.<String>getArgument(1)))
                .map(JsonUtils::deepCopy)));
        when(client.fetch(eq(ThemePageRevision.class), anyString())).thenAnswer(invocation ->
            Mono.defer(() -> Mono.justOrEmpty(revisions.get(invocation.<String>getArgument(1)))
                .map(JsonUtils::deepCopy)));
        when(client.create(any(ThemePage.class))).thenAnswer(invocation -> Mono.fromSupplier(() -> {
            ThemePage page = invocation.getArgument(0);
            if (pages.containsKey(page.getMetadata().getName())) {
                throw new OptimisticLockingFailureException("Already exists");
            }
            page.getMetadata().setVersion(1L);
            pages.put(page.getMetadata().getName(), JsonUtils.deepCopy(page));
            return page;
        }));
        when(client.create(any(ThemePageRevision.class))).thenAnswer(invocation ->
            Mono.fromSupplier(() -> {
                ThemePageRevision revision = invocation.getArgument(0);
                revision.getMetadata().setVersion(1L);
                revision.getMetadata().setCreationTimestamp(Instant.now());
                revisions.put(revision.getMetadata().getName(), JsonUtils.deepCopy(revision));
                return revision;
            }));
        when(client.update(any(ThemePage.class))).thenAnswer(invocation -> Mono.fromSupplier(() -> {
            ThemePage page = invocation.getArgument(0);
            var stored = pages.get(page.getMetadata().getName());
            if (failUpdate || !stored.getMetadata().getVersion().equals(page.getMetadata().getVersion())) {
                throw new OptimisticLockingFailureException("Changed");
            }
            page.getMetadata().setVersion(page.getMetadata().getVersion() + 1);
            pages.put(page.getMetadata().getName(), JsonUtils.deepCopy(page));
            return page;
        }));
        when(client.delete(any(ThemePage.class))).thenAnswer(invocation -> Mono.fromSupplier(() -> {
            ThemePage page = invocation.getArgument(0);
            pages.remove(page.getMetadata().getName());
            return page;
        }));
        when(client.delete(any(ThemePageRevision.class))).thenAnswer(invocation ->
            Mono.fromSupplier(() -> {
                ThemePageRevision revision = invocation.getArgument(0);
                revisions.remove(revision.getMetadata().getName());
                return revision;
            }));
        when(client.listAll(eq(ThemePageRevision.class), any(ListOptions.class), any()))
            .thenAnswer(invocation -> Flux.defer(() -> Flux.fromIterable(
                revisions.values().stream().map(JsonUtils::deepCopy).toList())));
    }

    @Test
    void draftChangesDoNotChangePublishedContentAndRestoreCreatesANewDraft() {
        var created = service.create(new CreatePageRequest("about", "Original"), "admin").block();
        var first = created.page().draftRevision();
        assertNotFound(() -> service.published("about").block());
        var published = service.publish(created.page().name(), created.page().version()).block();
        var edited = service.save(created.page().name(), new SavePageRequest(published.page().version(),
            document("Changed"), "manual"), "admin").block();

        assertThat(service.published("about").block().document().title()).isEqualTo("Original");
        assertThat(edited.document().title()).isEqualTo("Changed");
        assertThat(edited.page().hasUnpublishedChanges()).isTrue();
        var restored = service.restore(created.page().name(), new RestoreRequest(edited.page().version(), first),
            "admin").block();
        assertThat(restored.page().draftRevision()).isNotEqualTo(first);
        assertThat(restored.page().publishedRevision()).isEqualTo(first);
        assertThat(restored.document().title()).isEqualTo("Original");
        assertThat(revisions).hasSize(3);
        assertThat(revisions.get(restored.page().draftRevision()).getSpec().getSource())
            .isEqualTo("restore");
        assertThat(revisions.get(first).getSpec().getDocument().title()).isEqualTo("Original");
    }

    @Test
    void aiRunCanBeReconciledAfterLaterManualSavesButOrphansDoNotCount() {
        var created = service.create(new CreatePageRequest("about", "Original"), "admin").block();
        var saved = service.saveAi(created.page().name(), created.page().version(), document("AI"), "admin", "run-1").block();
        service.save(created.page().name(), new SavePageRequest(saved.page().version(), document("Manual"), "manual"),
            "admin").block();
        assertThat(service.committedAiRevision(created.page().name(), "run-1").block()).isEqualTo(saved.page().draftRevision());
        var orphan = JsonUtils.deepCopy(revisions.get(saved.page().draftRevision()));
        orphan.getMetadata().setName("orphan");
        orphan.getSpec().setRunId("uncommitted-run");
        revisions.put("orphan", orphan);
        assertThat(service.committedAiRevision(created.page().name(), "uncommitted-run").block()).isNull();
    }

    @Test
    void clonesSavedSourceAsIndependentUnpublishedPageIncludingScript() {
        var original = service.create(new CreatePageRequest("original", "Original"), "admin").block();
        var document = new PageDocument(2, "code", null, null, "<button>Next</button>", "button{color:red}",
            "Original", "Description", "document.querySelector('button').textContent='Ready'");
        var saved = service.save(original.page().name(), new SavePageRequest(original.page().version(), document, "manual"), "admin").block();
        var published = service.publish(original.page().name(), saved.page().version()).block();
        var copy = service.clonePage(original.page().name(), new PageApi.ClonePageRequest(published.page().version(), "copy", "Copy"), "admin").block();
        assertThat(copy.document().html()).isEqualTo(document.html());
        assertThat(copy.document().css()).isEqualTo(document.css());
        assertThat(copy.document().js()).isEqualTo(document.js());
        assertThat(copy.document().title()).isEqualTo("Copy");
        assertThat(copy.page().publishedRevision()).isNull();
        assertThat(copy.page().draftRevision()).isNotEqualTo(published.page().draftRevision());
        assertThat(revisions.get(copy.page().draftRevision()).getSpec().getParentRevision()).isNull();
        assertConflict(() -> service.clonePage(original.page().name(), new PageApi.ClonePageRequest(0L, "stale", "Stale"), "admin").block());
        assertThat(pages).doesNotContainKey("stale");
    }

    @Test
    void staleVersionIsRejectedBeforeCreatingRevision() {
        var created = service.create(new CreatePageRequest("about", "Original"), "admin").block();
        assertConflict(() -> service.save(created.page().name(), new SavePageRequest(0L, document("New"),
            "manual"), "admin").block());
        assertThat(revisions).hasSize(1);
        assertThat(service.get(created.page().name()).block().document().title()).isEqualTo("Original");
    }

    @Test
    void failedPointerUpdateKeepsOldDraftAndRemovesUnusedRevision() {
        var created = service.create(new CreatePageRequest("about", "Original"), "admin").block();
        failUpdate = true;
        assertConflict(() -> service.save(created.page().name(), new SavePageRequest(created.page().version(),
            document("New"), "manual"), "admin").block());
        assertThat(revisions).hasSize(1);
        assertThat(service.get(created.page().name()).block().page().draftRevision())
            .isEqualTo(created.page().draftRevision());
    }

    @Test
    void identicalSaveDoesNotCreateRevisionOrIncrementVersion() {
        var created = service.create(new CreatePageRequest("about", "Original"), "admin").block();
        var saved = service.save(created.page().name(), new SavePageRequest(created.page().version(),
            created.document(), "manual"), "admin").block();
        assertThat(saved).isEqualTo(created);
        assertThat(revisions).hasSize(1);
    }

    @Test
    void cannotReadOrRestoreAnotherPagesRevision() {
        var about = service.create(new CreatePageRequest("about", "About"), "admin").block();
        var other = service.create(new CreatePageRequest("other", "Other"), "admin").block();
        assertNotFound(() -> service.revisionDetail(about.page().name(), other.page().draftRevision()).block());
        assertNotFound(() -> service.restore(about.page().name(), new RestoreRequest(about.page().version(),
            other.page().draftRevision()), "admin").block());
        assertThat(revisions).hasSize(2);
    }

    @Test
    void unpublishingAndDeletingRemovePublicAccess() {
        var created = service.create(new CreatePageRequest("about", "About"), "admin").block();
        var published = service.publish(created.page().name(), created.page().version()).block();
        var unpublished = service.unpublish(created.page().name(), published.page().version()).block();
        assertNotFound(() -> service.published("about").block());
        service.delete(created.page().name(), unpublished.page().version()).block();
        assertNotFound(() -> service.get(created.page().name()).block());
        assertThat(revisions).isEmpty();
    }

    @Test
    void publishRevalidatesPersistedDocumentBeforeChangingPointer() {
        var created = service.create(new CreatePageRequest("about", "About"), "admin").block();
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rejected"))
            .when(policy).validate(any());
        assertThatThrownBy(() -> service.publish(created.page().name(), created.page().version()).block())
            .isInstanceOf(ResponseStatusException.class);
        assertThat(pages.get(created.page().name()).getSpec().getPublishedRevision()).isNull();
    }

    @Test
    void duplicateSlugAndInvalidSlugCannotCreatePages() {
        service.create(new CreatePageRequest("about", "About"), "admin").block();
        assertConflict(() -> service.create(new CreatePageRequest("about", "Duplicate"), "admin")
            .block());
        assertThatThrownBy(() -> service.create(new CreatePageRequest("Bad/Slug", "Bad"), "admin")
            .block()).isInstanceOf(ResponseStatusException.class);
        assertThat(pages).hasSize(1);
        assertThat(revisions).hasSize(1);
    }

    @Test
    void hierarchicalSlugUsesIndependentStableNameAndRevisionOwner() {
        var created = service.create(new CreatePageRequest("company/about-us", "About"), "admin").block();
        assertThat(UUID.fromString(created.page().name()).toString()).isEqualTo(created.page().name());
        assertThat(created.page().slug()).isEqualTo("company/about-us");
        assertThat(created.page().name()).isNotEqualTo(created.page().slug());
        assertThat(revisions.get(created.page().draftRevision()).getSpec().getPageName())
            .isEqualTo(created.page().name());
        assertNotFound(() -> service.get(created.page().slug()).block());
        assertThat(service.findPublished(created.page().slug()).block()).isNull();
        var published = service.publish(created.page().name(), created.page().version()).block();
        var found = service.published(created.page().slug()).block();
        assertThat(found.page().name()).isEqualTo(created.page().name());
        assertThat(found.document()).isEqualTo(published.document());
    }

    @Test
    void changingPublishedSlugKeepsNameAndRevisionsAndMovesPublicLookup() {
        var created = service.create(new CreatePageRequest("about", "About"), "admin").block();
        var published = service.publish(created.page().name(), created.page().version()).block();
        var renamed = service.updateSlug(created.page().name(),
            new PageApi.UpdatePageSlugRequest(published.page().version(), "company/about-us")).block();
        assertThat(renamed.page().name()).isEqualTo(created.page().name());
        assertThat(renamed.page().draftRevision()).isEqualTo(published.page().draftRevision());
        assertThat(renamed.page().publishedRevision()).isEqualTo(published.page().publishedRevision());
        assertThat(renamed.page().version()).isEqualTo(published.page().version() + 1);
        assertThat(renamed.document()).isEqualTo(published.document());
        assertThat(revisions).hasSize(1);
        assertThat(service.findPublished("about").block()).isNull();
        assertNotFound(() -> service.published("about").block());
        assertThat(service.published("company/about-us").block().page().name())
            .isEqualTo(created.page().name());
        assertConflict(() -> service.updateSlug(created.page().name(),
            new PageApi.UpdatePageSlugRequest(published.page().version(), "stale")).block());
    }

    @Test
    void slugUpdateRejectsConflictsAndStorageCasFailuresWithoutCreatingRevisions() {
        var created = service.create(new CreatePageRequest("about", "About"), "admin").block();
        service.create(new CreatePageRequest("occupied", "Occupied"), "admin").block();
        assertConflict(() -> service.updateSlug(created.page().name(),
            new PageApi.UpdatePageSlugRequest(created.page().version(), "occupied")).block());
        failUpdate = true;
        assertConflict(() -> service.updateSlug(created.page().name(),
            new PageApi.UpdatePageSlugRequest(created.page().version(), "new-path")).block());
        assertThat(service.get(created.page().name()).block().page().slug()).isEqualTo("about");
        assertThat(revisions).hasSize(2);
    }

    @Test
    void layoutUpdateTogglesThemeLayoutAndKeepsContentAndRevisions() {
        var created = service.create(new CreatePageRequest("about", "About"), "admin").block();
        assertThat(created.page().themeLayout()).isTrue();
        var updated = service.updateLayout(created.page().name(),
            new PageApi.UpdatePageLayoutRequest(created.page().version(), false)).block();
        assertThat(updated.page().themeLayout()).isFalse();
        assertThat(updated.page().name()).isEqualTo(created.page().name());
        assertThat(updated.page().draftRevision()).isEqualTo(created.page().draftRevision());
        assertThat(updated.page().version()).isEqualTo(created.page().version() + 1);
        assertThat(updated.document()).isEqualTo(created.document());
        assertThat(revisions).hasSize(1);
        assertConflict(() -> service.updateLayout(created.page().name(),
            new PageApi.UpdatePageLayoutRequest(created.page().version(), true)).block());
    }

    @Test
    void createAcceptsThemeLayoutAndCloneInheritsIt() {
        var standalone = service.create(new CreatePageRequest("landing", "Landing", false), "admin")
            .block();
        assertThat(standalone.page().themeLayout()).isFalse();
        var themed = service.create(new CreatePageRequest("about", "About"), "admin").block();
        assertThat(themed.page().themeLayout()).isTrue();
        var clone = service.clonePage(standalone.page().name(), new PageApi.ClonePageRequest(
            standalone.page().version(), "landing-copy", "Landing Copy"), "admin").block();
        assertThat(clone.page().themeLayout()).isFalse();
    }

    @Test
    void deletingPagesKeepTheirSlugUntilStorageRemoval() {
        var created = service.create(new CreatePageRequest("about", "About"), "admin").block();
        pages.get(created.page().name()).getMetadata().setDeletionTimestamp(Instant.now());
        assertConflict(() -> service.create(new CreatePageRequest("about", "Duplicate"), "admin").block());
        assertThat(service.findPublished("about").block()).isNull();
        pages.remove(created.page().name());
        assertThat(service.create(new CreatePageRequest("about", "Replacement"), "admin").block())
            .isNotNull();
    }

    @Test
    void corePathsRejectCreateClonePublishAndSlugChangeEvenForPersistedData() {
        var created = service.create(new CreatePageRequest("about", "About"), "admin").block();
        var name = created.page().name();
        for (var slug : new String[] {"login", "console/settings", "captcha/altcha",
            "apis/security.halo.run/v1alpha1/users", "password-reset/email"}) {
            assertBadRequest(() -> service.create(new CreatePageRequest(slug, "Core"), "admin").block());
            assertBadRequest(() -> service.clonePage(name,
                new PageApi.ClonePageRequest(created.page().version(), slug, "Core"), "admin").block());
            assertBadRequest(() -> service.updateSlug(name,
                new PageApi.UpdatePageSlugRequest(created.page().version(), slug)).block());
            pages.get(name).getSpec().setSlug(slug);
            assertBadRequest(() -> service.publish(name, created.page().version()).block());
            assertThat(pages.get(name).getSpec().getPublishedRevision()).isNull();
            pages.get(name).getSpec().setSlug("about");
        }
        assertThat(revisions).hasSize(1);
        assertThat(pages).hasSize(1);
    }

    @Test
    void businessPathsCanBeCreatedPublishedAndChangedBack() {
        var created = service.create(new CreatePageRequest("categories", "Categories"), "admin").block();
        var published = service.publish(created.page().name(), created.page().version()).block();
        assertThat(service.published("categories").block().page().name()).isEqualTo(created.page().name());
        var renamed = service.updateSlug(created.page().name(),
            new PageApi.UpdatePageSlugRequest(published.page().version(), "about/company")).block();
        assertThat(service.findPublished("categories").block()).isNull();
        assertThat(service.published("about/company").block().page().name()).isEqualTo(renamed.page().name());
    }

    private static void assertBadRequest(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void uniqueIndexConflictOnCreateRemovesNewRevision() {
        when(client.create(any(ThemePage.class))).thenReturn(Mono.error(new DuplicateKeyException("slug")));
        assertConflict(() -> service.create(new CreatePageRequest("about", "About"), "admin").block());
        assertThat(revisions).isEmpty();
        assertThat(pages).isEmpty();
    }

    @Test
    void uniqueIndexConflictOnSlugUpdateReturnsConflict() {
        var created = service.create(new CreatePageRequest("about", "About"), "admin").block();
        when(client.update(any(ThemePage.class))).thenReturn(Mono.error(new DuplicateKeyException("slug")));
        assertConflict(() -> service.updateSlug(created.page().name(),
            new PageApi.UpdatePageSlugRequest(created.page().version(), "new-path")).block());
        assertThat(service.get(created.page().name()).block().page().slug()).isEqualTo("about");
        assertThat(revisions).hasSize(1);
    }

    @Test
    void sluglessDraftReportsNullSlugAndPublishesOnlyAfterSlugIsSet() {
        var created = service.create(new CreatePageRequest(null, "Untitled"), "admin").block();
        assertThat(created.page().slug()).isNull();
        assertThat(service.get(created.page().name()).block().page().slug()).isNull();
        assertBadRequest(() -> service.publish(created.page().name(), created.page().version())
            .block());
        assertThat(pages.get(created.page().name()).getSpec().getPublishedRevision()).isNull();
        var renamed = service.updateSlug(created.page().name(),
            new PageApi.UpdatePageSlugRequest(created.page().version(), "about")).block();
        var published = service.publish(renamed.page().name(), renamed.page().version()).block();
        assertThat(published.page().publishedRevision()).isNotNull();
        assertThat(service.published("about").block().page().name()).isEqualTo(created.page().name());
    }

    @Test
    void blankSlugIsNormalizedToNullOnCreate() {
        var created = service.create(new CreatePageRequest("  ", "Untitled"), "admin").block();
        assertThat(created.page().slug()).isNull();
        assertThat(pages.get(created.page().name()).getSpec().getSlug()).isNull();
    }

    @Test
    void cloneStillRequiresAnExplicitSlug() {
        var created = service.create(new CreatePageRequest(null, "Untitled"), "admin").block();
        assertBadRequest(() -> service.clonePage(created.page().name(),
            new PageApi.ClonePageRequest(created.page().version(), null, "Copy"), "admin").block());
        assertThat(pages).hasSize(1);
        assertThat(revisions).hasSize(1);
    }

    @Test
    void concurrentCreatesCannotBothPassTheAvailabilityCheck() {
        var commit = Sinks.<Void>empty();
        when(client.create(any(ThemePage.class))).thenAnswer(invocation -> {
            ThemePage page = invocation.getArgument(0);
            return commit.asMono().then(Mono.fromSupplier(() -> {
                page.getMetadata().setVersion(1L);
                pages.put(page.getMetadata().getName(), JsonUtils.deepCopy(page));
                return page;
            }));
        });
        var first = service.create(new CreatePageRequest("same", "First"), "admin").toFuture();
        assertThat(first).isNotDone();
        assertConflict(() -> service.create(new CreatePageRequest("same", "Second"), "admin").block());
        assertThat(revisions).hasSize(1);
        commit.tryEmitEmpty();
        assertThat(first.join().page().slug()).isEqualTo("same");
        assertThat(pages).hasSize(1);
        assertConflict(() -> service.create(new CreatePageRequest("same", "Third"), "admin").block());
    }

    @Test
    void concurrentRenamesAndCreateShareTheSameSlugGuard() {
        var first = service.create(new CreatePageRequest("first", "First"), "admin").block();
        var second = service.create(new CreatePageRequest("second", "Second"), "admin").block();
        var commit = Sinks.<Void>empty();
        when(client.update(any(ThemePage.class))).thenAnswer(invocation -> {
            ThemePage page = invocation.getArgument(0);
            return commit.asMono().then(Mono.fromSupplier(() -> {
                page.getMetadata().setVersion(page.getMetadata().getVersion() + 1);
                pages.put(page.getMetadata().getName(), JsonUtils.deepCopy(page));
                return page;
            }));
        });
        var renamed = service.updateSlug(first.page().name(),
            new PageApi.UpdatePageSlugRequest(first.page().version(), "same")).toFuture();
        assertThat(renamed).isNotDone();
        assertConflict(() -> service.updateSlug(second.page().name(),
            new PageApi.UpdatePageSlugRequest(second.page().version(), "same")).block());
        assertConflict(() -> service.create(new CreatePageRequest("same", "Third"), "admin").block());
        commit.tryEmitEmpty();
        assertThat(renamed.join().page().slug()).isEqualTo("same");
        assertThat(pages.values().stream().filter(page -> "same".equals(PagePaths.slug(page))))
            .hasSize(1);
        assertThat(revisions).hasSize(2);
    }

    @Test
    void cancelledRequestHoldsSlugUntilTheStorageTransactionCompletes() {
        var stored = TestPublisher.<ThemePage>create();
        var pending = new AtomicReference<ThemePage>();
        when(client.create(any(ThemePage.class))).thenAnswer(invocation -> {
            pending.set(invocation.getArgument(0));
            return Mono.fromDirect(stored.flux());
        });
        var request = service.create(new CreatePageRequest("same", "First"), "admin").subscribe();
        request.dispose();
        stored.assertNotCancelled();
        assertConflict(() -> service.create(new CreatePageRequest("same", "Second"), "admin").block());
        // A transactional publisher may emit the saved value before committing the transaction.
        stored.next(pending.get());
        assertConflict(() -> service.create(new CreatePageRequest("same", "Third"), "admin").block());
        stored.complete();
        // No row is persisted by this test publisher, so success proves the guard was released.
        when(client.create(any(ThemePage.class))).thenAnswer(invocation ->
            Mono.just(invocation.getArgument(0)));
        assertThat(service.create(new CreatePageRequest("same", "After completion"), "admin").block())
            .isNotNull();
    }

    @Test
    void failedWriteReleasesSlugForRetry() {
        when(client.create(any(ThemePage.class)))
            .thenReturn(Mono.error(new DuplicateKeyException("slug")))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        assertConflict(() -> service.create(new CreatePageRequest("same", "First"), "admin").block());
        assertThat(service.create(new CreatePageRequest("same", "Retry"), "admin").block())
            .isNotNull();
        assertThat(revisions).hasSize(1);
    }

    private static PageDocument document(String title) {
        return new PageDocument(1, "code", "", Map.of(), "<h1>" + title + "</h1>", "", title, "");
    }

    private static void assertConflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    private static void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
