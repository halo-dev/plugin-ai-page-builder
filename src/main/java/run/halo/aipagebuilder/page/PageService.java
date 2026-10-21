package run.halo.aipagebuilder.page;

import static run.halo.app.extension.index.query.Queries.contains;
import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.isNull;
import static run.halo.app.extension.index.query.Queries.not;
import static run.halo.app.extension.index.query.Queries.or;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;
import run.halo.aipagebuilder.Json;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;
import run.halo.aipagebuilder.model.ThemePage;
import run.halo.aipagebuilder.model.ThemePageRevision;
import run.halo.aipagebuilder.page.PageApi.CreatePageRequest;
import run.halo.aipagebuilder.page.PageApi.PageDetail;
import run.halo.aipagebuilder.page.PageApi.PageList;
import run.halo.aipagebuilder.page.PageApi.PageSummary;
import run.halo.aipagebuilder.page.PageApi.RestoreRequest;
import run.halo.aipagebuilder.page.PageApi.RevisionDetail;
import run.halo.aipagebuilder.page.PageApi.RevisionSummary;
import run.halo.aipagebuilder.page.PageApi.SavePageRequest;

@Service
public class PageService {
    private final ReactiveExtensionClient client;
    private final ContentPolicy contentPolicy;
    private final PageImportReviewService importReviews;
    private final Set<String> slugWrites = ConcurrentHashMap.newKeySet();
    private final Map<String, Object> pageWrites = new ConcurrentHashMap<>();

    public PageService(ReactiveExtensionClient client, ContentPolicy contentPolicy,
        PageImportReviewService importReviews) {
        this.client = client;
        this.contentPolicy = contentPolicy;
        this.importReviews = importReviews;
    }

    public Mono<PageList> list(int page, int size, String keyword, String status, String sort) {
        if (page < 1 || size < 1 || size > 100) {
            return Mono.error(badRequest("page must be positive; size must be between 1 and 100"));
        }
        var selector = FieldSelector.of(isNull("metadata.deletionTimestamp"));
        if (keyword != null && !keyword.isBlank()) {
            selector = selector.andQuery(or(contains("spec.title", keyword),
                contains("spec.slug", keyword)));
        }
        if ("published".equals(status)) {
            selector = selector.andQuery(not(isNull("spec.publishedRevision")));
        } else if ("unpublished".equals(status)) {
            selector = selector.andQuery(isNull("spec.publishedRevision"));
        } else if (status != null && !status.isBlank()) {
            return Mono.error(badRequest("status must be published or unpublished"));
        }
        Sort.Order order;
        if (sort == null || sort.isBlank()) {
            order = Sort.Order.desc("spec.updatedAt");
        } else if ("created".equals(sort)) {
            order = Sort.Order.desc("metadata.creationTimestamp");
        } else {
            return Mono.error(badRequest("sort must be created"));
        }
        var options = new ListOptions().setFieldSelector(selector);
        return client.listBy(ThemePage.class, options,
                PageRequestImpl.of(page, size, Sort.by(order, Sort.Order.asc("metadata.name"))))
            .map(result -> new PageList(result.getItems().stream().map(PageService::summary).toList(),
                result.getTotal(), result.getPage(), result.getSize()));
    }

    public Mono<PageDetail> create(CreatePageRequest request, String actor) {
        return createDocument(request.slug(),
            new PageDocument(1, "code", "", Map.of(), "", ""),
            request.title(), "", request.themeLayout(), actor);
    }

