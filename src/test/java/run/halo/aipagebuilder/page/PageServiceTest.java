package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static run.halo.app.extension.index.query.Queries.equal;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.dao.DuplicateKeyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.publisher.TestPublisher;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.PageRequest;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;
import run.halo.aipagebuilder.Json;
import run.halo.aipagebuilder.ai.AiEndpoint;
import run.halo.aipagebuilder.ai.AiGateway;
import run.halo.aipagebuilder.ai.AiReview;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;
import run.halo.aipagebuilder.model.ThemePage;
import run.halo.aipagebuilder.model.ThemePageRevision;
import run.halo.aipagebuilder.model.ThemeRef;
import run.halo.aipagebuilder.page.PageApi.CreatePageRequest;
import run.halo.aipagebuilder.page.PageApi.RestoreRequest;
import run.halo.aipagebuilder.page.PageApi.SavePageRequest;
import run.halo.aipagebuilder.theme.ThemeContextService;

class PageServiceTest {
    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final ContentPolicy policy = mock(ContentPolicy.class);
    private final Map<String, ThemePage> pages = new HashMap<>();
    private final Map<String, ThemePageRevision> revisions = new HashMap<>();
    private final AiGateway gateway = mock(AiGateway.class);
    private final ImportReviewStore reviewStore = new ImportReviewStore();
    private final PageImportReviewService importReviews =
        new PageImportReviewService(gateway, new ContentPolicy(), reviewStore);
    private final ThemeContextService themes = mock(ThemeContextService.class);
    private final PageService service = new PageService(client, policy, importReviews, themes);
    private final PageService importer =
        new PageService(client, new ContentPolicy(), importReviews, themes);
    private boolean failUpdate;

    @BeforeEach
    void setUp() {
        when(themes.activeThemeIdentity()).thenReturn(Mono.empty());
        when(client.listAll(eq(ThemePage.class), any(ListOptions.class), any()))
            .thenAnswer(invocation -> Flux.defer(() -> {
                ListOptions options = invocation.getArgument(1);
                return Flux.fromIterable(pages.values()).filter(page -> options.equals(
                    new ListOptions().setFieldSelector(FieldSelector.of(equal("spec.slug", PagePaths.slug(page))))))
                    .map(Json::deepCopy);
            }));
        when(client.fetch(eq(run.halo.aipagebuilder.model.PageChatSession.class), anyString()))
            .thenReturn(Mono.empty());
        when(client.listAll(eq(run.halo.aipagebuilder.model.PageChatSession.class), any(ListOptions.class), any()))
            .thenReturn(Flux.empty());
        when(client.listAll(eq(run.halo.aipagebuilder.model.PageChatCheckpoint.class), any(ListOptions.class), any()))
            .thenReturn(Flux.empty());
        when(client.listAll(eq(run.halo.aipagebuilder.model.PageChatTurnRecord.class), any(ListOptions.class), any()))
            .thenReturn(Flux.empty());
        when(client.listBy(eq(ThemePageRevision.class), any(), any())).thenAnswer(invocation -> {
            ListOptions options = invocation.getArgument(1);
            var matching = revisions.values().stream().filter(revision ->
                run.halo.aipagebuilder.QueryTestSupport.matches(options, field -> switch (field) {
                    case "spec.pageName" -> revision.getSpec().getPageName();
                    default -> null;
                })).map(Json::deepCopy).toList();
            return Mono.just(new run.halo.app.extension.ListResult<>(1, 1, matching.size(),
                matching.stream().limit(1).toList()));
        });
        when(client.fetch(eq(ThemePage.class), anyString())).thenAnswer(invocation ->
            Mono.defer(() -> Mono.justOrEmpty(pages.get(invocation.<String>getArgument(1)))
                .map(Json::deepCopy)));
        when(client.fetch(eq(ThemePageRevision.class), anyString())).thenAnswer(invocation ->
            Mono.defer(() -> Mono.justOrEmpty(revisions.get(invocation.<String>getArgument(1)))
                .map(Json::deepCopy)));
        when(client.create(any(ThemePage.class))).thenAnswer(invocation -> Mono.fromSupplier(() -> {
            ThemePage page = invocation.getArgument(0);
            if (pages.containsKey(page.getMetadata().getName())) {
                throw new OptimisticLockingFailureException("Already exists");
            }
            page.getMetadata().setVersion(1L);
            pages.put(page.getMetadata().getName(), Json.deepCopy(page));
            return page;
        }));
        when(client.create(any(ThemePageRevision.class))).thenAnswer(invocation ->
            Mono.fromSupplier(() -> {
                ThemePageRevision revision = invocation.getArgument(0);
                revision.getMetadata().setVersion(1L);
                revision.getMetadata().setCreationTimestamp(Instant.now());
                revisions.put(revision.getMetadata().getName(), Json.deepCopy(revision));
                return revision;
            }));
        when(client.update(any(ThemePage.class))).thenAnswer(invocation -> Mono.fromSupplier(() -> {
            ThemePage page = invocation.getArgument(0);
            var stored = pages.get(page.getMetadata().getName());
            if (failUpdate || !stored.getMetadata().getVersion().equals(page.getMetadata().getVersion())) {
                throw new OptimisticLockingFailureException("Changed");
            }
            page.getMetadata().setVersion(page.getMetadata().getVersion() + 1);
            pages.put(page.getMetadata().getName(), Json.deepCopy(page));
            return page;
        }));
        when(client.delete(any(ThemePage.class))).thenAnswer(invocation -> Mono.fromSupplier(() -> {
            ThemePage page = invocation.getArgument(0);
            page.getMetadata().setDeletionTimestamp(Instant.now());
            page.getMetadata().setVersion(page.getMetadata().getVersion() + 1);
            pages.put(page.getMetadata().getName(), Json.deepCopy(page));
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
                revisions.values().stream().filter(revision ->
                    run.halo.aipagebuilder.QueryTestSupport.matches(invocation.getArgument(1), field ->
                        "spec.pageName".equals(field) ? revision.getSpec().getPageName() : null))
                    .map(Json::deepCopy).toList())));
    }

