package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.pf4j.PluginStateListener;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import run.halo.app.plugin.SpringPluginManager;

class PublicApiCatalogTest {
    private final SpringPluginManager manager = mock(SpringPluginManager.class);
    private final ApplicationContext root = mock(ApplicationContext.class);
    private final AtomicLong clock = new AtomicLong();
    private PublicApiCatalog catalog;
    private PluginStateListener listener;

    @BeforeEach
    void setUp() {
        when(manager.getRootContext()).thenReturn(root);
        catalog = new PublicApiCatalog(manager, clock::get);
        var captor = ArgumentCaptor.forClass(PluginStateListener.class);
        verify(manager).addPluginStateListener(captor.capture());
        listener = captor.getValue();
    }

    @AfterEach
    void close() { catalog.close(); }

    @Test
    void discoversNestedPublicRoutesAndKeepsPostSearch() {
        var routes = RouterFunctions.nest(RequestPredicates.path("/apis/api.content.halo.run/v1alpha1"),
            route("/posts", "queryPosts"));
        var search = SpringdocRouteBuilder.route().POST("/apis/api.halo.run/v1alpha1/indices/-/search",
            request -> ServerResponse.ok().build(), operation -> operation.operationId("searchIndices"))
            .build();
        when(root.getBeansOfType(RouterFunction.class)).thenReturn(Map.of("posts", routes, "search", search,
            "console", route("/apis/api.console.halo.run/v1alpha1/posts", "privatePosts"),
            "plugin", route("/apis/api.example.halo.run/v1alpha1/items", "pluginItems"),
            "notification", route("/apis/api.notification.halo.run/v1alpha1/userspaces/me", "privateNotification")));
        var result = catalog.search("", 20);
        assertThat(items(result)).hasSize(3);
        assertThat(items(result)).extracting(item -> item.get("id"))
            .contains("GET /apis/api.content.halo.run/v1alpha1/posts",
                "POST /apis/api.halo.run/v1alpha1/indices/-/search");
        assertThat(result).containsEntry("anonymousAccess", "unverified").containsEntry("partial", false);
        assertThat(items(catalog.search("queryPosts", 8)).getFirst())
            .containsEntry("operationId", "queryPosts");
        assertThat(items(catalog.search("doesNotExist", 8))).isEmpty();
    }

    @Test
    void refreshesOnPluginEventsAndTtlAndDetailsNeverUseOldSnapshot() {
        var calls = new AtomicInteger();
        when(root.getBeansOfType(RouterFunction.class)).thenAnswer(ignored -> {
            calls.incrementAndGet();
            return Map.of("plugin", route("/apis/api.example.halo.run/v1alpha1/items", "pluginItems"));
        });
        catalog.search("", 8);
        catalog.search("", 8);
        assertThat(calls).hasValue(1);
        listener.pluginStateChanged(null);
        catalog.search("", 8);
        assertThat(calls).hasValue(2);
        clock.addAndGet(Duration.ofSeconds(61).toNanos());
        catalog.search("", 8);
        assertThat(calls).hasValue(3);
        when(root.getBeansOfType(RouterFunction.class)).thenReturn(Map.of());
        var detail = catalog.details(List.of("GET /apis/api.example.halo.run/v1alpha1/items"));
        assertThat(items(detail).getFirst()).containsEntry("status", "not_found");
        listener.pluginStateChanged(null);
        assertThat(items(catalog.search("", 8))).isEmpty();
    }

    @Test
    void retriesAnInvalidatedBuildAndRejectsContinuousChanges() {
        var calls = new AtomicInteger();
        when(root.getBeansOfType(RouterFunction.class)).thenAnswer(ignored -> {
            if (calls.incrementAndGet() == 1) listener.pluginStateChanged(null);
            return Map.of("posts", route("/apis/api.content.halo.run/v1alpha1/posts", "queryPosts"));
        });
        assertThat(items(catalog.search("", 8))).hasSize(1);
        assertThat(calls).hasValue(2);
        listener.pluginStateChanged(null);
        when(root.getBeansOfType(RouterFunction.class)).thenAnswer(ignored -> {
            listener.pluginStateChanged(null);
            return Map.of();
        });
        assertThatThrownBy(() -> catalog.search("", 8)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("正在变化");
        assertThatThrownBy(() -> catalog.details(List.of("GET /apis/api.example/v1/items")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("正在变化");
    }

    @Test
    void sharesOneBuildAcrossConcurrentSearches() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(root.getBeansOfType(RouterFunction.class)).thenAnswer(ignored -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return Map.of();
        });
        var first = CompletableFuture.supplyAsync(() -> catalog.search("", 8));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var second = CompletableFuture.supplyAsync(() -> catalog.search("", 8));
        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
        verify(root, times(1)).getBeansOfType(RouterFunction.class);
    }

    @Test
    void marksMissingMetadataAndAmbiguousRoutesWithoutGuessing() {
        var path = "/apis/api.example.halo.run/v1alpha1/items";
        var bare = RouterFunctions.route(RequestPredicates.GET(path + "/bare"),
            request -> ServerResponse.ok().build());
        when(root.getBeansOfType(RouterFunction.class)).thenReturn(Map.of(
            "first", route(path, "first"), "second", route(path, "second"), "bare", bare));
        var result = catalog.search("", 8);
        assertThat(result).containsEntry("partial", true);
        assertThat(items(result)).extracting(item -> item.get("contract"))
            .containsExactly("ambiguous", "missing");
        assertThat(items(catalog.details(List.of("GET " + path, "GET " + path + "/bare"))))
            .extracting(item -> item.get("status")).containsExactly("ambiguous", "unavailable");
    }

    @Test
    void deduplicatesSameRouterAndParsesCurrentResponseType() {
        var route = route("/apis/api.example.halo.run/v1alpha1/items", "items");
        when(root.getBeansOfType(RouterFunction.class)).thenReturn(Map.of("first", route, "alias", route));
        assertThat(items(catalog.search("", 8))).hasSize(1);
        var item = items(catalog.details(List.of("GET /apis/api.example.halo.run/v1alpha1/items"))).getFirst();
        assertThat(item).containsEntry("status", "ok").containsKeys("responses", "components");
    }

    @Test
    void continuesWhenOneRouterCannotBeVisited() {
        var broken = mock(RouterFunction.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("broken route"))
            .when(broken).accept(org.mockito.ArgumentMatchers.any());
        when(root.getBeansOfType(RouterFunction.class)).thenReturn(Map.of("broken", broken,
            "healthy", route("/apis/api.example.halo.run/v1alpha1/items", "items")));
        var result = catalog.search("", 8);
        assertThat(result).containsEntry("partial", true);
        assertThat(items(result)).hasSize(1);
        assertThat((List<?>) result.get("warnings")).hasSize(1);
    }

    @Test
    void closesListenerAndRejectsFurtherReads() {
        catalog.close();
        verify(manager).removePluginStateListener(listener);
        assertThatThrownBy(() -> catalog.search("", 8)).isInstanceOf(IllegalStateException.class);
    }

    private static RouterFunction<ServerResponse> route(String path, String id) {
        return SpringdocRouteBuilder.route().GET(path, request -> ServerResponse.ok().build(),
            operation -> operation.operationId(id).description("List public items")
                .response(responseBuilder().implementation(Item.class))).build();
    }

    private record Item(String title) { }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("items");
    }
}