    public Mono<PageDetail> importPage(String reviewId, byte[] archive, String actor,
        boolean confirmed) {
        return importReviews.authorize(reviewId, archive, actor, confirmed)
            .then(Mono.fromCallable(() -> PageArchive.read(archive))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()))
            .flatMap(imported -> createDocument(null, imported.document(),
                imported.title(), imported.description(), imported.themeLayout(), actor));
    }

    public Mono<PageDetail> clonePage(String name, PageApi.ClonePageRequest request, String actor) {
        return mutate(name, request.version(), source -> draftDetail(source).flatMap(detail -> {
            // Clone still requires an explicit slug even though create accepts a blank one.
            PagePaths.validate(request.slug());
            var original = detail.document();
            var copy = new PageDocument(original.js().isEmpty() ? 1 : 2, "code", null, null,
                original.html(), original.css(), original.js());
            return createDocument(request.slug(), copy, request.title(), source.getSpec().getDescription(),
                source.getSpec().isThemeLayout(), actor);
        }));
    }

    private Mono<PageDetail> createDocument(String slug, PageDocument document, String title,
        String description, Boolean themeLayout,
        String actor) {
        return Mono.defer(() -> {
            // Normalize blank slugs so callers cannot persist an empty string.
            final var normalizedSlug = slug == null || slug.isBlank() ? null : slug;
            validateProperties(title, description);
            contentPolicy.validate(document);
            Supplier<Mono<PageDetail>> write = () -> {
                var name = UUID.randomUUID().toString();
                var revision = newRevision(name, document, "manual", actor);
                return client.create(revision).flatMap(created -> {
                    var page = new ThemePage();
                    var metadata = new Metadata();
                    metadata.setName(name);
                    metadata.setFinalizers(Set.of(PageReconciler.FINALIZER));
                    page.setMetadata(metadata);
                    page.setSpec(new ThemePage.Spec());
                    page.getSpec().setTitle(title);
                    page.getSpec().setDescription(description);
                    page.getSpec().setSlug(normalizedSlug);
                    page.getSpec().setThemeLayout(themeLayout == null || themeLayout);
                    applyDraft(page, created);
                    return client.create(page).flux().single()
                        .onErrorResume(error -> {
                            if (error instanceof OptimisticLockingFailureException
                                || error instanceof DuplicateKeyException) {
                                return client.delete(created).onErrorResume(cleanupError -> Mono.empty())
                                    .then(Mono.error(error));
                            }
                            // A lost create response can leave a live page referencing this revision.
                            // Never destroy its source merely because the caller observed an error.
                            return client.fetch(ThemePage.class, name)
                                .filter(saved -> created.getMetadata().getName()
                                    .equals(saved.getSpec().getDraftRevision()))
                                .switchIfEmpty(Mono.error(error));
                        })
                        .map(saved -> detail(saved, created));
                });
            };
            if (normalizedSlug == null) {
                return write.get();
            }
            PagePaths.validate(normalizedSlug);
            return writeSlug(normalizedSlug,
                () -> checkSlugAvailable(normalizedSlug, null).then(Mono.defer(write::get)));
        }).onErrorMap(DuplicateKeyException.class, error -> conflict("Slug already exists"))
            .onErrorMap(OptimisticLockingFailureException.class,
                error -> conflict("Slug already exists or page changed"));
    }

    public Mono<PageDetail> get(String name) {
        return page(name).flatMap(this::draftDetail);
    }

    public Mono<PageDetail> save(String name, SavePageRequest request, String actor) {
        return mutate(name, request.version(), page -> {
            if (!"manual".equals(request.source()) && !"ai".equals(request.source())) {
                return Mono.error(badRequest("source must be manual or ai"));
            }
            contentPolicy.validate(request.document());
            return revision(name, page.getSpec().getDraftRevision()).flatMap(current -> {
                if (Objects.equals(current.getSpec().getDocument(), request.document())) {
                    return Mono.just(detail(page, current));
                }
                return writeDraft(page, request.document(), request.source(), actor, null);
            });
        });
    }

    public Mono<PageDetail> saveAi(String name, Long version, PageDocument document,
        String actor, String runId, String baseRevision, String slug, String title) {
        return saveAi(name, version, document, actor, runId, baseRevision, slug, title, null);
    }

    public Mono<PageDetail> saveAi(String name, Long version, PageDocument document,
        String actor, String runId, String baseRevision, String slug, String title,
        String description) {
        return mutate(name, version, page -> {
            if (baseRevision != null && !baseRevision.equals(page.getSpec().getDraftRevision())) {
                return Mono.error(conflict("Page changed; reload before saving"));
            }
            contentPolicy.validate(document);
            if (title != null || description != null) {
                validateProperties(title == null ? page.getSpec().getTitle() : title,
                    description == null ? page.getSpec().getDescription() : description);
            }
            if (slug != null && (page.getSpec().getSlug() != null
                || page.getSpec().getPublishedRevision() != null)) {
                return Mono.error(conflict("已有访问路径或已发布的页面不能由 AI 更改路径"));
            }
            Supplier<Mono<PageDetail>> write = () -> {
                if (title != null) page.getSpec().setTitle(title);
                if (description != null) page.getSpec().setDescription(description);
                if (slug != null) page.getSpec().setSlug(slug);
                page.getSpec().setLastAiCommit(new ThemePage.AiCommit(runId, null));
                page.getSpec().setUpdatedAt(Instant.now());
                return revision(name, page.getSpec().getDraftRevision()).flatMap(current -> {
                    if (Objects.equals(current.getSpec().getDocument(), document)) {
                        return client.update(page).map(saved -> detail(saved, current));
                    }
                    return writeDraft(page, document, "ai", actor, runId);
                });
            };
            return slug == null ? write.get() : writeSlug(slug,
                () -> checkSlugAvailable(slug, name).then(Mono.defer(write)));
        });
    }

    /** A pending run is committed only when its receipt reached the page CAS. */
    public Mono<ThemePage.AiCommit> committedAiRun(String name, String runId) {
        return page(name).mapNotNull(page -> page.getSpec().getLastAiCommit())
            .filter(commit -> runId.equals(commit.runId()));
    }

    public Mono<PageDetail> updateProperties(String name, PageApi.UpdatePagePropertiesRequest request) {
        return mutate(name, request.version(), page -> {
            var spec = page.getSpec();
            var title = request.title() == null ? spec.getTitle() : request.title();
            var description = request.description() == null ? spec.getDescription() : request.description();
            validateProperties(title, description);
            var slug = request.slug() == null ? spec.getSlug() : request.slug();
            var layout = request.themeLayout() == null ? spec.isThemeLayout() : request.themeLayout();
            if (Objects.equals(title, spec.getTitle()) && Objects.equals(description, spec.getDescription())
                && Objects.equals(slug, spec.getSlug()) && layout == spec.isThemeLayout()) {
                return draftDetail(page);
            }
            Supplier<Mono<PageDetail>> write = () -> {
                spec.setTitle(title);
                spec.setDescription(description);
                spec.setSlug(slug);
                spec.setThemeLayout(layout);
                spec.setUpdatedAt(Instant.now());
                return client.update(page).flatMap(this::draftDetail);
            };
            return Objects.equals(slug, spec.getSlug()) ? write.get() : writeSlug(slug,
                () -> checkSlugAvailable(slug, name).then(Mono.defer(write)));
        });
    }

    public static void validateProperties(String title, String description) {
        if (title == null || title.isBlank() || title.length() > 200) {
            throw badRequest("页面标题须为 1–200 个字符");
        }
        if (description != null && description.length() > 1000) {
            throw badRequest("页面描述不能超过 1000 个字符");
        }
    }

    public Mono<PageDetail> publish(String name, Long version) {
        return publish(name, version, null);
    }

    public Mono<PageDetail> publish(String name, Long version, String expectedDraftRevision) {
        return mutate(name, version, page -> {
            if (expectedDraftRevision != null
                && !expectedDraftRevision.equals(page.getSpec().getDraftRevision())) {
                return Mono.error(conflict("Page changed; reload before publishing"));
            }
            var slug = page.getSpec().getSlug();
            if (slug == null || slug.isBlank()) {
                return Mono.error(badRequest("请先设置访问路径再发布"));
            }
            return writeSlug(slug, () -> checkSlugAvailable(slug, name)
                .then(revision(name, page.getSpec().getDraftRevision())).flatMap(draft -> {
                    contentPolicy.validate(draft.getSpec().getDocument());
                    page.getSpec().setPublishedRevision(draft.getMetadata().getName());
                    page.getSpec().setUpdatedAt(Instant.now());
                    return client.update(page).map(saved -> detail(saved, draft));
                }));
        });
    }

    public Mono<PageDetail> unpublish(String name, Long version) {
        return mutate(name, version, page -> {
            page.getSpec().setPublishedRevision(null);
            page.getSpec().setUpdatedAt(Instant.now());
            return client.update(page).flatMap(this::draftDetail);
        });
    }

    public Mono<PageDetail> restore(String name, RestoreRequest request, String actor) {
        return mutate(name, request.version(), page ->
            revision(name, request.revision()).flatMap(historical -> {
                contentPolicy.validate(historical.getSpec().getDocument());
                return writeDraft(page, historical.getSpec().getDocument(), "restore", actor, null);
            }));
    }

    public Mono<Void> delete(String name, Long version) {
        return client.fetch(ThemePage.class, name).flatMap(existing -> {
            // A retry of an accepted deletion must not require the old resource version.
            if (existing.getMetadata().getDeletionTimestamp() != null) {
                return PageReconciler.cleanup(client, existing);
            }
            return mutate(name, version, page -> client.fetch(
                    run.halo.aipagebuilder.model.PageChatSession.class, name)
                .flatMap(session -> session.getSpec().getActive() != null
                    || session.getSpec().getDeletingSessionId() != null
                    ? Mono.<Void>error(conflict("请先结束当前聊天操作再删除页面")) : Mono.empty())
                .then(Mono.defer(() -> run.halo.app.extension.ExtensionUtil.addFinalizers(
                    page.getMetadata(), Set.of(PageReconciler.FINALIZER))
                    ? client.update(page) : Mono.just(page)))
                .flatMap(client::delete)
                .flatMap(deleted -> PageReconciler.cleanup(client, deleted)));
        });
    }

    public Mono<List<RevisionSummary>> revisions(String name) {
        return page(name).thenMany(client.listAll(ThemePageRevision.class, revisionOptions(name),
                Sort.by(Sort.Order.desc("metadata.creationTimestamp"),
                    Sort.Order.asc("metadata.name"))))
            .map(PageService::revisionSummary).collectList();
    }

    public record RevisionList(List<RevisionSummary> items, long total, int page, int size) { }

    public Mono<RevisionList> revisions(String name, int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            return Mono.error(badRequest("page must be positive; size must be between 1 and 100"));
        }
        return page(name).then(client.listBy(ThemePageRevision.class, revisionOptions(name),
            PageRequestImpl.of(page, size, Sort.by(Sort.Order.desc("metadata.creationTimestamp"),
                Sort.Order.asc("metadata.name")))))
            .map(result -> new RevisionList(result.getItems().stream()
                .map(PageService::revisionSummary).toList(), result.getTotal(), page, size));
    }

    public Mono<RevisionDetail> revisionDetail(String name, String revisionName) {
        return page(name).then(revision(name, revisionName))
            .map(revision -> new RevisionDetail(revisionSummary(revision),
                revision.getSpec().getDocument()));
    }

    public Mono<PageDetail> published(String slug) {
        return findPublished(slug).switchIfEmpty(Mono.error(notFound()));
    }

    public Mono<PageDetail> findPublished(String slug) {
        return client.listAll(ThemePage.class, slugOptions(slug), Sort.unsorted())
            .filter(page -> page.getMetadata().getDeletionTimestamp() == null
                && page.getSpec().getPublishedRevision() != null)
            .next()
            .flatMap(page -> revision(page.getMetadata().getName(),
                page.getSpec().getPublishedRevision()).map(revision -> detail(page, revision)));
    }

    private <T> Mono<T> writeSlug(String slug, Supplier<Mono<T>> write) {
        return Mono.defer(() -> {
            // Halo 2.26 guards unique-index prepare/commit by metadata.name, not index value.
            if (!slugWrites.add(slug)) {
                return Mono.error(conflict("Slug is being changed; retry after the current operation"));
            }
            // Wait for onComplete (including transaction commit), not just the first value.
            // Once subscribed, cache keeps the write alive if the HTTP request is cancelled.
            return Mono.defer(write).flux().collectList()
                .doOnTerminate(() -> slugWrites.remove(slug))
                .flatMap(values -> values.isEmpty() ? Mono.<T>empty() : Mono.just(values.getFirst()))
                .cache();
        });
    }

    private Mono<Void> checkSlugAvailable(String slug, String name) {
        return Mono.defer(() -> {
            PagePaths.validate(slug);
            return client.listAll(ThemePage.class, slugOptions(slug), Sort.unsorted())
                .filter(page -> !Objects.equals(name, page.getMetadata().getName()))
                .hasElements().flatMap(occupied -> occupied
                    ? Mono.error(conflict("Slug already exists")) : Mono.empty());
        });
    }

    private static ListOptions slugOptions(String slug) {
        return new ListOptions().setFieldSelector(FieldSelector.of(equal("spec.slug", slug)));
    }

    private Mono<PageDetail> writeDraft(ThemePage page, PageDocument document, String source,
        String actor, String runId) {
        var revision = newRevision(page.getMetadata().getName(), document, source, actor);
        revision.getSpec().setParentRevision(page.getSpec().getDraftRevision());
        return client.create(revision).flatMap(created -> {
            applyDraft(page, created);
            if (runId != null) {
                page.getSpec().setLastAiCommit(new ThemePage.AiCommit(
                    runId, created.getMetadata().getName()));
            }
            return client.update(page)
                .onErrorResume(OptimisticLockingFailureException.class, error ->
                    // A rejected compare-and-set cannot have referenced this unique revision.
                    client.delete(created).onErrorResume(cleanupError -> Mono.empty())
                        .then(Mono.error(error)))
                .map(saved -> detail(saved, created));
        });
    }

    private <T> Mono<T> mutate(String name, Long version, Function<ThemePage, Mono<T>> action) {
        return coordinate(name, () -> {
            if (version == null) {
                return Mono.error(badRequest("version is required"));
            }
            return page(name).flatMap(page -> {
                if (!Objects.equals(version, page.getMetadata().getVersion())) {
                    return Mono.error(conflict("Page changed; reload before saving"));
                }
                return action.apply(page);
            });
        }).onErrorMap(DuplicateKeyException.class, error -> conflict("Slug already exists"))
            .onErrorMap(OptimisticLockingFailureException.class,
                error -> conflict("Page changed; reload before saving"));
    }

    /** Prevent a chat reservation/creation from racing the page's deletion in this process. */
    public <T> Mono<T> withLivePage(String name, Supplier<Mono<T>> action) {
        return coordinate(name, () -> page(name).then(Mono.defer(action)));
    }

    public Mono<Void> reserveRun(String name, Long version, Supplier<Mono<Void>> reservation) {
        return mutate(name, version, page -> reservation.get());
    }

    private <T> Mono<T> coordinate(String name, Supplier<Mono<T>> action) {
        return Mono.defer(() -> {
            var token = new Object();
            if (pageWrites.putIfAbsent(name, token) != null) {
                return Mono.error(conflict("页面正在修改，请稍后重试"));
            }
            return Mono.defer(action).doOnSuccess(value -> pageWrites.remove(name, token))
                .doOnError(error -> pageWrites.remove(name, token))
                .doFinally(signal -> pageWrites.remove(name, token));
        });
    }

    private Mono<ThemePage> page(String name) {
        return client.fetch(ThemePage.class, name)
            .filter(page -> page.getMetadata().getDeletionTimestamp() == null)
            .switchIfEmpty(Mono.error(notFound()));
    }

    private Mono<ThemePageRevision> revision(String name, String reference) {
        if (reference == null || reference.isBlank()) {
            return Mono.error(notFound());
        }
        return client.fetch(ThemePageRevision.class, reference)
            .filter(revision -> revision.getMetadata().getDeletionTimestamp() == null
                && name.equals(revision.getSpec().getPageName()))
            .switchIfEmpty(Mono.error(notFound()));
    }

    private Mono<PageDetail> draftDetail(ThemePage page) {
        return revision(page.getMetadata().getName(), page.getSpec().getDraftRevision())
            .map(revision -> detail(page, revision));
    }

    private static ListOptions revisionOptions(String name) {
        return new ListOptions().setFieldSelector(FieldSelector.of(equal("spec.pageName", name))
            .andQuery(isNull("metadata.deletionTimestamp")));
    }

    private static ThemePageRevision newRevision(String name, PageDocument document, String source,
        String actor) {
        var revision = new ThemePageRevision();
        var metadata = new Metadata();
        metadata.setName(UUID.randomUUID().toString());
        revision.setMetadata(metadata);
        var spec = new ThemePageRevision.Spec();
        spec.setPageName(name);
        spec.setCreatedBy(actor);
        spec.setSource(source);
        // Snapshot editor project data too, so caller mutations cannot modify a revision.
        var json = Json.toJson(document);
        spec.setDocument(Json.fromJson(json, PageDocument.class));
        spec.setContentHash(sha256(json));
        revision.setSpec(spec);
        return revision;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void applyDraft(ThemePage page, ThemePageRevision revision) {
        page.getSpec().setDraftRevision(revision.getMetadata().getName());
        page.getSpec().setUpdatedAt(Instant.now());
    }

    private static PageDetail detail(ThemePage page, ThemePageRevision revision) {
        return new PageDetail(summary(page), revision.getSpec().getDocument());
    }

    private static PageSummary summary(ThemePage page) {
        var spec = page.getSpec();
        var name = page.getMetadata().getName();
        return new PageSummary(name, spec.getSlug(), spec.getTitle(), spec.getDescription(),
            page.getMetadata().getVersion(), page.getSpec().isThemeLayout(), spec.getDraftRevision(),
            spec.getPublishedRevision(),
            spec.getUpdatedAt(), !Objects.equals(spec.getDraftRevision(), spec.getPublishedRevision()));
    }

    private static RevisionSummary revisionSummary(ThemePageRevision revision) {
        return new RevisionSummary(revision.getMetadata().getName(),
            revision.getMetadata().getCreationTimestamp(), revision.getSpec().getCreatedBy(),
            revision.getSpec().getSource(),
            revision.getSpec().getParentRevision());
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Page or revision not found");
    }
}