    @Test
    void runReservationExcludesDeletionUntilTheReservationFinishes() {
        var created = service.create(new CreatePageRequest("about", "Initial"), "admin").block();
        var name = created.page().name();
        var reservation = Sinks.<Void>empty();
        var execution = service.reserveRun(name, created.page().version(), reservation::asMono).subscribe();
        assertThatThrownBy(() -> service.delete(name, created.page().version()).block())
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(pages.get(name).getMetadata().getDeletionTimestamp()).isNull();
        reservation.tryEmitEmpty();
        service.delete(name, created.page().version()).block();
        assertThat(pages.get(name).getMetadata().getDeletionTimestamp()).isNotNull();
        var createdChild = new java.util.concurrent.atomic.AtomicBoolean();
        assertThatThrownBy(() -> service.withLivePage(name,
            () -> Mono.fromRunnable(() -> createdChild.set(true))).block())
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(createdChild).isFalse();
        execution.dispose();
    }

    @Test
    void reconcilerAddsFinalizerToExistingPages() {
        var created = service.create(new CreatePageRequest("legacy", "Legacy"), "admin").block();
        var stored = pages.get(created.page().name());
        stored.getMetadata().setFinalizers(null);
        new PageReconciler(client).reconcilePage(created.page().name()).block();
        assertThat(pages.get(created.page().name()).getMetadata().getFinalizers())
            .contains(PageReconciler.FINALIZER);
    }

    @Test
    void uncertainCreateKeepsReferencedRevisionAndReturnsTheCommittedPage() {
        when(client.create(any(ThemePage.class))).thenAnswer(i -> Mono.defer(() -> {
            ThemePage value = i.getArgument(0);
            value.getMetadata().setVersion(1L);
            pages.put(value.getMetadata().getName(), Json.deepCopy(value));
            return Mono.error(new java.util.concurrent.TimeoutException("response lost"));
        }));
        var created = service.create(new CreatePageRequest("about", "Created"), "admin").block();
        assertThat(revisions).containsKey(created.page().draftRevision());
        assertThat(service.get(created.page().name()).block().page().title()).isEqualTo("Created");
    }

    @Test
    void deletionFailureKeepsFinalizerAndReconcilerRetriesWithoutDeletingOtherPages() {
        var page = service.create(new CreatePageRequest("delete", "Delete"), "admin").block();
        var other = service.create(new CreatePageRequest("keep", "Keep"), "admin").block();
        when(client.delete(any(ThemePageRevision.class))).thenReturn(Mono.error(new IllegalStateException("failed")));
        assertThatThrownBy(() -> service.delete(page.page().name(), page.page().version()).block())
            .isInstanceOf(IllegalStateException.class);
        var deleting = pages.get(page.page().name());
        assertThat(deleting.getMetadata().getDeletionTimestamp()).isNotNull();
        assertThat(deleting.getMetadata().getFinalizers()).contains(PageReconciler.FINALIZER);
        when(client.delete(any(ThemePageRevision.class))).thenAnswer(i -> Mono.fromSupplier(() -> {
            ThemePageRevision value = i.getArgument(0);
            revisions.remove(value.getMetadata().getName());
            return value;
        }));
        new PageReconciler(client).reconcilePage(page.page().name()).block();
        assertThat(pages.get(page.page().name()).getMetadata().getFinalizers()).isEmpty();
        assertThat(revisions).containsOnlyKeys(other.page().draftRevision());
    }

    @Test
    void propertiesApplyToPublishedPageWithoutCreatingContentRevisionAndSurviveRestore() {
        var created = service.create(new CreatePageRequest("about", "Public title"), "admin").block();
        var published = service.publish(created.page().name(), created.page().version()).block();
        var changed = service.updateProperties(created.page().name(),
            new PageApi.UpdatePagePropertiesRequest(published.page().version(), "New title",
                "New description", "company", false, null, null)).block();
        var live = service.published("company").block();
        assertThat(live.page().title()).isEqualTo("New title");
        assertThat(live.page().description()).isEqualTo("New description");
        assertThat(live.page().themeLayout()).isFalse();
        assertThat(live.document()).isEqualTo(published.document());
        assertThat(changed.page().draftRevision()).isEqualTo(published.page().draftRevision());
        assertThat(changed.page().publishedRevision()).isEqualTo(published.page().publishedRevision());
        assertThat(changed.page().hasUnpublishedChanges()).isFalse();
        assertThat(revisions).hasSize(1);
        assertThat(service.findPublished("about").block()).isNull();
        var edited = service.save(created.page().name(), new SavePageRequest(changed.page().version(),
            document("Draft secret"), "manual"), "admin").block();
        assertThat(service.published("company").block().document()).isEqualTo(published.document());
        var restored = service.restore(created.page().name(),
            new RestoreRequest(edited.page().version(), created.page().draftRevision()), "admin").block();
        assertThat(restored.document()).isEqualTo(created.document());
        assertThat(restored.page().title()).isEqualTo("New title");
        assertThat(restored.page().description()).isEqualTo("New description");
        assertThat(restored.page().slug()).isEqualTo("company");
        assertThat(restored.page().themeLayout()).isFalse();
        assertThat(Json.toJson(revisions.get(restored.page().draftRevision()).getSpec().getDocument()))
            .doesNotContain("title", "description", "slug", "themeLayout");
    }

