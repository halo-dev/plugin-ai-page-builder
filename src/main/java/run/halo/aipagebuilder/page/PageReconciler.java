package run.halo.aipagebuilder.page;

import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.isNull;

import java.time.Duration;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;
import run.halo.app.extension.router.selector.FieldSelector;
import run.halo.aipagebuilder.ai.PageChatService;
import run.halo.aipagebuilder.model.ThemePage;
import run.halo.aipagebuilder.model.ThemePageRevision;

/** Keeps cleanup responsibility durable until every child has a deletion marker. */
@Component
public class PageReconciler implements Reconciler<Reconciler.Request> {
    public static final String FINALIZER = "aipagebuilder.halo.run/page-cleanup";
    private final ReactiveExtensionClient client;

    public PageReconciler(ReactiveExtensionClient client) {
        this.client = client;
    }

    @Override
    public Result reconcile(Request request) {
        // Halo invokes reconcilers on controller workers, not WebFlux request threads.
        reconcilePage(request.name()).block(Duration.ofSeconds(30));
        return Result.doNotRetry();
    }

    Mono<Void> reconcilePage(String name) {
        return client.fetch(ThemePage.class, name).flatMap(page -> {
            if (page.getMetadata().getDeletionTimestamp() != null) return cleanup(client, page);
            boolean changed = ExtensionUtil.addFinalizers(page.getMetadata(), Set.of(FINALIZER));
            return changed ? client.update(page).then() : Mono.empty();
        });
    }

    static Mono<Void> cleanup(ReactiveExtensionClient client, ThemePage page) {
        var name = page.getMetadata().getName();
        var options = new ListOptions().setFieldSelector(FieldSelector.of(equal("spec.pageName", name))
            .andQuery(isNull("metadata.deletionTimestamp")));
        return client.listAll(ThemePageRevision.class, options, Sort.unsorted())
            .concatMap(client::delete)
            .then(PageChatService.deletePageHistory(client, name))
            .then(client.fetch(ThemePage.class, name)).flatMap(latest -> {
                if (latest.getMetadata().getDeletionTimestamp() != null
                    && ExtensionUtil.removeFinalizers(latest.getMetadata(), Set.of(FINALIZER))) {
                    return client.update(latest).then();
                }
                return Mono.empty();
            }).retryWhen(reactor.util.retry.Retry.max(3)
                .filter(org.springframework.dao.OptimisticLockingFailureException.class::isInstance));
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder.extension(new ThemePage()).syncAllOnStart(true).build();
    }
}