    @Test
    void newRunIdDoesNotReadEveryHistoricalRevision() {
        var created = service.create(new CreatePageRequest("about", "Initial"), "admin").block();
        org.mockito.Mockito.clearInvocations(client);
        assertThat(service.committedAiRun(created.page().name(), "new-run").block()).isNull();
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never()).fetch(eq(ThemePageRevision.class), anyString());
    }

    @Test
    void importsExportAsNewUnpublishedPageWithNoOriginalPathOrHistory() {
        var document = new PageDocument(2, "code", null, null, "<p>共享</p>", "p {color:red}", "console.log('shared');");
        var archive = PageExport.zip(PageExportTest.detail(document, false));
        var review = reviewImport(archive, AiReview.Verdict.SAFE);
        var pending = importer.importPage(review.reviewId(), archive, "recipient", false);
        assertThat(pages).isEmpty();
        assertThat(revisions).isEmpty();
        var first = pending.block();
        var second = importer.importPage(review.reviewId(), archive, "recipient", false).block();
        assertThat(first.document()).isEqualTo(document);
        assertThat(first.page().themeLayout()).isFalse();
        assertThat(first.page().slug()).isNull();
        assertThat(first.page().publishedRevision()).isNull();
        assertThat(first.page().name()).isNotEqualTo("original-id").isNotEqualTo(second.page().name());
        assertThat(pages).hasSize(2);
        assertThat(revisions).hasSize(2);
        var revision = revisions.get(first.page().draftRevision());
        assertThat(revision.getSpec().getParentRevision()).isNull();
        assertThat(revision.getSpec().getCreatedBy()).isEqualTo("recipient");
    }

    @Test
    void importsAccessRestrictionButNotMembers() {
        var document = new PageDocument(2, "code", null, null, "<p>受限</p>", "", "");
        var members = List.of(new ThemePage.PageMember(ThemePage.MemberType.USER, "alice"));
        var archive = PageExport.zip(PageExportTest.detail(document, false, true, members));
        var review = reviewImport(archive, AiReview.Verdict.SAFE);
        var imported = importer.importPage(review.reviewId(), archive, "recipient", false).block();
        assertThat(imported.page().authorizeRequired()).isTrue();
        assertThat(imported.page().members()).isNull();
    }

    @Test
    void invalidImportedContentDoesNotCreatePageOrRevision() throws Exception {
        var files = PageArchiveTest.files();
        files.put("page.html", "<script>alert(1)</script>");
        var bytes = PageArchiveTest.zip(files);
        storeReview("review", "safe", bytes, Instant.now().plusSeconds(600));
        assertThatThrownBy(() -> importer.importPage("review", bytes, "recipient", false).block())
            .isInstanceOf(ResponseStatusException.class);
        assertThat(pages).isEmpty();
        assertThat(revisions).isEmpty();
    }

    @Test
    void uncertainImportedPageCreationRetainsRevisionForReconciliation() throws Exception {
        when(client.create(any(ThemePage.class))).thenReturn(Mono.error(new IllegalStateException("failed")));
        var bytes = PageArchiveTest.zip(PageArchiveTest.files());
        var review = reviewImport(bytes, AiReview.Verdict.SAFE);
        assertThatThrownBy(() -> importer.importPage(review.reviewId(), bytes, "recipient", false)
            .block())
            .isInstanceOf(IllegalStateException.class);
        assertThat(pages).isEmpty();
        assertThat(revisions).hasSize(1);
    }

    @Test
    void rejectedImportedPageCreationCleansUpInitialRevision() throws Exception {
        when(client.create(any(ThemePage.class))).thenReturn(Mono.error(new OptimisticLockingFailureException("rejected")));
        var bytes = PageArchiveTest.zip(PageArchiveTest.files());
        var review = reviewImport(bytes, AiReview.Verdict.SAFE);
        assertThatThrownBy(() -> importer.importPage(review.reviewId(), bytes, "recipient", false)
            .block())
            .isInstanceOf(ResponseStatusException.class);
        assertThat(pages).isEmpty();
        assertThat(revisions).isEmpty();
    }

    @Test
    void importRejectsMissingActorMismatchAndHashMismatchWithoutWrites() throws Exception {
        var bytes = PageArchiveTest.zip(PageArchiveTest.files());
        var review = reviewImport(bytes, AiReview.Verdict.SAFE);
        assertImportRejected(null, bytes, "recipient", false, HttpStatus.BAD_REQUEST);
        assertImportRejected("", bytes, "recipient", false, HttpStatus.BAD_REQUEST);
        assertImportRejected("  ", bytes, "recipient", false, HttpStatus.BAD_REQUEST);
        assertImportRejected("missing", bytes, "recipient", false, HttpStatus.BAD_REQUEST);
        assertImportRejected(review.reviewId(), bytes, "other", false, HttpStatus.BAD_REQUEST);
        var changed = PageArchiveTest.files();
        changed.put("page.html", "<p>Changed</p>");
        assertImportRejected(review.reviewId(), PageArchiveTest.zip(changed), "recipient", false,
            HttpStatus.BAD_REQUEST);
    }

    @Test
    void importRejectsExpiredAndUnknownReviewsWithoutWrites() throws Exception {
        var bytes = PageArchiveTest.zip(PageArchiveTest.files());
        storeReview("expired", "safe", bytes, Instant.now().minusSeconds(1));
        storeReview("unknown", "unexpected", bytes, Instant.now().plusSeconds(600));
        assertImportRejected("expired", bytes, "recipient", false, HttpStatus.BAD_REQUEST);
        assertImportRejected("unknown", bytes, "recipient", false, HttpStatus.BAD_REQUEST);
    }

    @Test
    void suspiciousImportRequiresConfirmationBeforeCreatingDraft() throws Exception {
        var bytes = PageArchiveTest.zip(PageArchiveTest.files());
        var review = reviewImport(bytes, AiReview.Verdict.SUSPICIOUS);
        assertImportRejected(review.reviewId(), bytes, "recipient", false,
            HttpStatus.UNPROCESSABLE_CONTENT);
        var imported = importer.importPage(review.reviewId(), bytes, "recipient", true).block();
        assertThat(imported.page().publishedRevision()).isNull();
        assertThat(pages).containsOnlyKeys(imported.page().name());
        assertThat(revisions).containsOnlyKeys(imported.page().draftRevision());
    }

    @Test
    void maliciousImportRejectsConfirmationAndEarlierOrLaterSafeReviews() throws Exception {
        var bytes = PageArchiveTest.zip(PageArchiveTest.files());
        var safe = reviewImport(bytes, AiReview.Verdict.SAFE);
        var malicious = reviewImport(bytes, AiReview.Verdict.MALICIOUS);
        assertImportRejected(malicious.reviewId(), bytes, "recipient", true,
            HttpStatus.UNPROCESSABLE_CONTENT);
        assertImportRejected(safe.reviewId(), bytes, "recipient", false,
            HttpStatus.UNPROCESSABLE_CONTENT);
        storeReview("later-safe", "safe", bytes, Instant.now().plusSeconds(600));
        assertImportRejected("later-safe", bytes, "recipient", true,
            HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void importRejectsPendingReviewAndAllowsRetryAfterCancellation() throws Exception {
        var bytes = PageArchiveTest.zip(PageArchiveTest.files());
        var safe = reviewImport(bytes, AiReview.Verdict.SAFE);
        var started = new CountDownLatch(1);
        when(gateway.review(anyString(), anyString())).thenAnswer(invocation -> {
            started.countDown();
            return Mono.<AiReview>never();
        });
        var pending = importReviews.review(bytes, "recipient").subscribe();
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertImportRejected(safe.reviewId(), bytes, "recipient", false, HttpStatus.CONFLICT);
        } finally {
            pending.dispose();
        }
        var imported = importer.importPage(safe.reviewId(), bytes, "recipient", false).block();
        assertThat(pages).containsOnlyKeys(imported.page().name());
        assertThat(revisions).containsOnlyKeys(imported.page().draftRevision());
    }

    private PageApi.ImportReview reviewImport(byte[] bytes, AiReview.Verdict verdict) {
        when(gateway.status()).thenReturn(Mono.just(new AiEndpoint.AiStatus(true, "ok")));
        when(gateway.review(anyString(), anyString()))
            .thenReturn(Mono.just(new AiReview(verdict, "summary", List.of())));
        return importReviews.review(bytes, "recipient").block();
    }

    private void storeReview(String id, String verdict, byte[] bytes, Instant expiresAt)
        throws Exception {
        var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        reviewStore.put("recipient", hash, new PageApi.ImportReview(id, verdict, "summary", List.of(),
            "Test", "", false, expiresAt));
    }

    private void assertImportRejected(String id, byte[] bytes, String actor, boolean confirmed,
        HttpStatus status) {
        assertThatThrownBy(() -> importer.importPage(id, bytes, actor, confirmed).block())
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(status));
        assertThat(pages).isEmpty();
        assertThat(revisions).isEmpty();
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never()).create(any());
    }

    @Test
    void aiSavesTitleContentAndSemanticPathInOnePageUpdate() {
        var created = service.create(new CreatePageRequest(null, "未命名页面"), "admin").block();
        var saved = service.saveAi(created.page().name(), created.page().version(), document("About"),
            "admin", "run-path", created.page().draftRevision(), "company/about", "About").block();
        assertThat(saved.page().slug()).isEqualTo("company/about");
        assertThat(saved.page().title()).isEqualTo("About");
        assertThat(saved.page().version()).isEqualTo(created.page().version() + 1);
        assertThat(saved.page().publishedRevision()).isNull();
        assertThat(service.get(created.page().name()).block()).isEqualTo(saved);
        assertThat(service.committedAiRun(created.page().name(), "run-path").block())
            .isEqualTo(new ThemePage.AiCommit("run-path", saved.page().draftRevision()));
    }

    @Test
    void aiSavePersistsDescriptionWithoutANewContentRevision() {
        var created = service.create(new CreatePageRequest("about", "About"), "admin").block();
        var saved = service.saveAi(created.page().name(), created.page().version(),
            created.document(), "admin", "run-description", created.page().draftRevision(),
            null, null, " 了解我们的公司与团队。 ").block();
        assertThat(saved.page().description()).isEqualTo(" 了解我们的公司与团队。 ");
        assertThat(saved.page().draftRevision()).isEqualTo(created.page().draftRevision());
        assertThat(saved.page().title()).isEqualTo("About");
        assertThat(saved.page().slug()).isEqualTo("about");
        assertThat(revisions).hasSize(1);
        assertThat(service.committedAiRun(created.page().name(), "run-description").block())
            .isEqualTo(new ThemePage.AiCommit("run-description", null));
    }

    @Test
    void pathOnlyAiSaveIsPersistedAndCanBeReconciled() {
        var created = service.create(new CreatePageRequest(null, "About"), "admin").block();
        var saved = service.saveAi(created.page().name(), created.page().version(), created.document(),
            "admin", "run-path", created.page().draftRevision(), "about", null).block();
        assertThat(saved.page().slug()).isEqualTo("about");
        assertThat(saved.document()).isEqualTo(created.document());
        assertThat(saved.page().draftRevision()).isEqualTo(created.page().draftRevision());
        assertThat(revisions).hasSize(1);
        assertThat(service.committedAiRun(created.page().name(), "run-path").block())
            .isEqualTo(new ThemePage.AiCommit("run-path", null));
    }

    @Test
    void conflictingOrReservedAiPathDoesNotPartiallySaveTheDraft() {
        service.create(new CreatePageRequest("taken", "Existing"), "admin").block();
        var created = service.create(new CreatePageRequest(null, "Original"), "admin").block();
        for (var slug : java.util.List.of("taken", "console/settings", "Bad Path")) {
            assertThatThrownBy(() -> service.saveAi(created.page().name(), created.page().version(),
                document("Changed"), "admin", "run-path", created.page().draftRevision(), slug, null).block())
                .isInstanceOf(ResponseStatusException.class);
            assertThat(service.get(created.page().name()).block()).isEqualTo(created);
            assertThat(revisions).hasSize(2);
        }
    }

    @Test
    void failedAiPathCommitLeavesNeitherPathNorContentAndCanRetry() {
        var created = service.create(new CreatePageRequest(null, "Original"), "admin").block();
        failUpdate = true;
        assertThatThrownBy(() -> service.saveAi(created.page().name(), created.page().version(),
            document("Changed"), "admin", "run-path", created.page().draftRevision(), "about", null).block())
            .isInstanceOf(ResponseStatusException.class);
        assertThat(service.get(created.page().name()).block()).isEqualTo(created);
        assertThat(service.committedAiRun(created.page().name(), "run-path").block()).isNull();
        assertThat(revisions).hasSize(1);
        failUpdate = false;
        assertThat(service.saveAi(created.page().name(), created.page().version(), document("Changed"),
            "admin", "run-path", created.page().draftRevision(), "about", null).block().page().slug())
            .isEqualTo("about");
    }

    @Test
    void aiCannotReplaceAnExistingOrConcurrentlySetPath() {
        var created = service.create(new CreatePageRequest(null, "Original"), "admin").block();
        var manual = service.updateProperties(created.page().name(),
            new PageApi.UpdatePagePropertiesRequest(created.page().version(), null, null, "manual-path", null, null, null)).block();
        for (var version : java.util.List.of(created.page().version(), manual.page().version())) {
            assertThatThrownBy(() -> service.saveAi(created.page().name(), version, document("Changed"),
                "admin", "run-path", created.page().draftRevision(), "about", null).block())
                .isInstanceOf(ResponseStatusException.class);
            assertThat(service.get(created.page().name()).block()).isEqualTo(manual);
        }
        var edited = service.saveAi(manual.page().name(), manual.page().version(), document("Changed"),
            "admin", "run-content", manual.page().draftRevision(), null, null).block();
        assertThat(edited.page().slug()).isEqualTo("manual-path");
    }

    @Test
    void draftChangesDoNotChangePublishedContentAndRestoreCreatesANewDraft() {
        var created = service.create(new CreatePageRequest("about", "Original"), "admin").block();
        var first = created.page().draftRevision();
        assertNotFound(() -> service.published("about").block());
        var published = service.publish(created.page().name(), created.page().version()).block();
        var edited = service.save(created.page().name(), new SavePageRequest(published.page().version(),
            document("Changed"), "manual"), "admin").block();

        assertThat(service.published("about").block().page().title()).isEqualTo("Original");
        assertThat(edited.document().html()).isEqualTo("<h1>Changed</h1>");
        assertThat(edited.page().hasUnpublishedChanges()).isTrue();
        var restored = service.restore(created.page().name(), new RestoreRequest(edited.page().version(), first),
            "admin").block();
        assertThat(restored.page().draftRevision()).isNotEqualTo(first);
        assertThat(restored.page().publishedRevision()).isEqualTo(first);
        assertThat(restored.document()).isEqualTo(created.document());
        assertThat(revisions).hasSize(3);
        assertThat(revisions.get(restored.page().draftRevision()).getSpec().getSource())
            .isEqualTo("restore");
        assertThat(revisions.get(first).getSpec().getDocument()).isEqualTo(created.document());
    }

    @Test
    void aiRunCanBeReconciledAfterLaterManualSavesButOrphansDoNotCount() {
        var created = service.create(new CreatePageRequest("about", "Original"), "admin").block();
        var saved = service.saveAi(created.page().name(), created.page().version(), document("AI"), "admin", "run-1", null, null, null).block();
        service.save(created.page().name(), new SavePageRequest(saved.page().version(), document("Manual"), "manual"),
            "admin").block();
        assertThat(service.committedAiRun(created.page().name(), "run-1").block().revision()).isEqualTo(saved.page().draftRevision());
        var orphan = Json.deepCopy(revisions.get(saved.page().draftRevision()));
        orphan.getMetadata().setName("orphan");
        revisions.put("orphan", orphan);
        assertThat(service.committedAiRun(created.page().name(), "uncommitted-run").block()).isNull();
    }

    @Test
    void clonesSavedSourceAsIndependentUnpublishedPageIncludingScript() {
        var original = service.create(new CreatePageRequest("original", "Original"), "admin").block();
        var document = new PageDocument(2, "code", null, null, "<button>Next</button>", "button{color:red}", "document.querySelector('button').textContent='Ready'");
        var saved = service.save(original.page().name(), new SavePageRequest(original.page().version(), document, "manual"), "admin").block();
        var published = service.publish(original.page().name(), saved.page().version()).block();
        var copy = service.clonePage(original.page().name(), new PageApi.ClonePageRequest(published.page().version(), "copy", "Copy"), "admin").block();
        assertThat(copy.document().html()).isEqualTo(document.html());
        assertThat(copy.document().css()).isEqualTo(document.css());
        assertThat(copy.document().js()).isEqualTo(document.js());
        assertThat(copy.page().title()).isEqualTo("Copy");
        assertThat(copy.page().publishedRevision()).isNull();
        assertThat(copy.page().draftRevision()).isNotEqualTo(published.page().draftRevision());
        assertThat(revisions.get(copy.page().draftRevision()).getSpec().getParentRevision()).isNull();
        assertConflict(() -> service.clonePage(original.page().name(), new PageApi.ClonePageRequest(0L, "stale", "Stale"), "admin").block());
        assertThat(pages).doesNotContainKey("stale");
    }

    @Test
    void cloningARestrictedPageKeepsItsAccessSettings() {
        var original = service.create(new CreatePageRequest("original", "Original"), "admin").block();
        var members = List.of(
            new ThemePage.PageMember(ThemePage.MemberType.USER, "alice"),
            new ThemePage.PageMember(ThemePage.MemberType.ROLE, "editors"));
        var restricted = service.updateProperties(original.page().name(),
            new PageApi.UpdatePagePropertiesRequest(original.page().version(), null, null, null, null,
                true, members)).block();
        var copy = service.clonePage(original.page().name(),
            new PageApi.ClonePageRequest(restricted.page().version(), "copy", "Copy"), "admin").block();
        assertThat(copy.page().authorizeRequired()).isTrue();
        assertThat(copy.page().members()).isEqualTo(members);
        assertThat(copy.page().publishedRevision()).isNull();
    }

    @Test
    void cloningDropsNullMembersWithoutLeakingARevision() {
        var original = service.create(new CreatePageRequest("original", "Original"), "admin").block();
        var alice = new ThemePage.PageMember(ThemePage.MemberType.USER, "alice");
        var stored = pages.get(original.page().name());
        stored.getSpec().setAuthorizeRequired(true);
        stored.getSpec().setMembers(java.util.Arrays.asList(alice, null));
        var copy = service.clonePage(original.page().name(),
            new PageApi.ClonePageRequest(original.page().version(), "copy", "Copy"), "admin").block();
        assertThat(copy.page().members()).containsExactly(alice);
        assertThat(pages).hasSize(2);
        assertThat(revisions).hasSize(2);
    }

    @Test
    void staleVersionIsRejectedBeforeCreatingRevision() {
        var created = service.create(new CreatePageRequest("about", "Original"), "admin").block();
        assertConflict(() -> service.save(created.page().name(), new SavePageRequest(0L, document("New"),
            "manual"), "admin").block());
        assertThat(revisions).hasSize(1);
        assertThat(service.get(created.page().name()).block().page().title()).isEqualTo("Original");
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
    void invalidPropertiesAndSlugConflictsDoNotPartiallySave() {
        var created = service.create(new CreatePageRequest("about", "Original"), "admin").block();
        service.create(new CreatePageRequest("occupied", "Other"), "admin").block();
        assertConflict(() -> service.updateProperties(created.page().name(),
            new PageApi.UpdatePagePropertiesRequest(created.page().version(), "New", "Changed",
                "occupied", false, null, null)).block());
        assertBadRequest(() -> service.updateProperties(created.page().name(),
            new PageApi.UpdatePagePropertiesRequest(created.page().version(), " ", null,
                "new-path", false, null, null)).block());
        assertBadRequest(() -> service.updateProperties(created.page().name(),
            new PageApi.UpdatePagePropertiesRequest(created.page().version(), null, "x".repeat(1001),
                null, null, null, null)).block());
        assertThat(service.get(created.page().name()).block()).isEqualTo(created);
    }

    @Test
    void restrictedAccessPropertiesPersistAndAllowMemberLists() {
        var created = service.create(new CreatePageRequest("secret", "Secret"), "admin").block();
        var members = List.of(
            new ThemePage.PageMember(ThemePage.MemberType.USER, "alice"),
            new ThemePage.PageMember(ThemePage.MemberType.ROLE, "editors"));
        var restricted = service.updateProperties(created.page().name(),
            new PageApi.UpdatePagePropertiesRequest(created.page().version(), null, null, null, null,
                true, members)).block();
        assertThat(restricted.page().authorizeRequired()).isTrue();
        assertThat(restricted.page().members()).isEqualTo(members);

        var renamed = service.updateProperties(created.page().name(),
            new PageApi.UpdatePagePropertiesRequest(restricted.page().version(), "Renamed", null,
                null, null, null, null)).block();
        assertThat(renamed.page().authorizeRequired()).isTrue();
        assertThat(renamed.page().members()).isEqualTo(members);

        assertBadRequest(() -> service.updateProperties(created.page().name(),
            new PageApi.UpdatePagePropertiesRequest(renamed.page().version(), null, null, null, null,
                null, List.of(new ThemePage.PageMember(ThemePage.MemberType.USER, " ")))).block());
        assertThat(service.get(created.page().name()).block().page().members()).isEqualTo(members);

        var cleared = service.updateProperties(created.page().name(),
            new PageApi.UpdatePagePropertiesRequest(renamed.page().version(), null, null, null, null,
                false, List.of())).block();
        assertThat(cleared.page().authorizeRequired()).isFalse();
        assertThat(cleared.page().members()).isEmpty();
    }

    @Test
    void titleOnlyAiSaveHasNoContentRevisionAndIsReconciledAfterManualPropertiesChange() {
        var created = service.create(new CreatePageRequest("about", "Original"), "admin").block();
        var published = service.publish(created.page().name(), created.page().version()).block();
        var saved = service.saveAi(created.page().name(), published.page().version(), created.document(),
            "admin", "rename-run", created.page().draftRevision(), null, "Renamed").block();
        assertThat(service.published("about").block().page().title()).isEqualTo("Renamed");
        assertThat(saved.page().draftRevision()).isEqualTo(created.page().draftRevision());
        assertThat(saved.page().hasUnpublishedChanges()).isFalse();
        assertThat(revisions).hasSize(1);
        service.updateProperties(created.page().name(), new PageApi.UpdatePagePropertiesRequest(
            saved.page().version(), "Manual", null, null, null, null, null)).block();
        assertThat(service.committedAiRun(created.page().name(), "rename-run").block())
            .isEqualTo(new ThemePage.AiCommit("rename-run", null));
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
        var renamed = service.updateProperties(created.page().name(),
            new PageApi.UpdatePagePropertiesRequest(published.page().version(), null, null, "company/about-us", null, null, null)).block();
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
        assertConflict(() -> service.updateProperties(created.page().name(),
            new PageApi.UpdatePagePropertiesRequest(published.page().version(), null, null, "stale", null, null, null)).block());
    }

    @Test
    void slugUpdateRejectsConflictsAndStorageCasFailuresWithoutCreatingRevisions() {
        var created = service.create(new CreatePageRequest("about", "About"), "admin").block();
        service.create(new CreatePageRequest("occupied", "Occupied"), "admin").block();
        assertConflict(() -> service.updateProperties(created.page().name(),
            new PageApi.UpdatePagePropertiesRequest(created.page().version(), null, null, "occupied", null, null, null)).block());
        failUpdate = true;
        assertConflict(() -> service.updateProperties(created.page().name(),
            new PageApi.UpdatePagePropertiesRequest(created.page().version(), null, null, "new-path", null, null, null)).block());
        assertThat(service.get(created.page().name()).block().page().slug()).isEqualTo("about");
        assertThat(revisions).hasSize(2);
    }

    @Test
    void layoutUpdateTogglesThemeLayoutAndKeepsContentAndRevisions() {
        var created = service.create(new CreatePageRequest("about", "About"), "admin").block();
        assertThat(created.page().themeLayout()).isTrue();
        var updated = service.updateProperties(created.page().name(),
            new PageApi.UpdatePagePropertiesRequest(created.page().version(), null, null, null, false, null, null)).block();
        assertThat(updated.page().themeLayout()).isFalse();
        assertThat(updated.page().name()).isEqualTo(created.page().name());
        assertThat(updated.page().draftRevision()).isEqualTo(created.page().draftRevision());
        assertThat(updated.page().version()).isEqualTo(created.page().version() + 1);
        assertThat(updated.document()).isEqualTo(created.document());
        assertThat(revisions).hasSize(1);
        assertConflict(() -> service.updateProperties(created.page().name(),
            new PageApi.UpdatePagePropertiesRequest(created.page().version(), null, null, null, true, null, null)).block());
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
            assertBadRequest(() -> service.updateProperties(name,
                new PageApi.UpdatePagePropertiesRequest(created.page().version(), null, null, slug, null, null, null)).block());
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
        var renamed = service.updateProperties(created.page().name(),
            new PageApi.UpdatePagePropertiesRequest(published.page().version(), null, null, "about/company", null, null, null)).block();
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
        assertConflict(() -> service.updateProperties(created.page().name(),
            new PageApi.UpdatePagePropertiesRequest(created.page().version(), null, null, "new-path", null, null, null)).block());
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
        var renamed = service.updateProperties(created.page().name(),
            new PageApi.UpdatePagePropertiesRequest(created.page().version(), null, null, "about", null, null, null)).block();
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
    void cloneAcceptsBlankSlugLikeCreate() {
        var created = service.create(new CreatePageRequest(null, "Untitled"), "admin").block();
        var copy = service.clonePage(created.page().name(),
            new PageApi.ClonePageRequest(created.page().version(), "", "Copy"), "admin").block();
        assertThat(copy.page().slug()).isNull();
        assertThat(pages).hasSize(2);
        assertThat(revisions).hasSize(2);
    }

    @Test
    void concurrentCreatesCannotBothPassTheAvailabilityCheck() {
        var commit = Sinks.<Void>empty();
        when(client.create(any(ThemePage.class))).thenAnswer(invocation -> {
            ThemePage page = invocation.getArgument(0);
            return commit.asMono().then(Mono.fromSupplier(() -> {
                page.getMetadata().setVersion(1L);
                pages.put(page.getMetadata().getName(), Json.deepCopy(page));
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
                pages.put(page.getMetadata().getName(), Json.deepCopy(page));
                return page;
            }));
        });
        var renamed = service.updateProperties(first.page().name(),
            new PageApi.UpdatePagePropertiesRequest(first.page().version(), null, null, "same", null, null, null)).toFuture();
        assertThat(renamed).isNotDone();
        assertConflict(() -> service.updateProperties(second.page().name(),
            new PageApi.UpdatePagePropertiesRequest(second.page().version(), null, null, "same", null, null, null)).block());
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

    @Test
    void listFiltersByStatusAndSortsByCreationTime() {
        var options = new AtomicReference<ListOptions>();
        var request = new AtomicReference<PageRequest>();
        when(client.listBy(eq(ThemePage.class), any(ListOptions.class), any(PageRequest.class)))
            .thenAnswer(invocation -> {
                options.set(invocation.getArgument(1));
                request.set(invocation.getArgument(2));
                return Mono.just(ListResult.emptyResult());
            });

        service.list(2, 10, null, "published", "created").block();

        assertThat(options.get().getFieldSelector().query().toString()).isEqualTo(
            "(metadata.deletionTimestamp IS NULL AND spec.publishedRevision IS NOT NULL)");
        assertThat(request.get().getSort().toList()).containsExactly(
            Sort.Order.desc("metadata.creationTimestamp"), Sort.Order.asc("metadata.name"));
        assertThat(request.get().getPageNumber()).isEqualTo(2);
        assertThat(request.get().getPageSize()).isEqualTo(10);
    }

    @Test
    void listDefaultsToRecentlyUpdated() {
        var options = new AtomicReference<ListOptions>();
        var request = new AtomicReference<PageRequest>();
        when(client.listBy(eq(ThemePage.class), any(ListOptions.class), any(PageRequest.class)))
            .thenAnswer(invocation -> {
                options.set(invocation.getArgument(1));
                request.set(invocation.getArgument(2));
                return Mono.just(ListResult.emptyResult());
            });

        service.list(1, 20, null, "unpublished", null).block();

        assertThat(options.get().getFieldSelector().query().toString()).isEqualTo(
            "(metadata.deletionTimestamp IS NULL AND spec.publishedRevision IS NULL)");
        assertThat(request.get().getSort().toList()).containsExactly(
            Sort.Order.desc("spec.updatedAt"), Sort.Order.asc("metadata.name"));
    }

    @Test
    void listRejectsUnknownStatusAndSort() {
        assertBadRequest(() -> service.list(1, 20, null, "draft", null).block());
        assertBadRequest(() -> service.list(1, 20, null, null, "title").block());
    }

    @Test
    void publishingRequiresTheExactDraftAndVersion() {
        var created = service.create(new CreatePageRequest("mcp-page", "First"), "owner").block();
        var name = created.page().name();
        assertConflict(() -> service.publish(name, created.page().version(), "other").block());
        assertThat(service.get(name).block().page().publishedRevision()).isNull();
        var saved = service.save(name, new SavePageRequest(created.page().version(),
            document("Changed"), "ai"), "owner").block();
        assertConflict(() -> service.publish(name, saved.page().version(),
            created.page().draftRevision()).block());
        assertConflict(() -> service.publish(name, created.page().version(),
            saved.page().draftRevision()).block());
        var published = service.publish(name, saved.page().version(), saved.page().draftRevision())
            .block();
        assertThat(published.page().publishedRevision()).isEqualTo(saved.page().draftRevision());
    }

    @Test
    void revisionsSnapshotTheActiveTheme() {
        var earth = new ThemeRef("theme-earth", "Earth");
        when(themes.activeThemeIdentity()).thenReturn(Mono.just(earth));

        var created = service.create(new CreatePageRequest("themed", "Themed"), "owner").block();
        var name = created.page().name();
        var saved = service.save(name, new SavePageRequest(created.page().version(),
            document("Changed"), "manual"), "owner").block();

        assertThat(revisions.get(created.page().draftRevision()).getSpec().getTheme())
            .isEqualTo(earth);
        assertThat(revisions.get(saved.page().draftRevision()).getSpec().getTheme())
            .isEqualTo(earth);
    }

    @Test
    void missingActiveThemeStillAllowsSaving() {
        var created = service.create(new CreatePageRequest("untitled-theme", "Themed"), "owner")
            .block();
        var saved = service.save(created.page().name(), new SavePageRequest(
            created.page().version(), document("Changed"), "manual"), "owner").block();

        assertThat(saved).isNotNull();
        assertThat(revisions.get(saved.page().draftRevision()).getSpec().getTheme()).isNull();
    }

    @Test
    void publishingCopiesTheRevisionThemeAndRestoreKeepsTheSourceTheme() {
        var earth = new ThemeRef("theme-earth", "Earth");
        var nebula = new ThemeRef("theme-nebula", "Nebula");
        when(themes.activeThemeIdentity()).thenReturn(Mono.just(earth));
        var created = service.create(new CreatePageRequest("theme-switch", "Themed"), "owner")
            .block();
        var name = created.page().name();
        var earthRevision = created.page().draftRevision();
        var published = service.publish(name, created.page().version(), earthRevision).block();
        assertThat(published.page().publishedTheme()).isEqualTo(earth);

        when(themes.activeThemeIdentity()).thenReturn(Mono.just(nebula));
        var saved = service.save(name, new SavePageRequest(published.page().version(),
            document("Changed"), "manual"), "owner").block();
        var restored = service.restore(name,
            new RestoreRequest(saved.page().version(), earthRevision), "owner").block();

        assertThat(revisions.get(saved.page().draftRevision()).getSpec().getTheme())
            .isEqualTo(nebula);
        assertThat(revisions.get(restored.page().draftRevision()).getSpec().getTheme())
            .isEqualTo(earth);
    }

    @Test
    void listExposesTheCurrentThemeAndEachPagesPublishedTheme() {
        var earth = new ThemeRef("theme-earth", "Earth");
        var nebula = new ThemeRef("theme-nebula", "Nebula");
        when(themes.activeThemeIdentity()).thenReturn(Mono.just(earth));
        var created = service.create(new CreatePageRequest("listed-theme", "Themed"), "owner")
            .block();
        service.publish(created.page().name(), created.page().version(),
            created.page().draftRevision()).block();
        when(themes.activeThemeIdentity()).thenReturn(Mono.just(nebula));
        when(client.listBy(eq(ThemePage.class), any(ListOptions.class), any(PageRequest.class)))
            .thenAnswer(invocation -> Mono.just(new ListResult<>(1, 20, pages.size(),
                pages.values().stream().map(Json::deepCopy).toList())));

        var list = service.list(1, 20, null, null, null).block();

        assertThat(list.currentTheme()).isEqualTo(nebula);
        assertThat(list.items()).singleElement()
            .satisfies(item -> assertThat(item.publishedTheme()).isEqualTo(earth));
    }

    @Test
    void revisionPaginationIsScopedAndBounded() {
        var created = service.create(new CreatePageRequest("history", "History"), "owner").block();
        var options = new AtomicReference<ListOptions>();
        var request = new AtomicReference<PageRequest>();
        when(client.listBy(eq(ThemePageRevision.class), any(ListOptions.class),
            any(PageRequest.class)))
            .thenAnswer(invocation -> {
                options.set(invocation.getArgument(1));
                request.set(invocation.getArgument(2));
                return Mono.just(ListResult.emptyResult());
            });
        service.revisions(created.page().name(), 2, 10).block();
        assertThat(request.get().getPageNumber()).isEqualTo(2);
        assertThat(request.get().getPageSize()).isEqualTo(10);
        assertThat(options.get().getFieldSelector().query().toString())
            .contains(created.page().name());
        assertThat(request.get().getSort().toList()).containsExactly(
            Sort.Order.desc("metadata.creationTimestamp"), Sort.Order.asc("metadata.name"));
        assertBadRequest(() -> service.revisions(created.page().name(), 1, 101).block());
    }

    private static PageDocument document(String title) {
        return new PageDocument(1, "code", "", Map.of(), "<h1>" + title + "</h1>", "");
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
