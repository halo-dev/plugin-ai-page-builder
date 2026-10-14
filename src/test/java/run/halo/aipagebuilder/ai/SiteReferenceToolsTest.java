package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Menu;
import run.halo.app.core.extension.MenuItem;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequest;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Ref;
import run.halo.app.extension.Secret;
import run.halo.app.infra.ExternalUrlSupplier;

class SiteReferenceToolsTest {
    private ReactiveExtensionClient client;
    private ExternalUrlSupplier urls;
    private SiteReferenceTools tools;

    @BeforeEach
    void setUp() {
        client = mock(ReactiveExtensionClient.class);
        urls = mock(ExternalUrlSupplier.class);
        tools = new SiteReferenceTools(client, urls);
    }

    @Test
    void returnsOnlyPublicFieldsAndNeverReadsSecretsOrUnrelatedGroups() throws Exception {
        config("system-default", Map.of("basic", """
            {"title":"Default","subtitle":"Default subtitle","language":"zh-CN","logo":"/logo.png"}
            """));
        config("system", Map.of("basic", """
            {"title":"Public title","subtitle":null,"favicon":"javascript:alert(1)",
             "adminEmail":"private@example.org","apiKey":"PRIVATE_TOKEN","externalUrl":"https://user:password@host/"}
            """, "seo", "not valid json PRIVATE_SEO", "smtp", "PRIVATE_SMTP",
            "codeInjection", "PRIVATE_SCRIPT", "authProvider", "PRIVATE_AUTH"));
        when(urls.getRaw()).thenReturn(URI.create("https://example.org/blog").toURL());
        var result = tools.execute("get_site_profile", Map.of()).block();
        assertThat(result).containsOnlyKeys("title", "subtitle", "logo", "favicon", "language", "url", "truncated")
            .containsEntry("title", "Public title").containsEntry("subtitle", "")
            .containsEntry("logo", "/logo.png").containsEntry("language", "zh-CN")
            .containsEntry("url", "https://example.org/blog").containsEntry("favicon", "");
        assertThat(result.toString()).doesNotContain("PRIVATE", "password", "adminEmail", "apiKey");
        verify(client, never()).fetch(eq(Secret.class), any());
    }

    @Test
    void handlesMissingConfigurationAndBoundsTextWithoutTruncatingLinks() {
        when(client.fetch(ConfigMap.class, "system-default")).thenReturn(Mono.empty());
        config("system", Map.of("basic", "{\"title\":\"" + "x".repeat(600)
            + "\",\"logo\":\"/" + "y".repeat(2050) + "\"}"));
        when(urls.get()).thenReturn(URI.create("/"));
        var result = tools.execute("get_site_profile", Map.of()).block();
        assertThat((String) result.get("title")).hasSize(512);
        assertThat(result).containsEntry("logo", "").containsEntry("url", "/")
            .containsEntry("truncated", true);
    }

    @Test
    void malformedConfigurationDoesNotIncludeRawValuesInError() {
        config("system-default", Map.of());
        config("system", Map.of("basic", "{PRIVATE_PASSWORD"));
        assertThatThrownBy(() -> tools.execute("get_site_profile", Map.of()).block())
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Site profile unavailable")
            .hasNoCause();
    }

    @Test
    void preservesNestedNavigationResolvedLinksAndSafeContactLinks() {
        var root = item("home", null, "/old");
        root.setStatus(new MenuItem.MenuItemStatus());
        root.getStatus().setDisplayName("Resolved home");
        root.getStatus().setHref("https://example.org/real");
        navigation(List.of(root, item("mail", "home", "mailto:hello@example.org"),
            item("phone", "home", "tel:+123456789"), item("anchor", "mail", "#contact")));
        var result = tools.execute("get_navigation", Map.of()).block();
        var items = menuItems(result);
        assertThat(items).hasSize(1);
        assertThat(items.getFirst()).containsEntry("href", "https://example.org/real")
            .containsEntry("displayName", "Resolved home");
        assertThat(children(items.getFirst())).extracting(value -> value.get("href"))
            .containsExactly("mailto:hello@example.org", "tel:+123456789");
        assertThat(children(children(items.getFirst()).getFirst())).hasSize(1);
        var options = ArgumentCaptor.forClass(ListOptions.class);
        var page = ArgumentCaptor.forClass(PageRequest.class);
        verify(client).listBy(eq(MenuItem.class), options.capture(), page.capture());
        assertThat(options.getValue().toString()).contains("spec.menuName", "main", "metadata.deletionTimestamp");
        assertThat(page.getValue().getPageSize()).isEqualTo(100);
        assertThat(result).containsEntry("truncated", false);
    }

    @Test
    void sortsBoundedItemsByPriorityWithoutRequiringAnUnregisteredIndex() {
        var first = item("z", null, "/first");
        first.getSpec().setPriority(-1);
        navigation(List.of(item("a", null, "/last"), first));
        assertThat(menuItems(tools.execute("get_navigation", Map.of()).block()))
            .extracting(item -> item.get("href")).containsExactly("/first", "/last");
        var page = ArgumentCaptor.forClass(PageRequest.class);
        verify(client).listBy(eq(MenuItem.class), any(), page.capture());
        assertThat(page.getValue().getSort().toString()).doesNotContain("spec.priority")
            .contains("metadata.name");
    }

    @Test
    void resolvesSupportedReferencesAndDropsUnavailableOrPrivateTargetsDespiteStaleStatus() {
        var publicPost = post("public", true, Post.VisibleEnum.PUBLIC);
        var draft = post("draft", false, Post.VisibleEnum.PUBLIC);
        var privatePost = post("private", true, Post.VisibleEnum.PRIVATE);
        var deleted = post("deleted", true, Post.VisibleEnum.PUBLIC);
        deleted.getMetadata().setDeletionTimestamp(Instant.now());
        var recycled = post("recycled", true, Post.VisibleEnum.PUBLIC);
        recycled.getSpec().setDeleted(true);
        var items = new ArrayList<MenuItem>();
        for (var post : List.of(publicPost, draft, privatePost, deleted, recycled)) {
            var name = post.getMetadata().getName();
            var item = item(name, null, "/PRIVATE_STALE_LINK");
            item.getSpec().setTargetRef(Ref.of(post));
            items.add(item);
            when(client.fetch(Post.class, name)).thenReturn(Mono.just(post));
        }
        var page = new SinglePage();
        page.setMetadata(metadata("page"));
        page.getMetadata().setLabels(Map.of(Post.PUBLISHED_LABEL, "true"));
        page.setSpec(new SinglePage.SinglePageSpec());
        page.getSpec().setTitle("About");
        page.getSpec().setDeleted(false);
        page.getSpec().setVisible(Post.VisibleEnum.PUBLIC);
        page.setStatus(new SinglePage.SinglePageStatus());
        page.getStatus().setPermalink("/custom-about");
        var pageItem = item("page", null, "/old");
        pageItem.getSpec().setTargetRef(Ref.of(page));
        items.add(pageItem);
        when(client.fetch(SinglePage.class, "page")).thenReturn(Mono.just(page));
        var category = new Category();
        category.setMetadata(metadata("category"));
        category.setSpec(new Category.CategorySpec());
        category.getSpec().setDisplayName("Category");
        category.setStatus(new Category.CategoryStatus());
        category.getStatus().setPermalink("/actual-category");
        var categoryItem = item("category", null, "/old");
        categoryItem.getSpec().setTargetRef(Ref.of(category));
        items.add(categoryItem);
        when(client.fetch(Category.class, "category")).thenReturn(Mono.just(category));
        var tag = new Tag();
        tag.setMetadata(metadata("tag"));
        tag.setSpec(new Tag.TagSpec());
        tag.getSpec().setDisplayName("Tag");
        tag.setStatus(new Tag.TagStatus());
        tag.getStatus().setPermalink("/actual-tag");
        var tagItem = item("tag", null, "/old");
        tagItem.getSpec().setTargetRef(Ref.of(tag));
        items.add(tagItem);
        when(client.fetch(Tag.class, "tag")).thenReturn(Mono.just(tag));
        var unknown = item("unknown", null, "/PRIVATE_FALLBACK");
        var secret = new Secret();
        secret.setMetadata(metadata("secret"));
        unknown.getSpec().setTargetRef(Ref.of(secret));
        items.add(unknown);
        var missing = item("missing", null, "/PRIVATE_FALLBACK");
        missing.getSpec().setTargetRef(Ref.of("missing", Post.GVK));
        items.add(missing);
        when(client.fetch(Post.class, "missing")).thenReturn(Mono.empty());
        navigation(items);
        var result = tools.execute("get_navigation", Map.of()).block();
        assertThat(menuItems(result)).extracting(item -> item.get("href"))
            .containsExactly("/actual-category", "/custom-about", "/actual-public", "/actual-tag");
        assertThat(result.toString()).doesNotContain("PRIVATE", "draft", "private", "recycled", "deleted");
        verify(client, never()).fetch(eq(Secret.class), any());
    }

    @Test
    void rejectsUnsafeLinksAndDoesNotPromoteChildrenOfFilteredParents() {
        var unsafe = List.of("javascript:alert(1)", "data:text/html,test", "//evil.example/a",
            "/\\evil.example", "https://user:password@example.org", "/bad\nlink", "ftp://example.org/a",
            "mailto:me@example.org?bcc=private@example.org", "mailto:me%0d%0abcc=other@example.org",
            "tel:+123%0d%0a", "/" + "x".repeat(2048));
        var items = new ArrayList<MenuItem>();
        for (int i = 0; i < unsafe.size(); i++) items.add(item("unsafe-" + i, null, unsafe.get(i)));
        items.add(item("hidden-child", "unsafe-0", "/safe-child"));
        var foreign = item("foreign", null, "/foreign");
        foreign.getSpec().setMenuName("other-menu");
        items.add(foreign);
        navigation(items);
        assertThat(menuItems(tools.execute("get_navigation", Map.of()).block())).isEmpty();
    }

    @Test
    void boundsDepthCountCyclesAndOrphans() {
        navigation(List.of(item("a", null, "/a"), item("b", "a", "/b"), item("c", "b", "/c"),
            item("cycle-1", "cycle-2", "/1"), item("cycle-2", "cycle-1", "/2"),
            item("orphan", "missing", "/orphan")));
        var result = tools.execute("get_navigation", Map.of("maxDepth", 2, "maxItems", 2)).block();
        var root = menuItems(result).getFirst();
        assertThat(children(root)).hasSize(1);
        assertThat(children(children(root).getFirst())).isEmpty();
        assertThat(result).containsEntry("truncated", true).containsEntry("scanTruncated", false);
    }

    @Test
    void stopsScanningAfterTwoBoundedPagesAndReportsTruncation() {
        when(client.listBy(eq(Menu.class), any(), any())).thenReturn(Mono.just(new ListResult<>(1, 5, 1, List.of(menu()))));
        when(client.listBy(eq(MenuItem.class), any(), any())).thenAnswer(invocation -> {
            PageRequest request = invocation.getArgument(2);
            int page = request.getPageNumber();
            assertThat(page).isBetween(1, 2);
            var items = IntStream.range(0, 100).mapToObj(i -> item(page + "-" + i, null, "/" + i)).toList();
            return Mono.just(new ListResult<>(page, 100, 10000, items));
        });
        var result = tools.execute("get_navigation", Map.of("maxItems", 3)).block();
        assertThat(menuItems(result)).hasSize(3);
        assertThat(result).containsEntry("truncated", true).containsEntry("scanTruncated", true);
        verify(client, org.mockito.Mockito.times(2)).listBy(eq(MenuItem.class), any(), any());
    }

    @Test
    void paginatesMenusAndSupportsExplicitMenuLookup() {
        when(client.listBy(eq(Menu.class), any(), any())).thenReturn(Mono.just(new ListResult<>(2, 1, 4, List.of())));
        var result = tools.execute("get_navigation", Map.of("page", 2, "size", 1)).block();
        assertThat(result).containsEntry("page", 2).containsEntry("size", 1)
            .containsEntry("hasNext", true).containsEntry("truncated", true);
        var page = ArgumentCaptor.forClass(PageRequest.class);
        verify(client).listBy(eq(Menu.class), any(), page.capture());
        assertThat(page.getValue().getPageNumber()).isEqualTo(2);
        assertThat(page.getValue().getPageSize()).isEqualTo(1);
        when(client.fetch(Menu.class, "missing")).thenReturn(Mono.empty());
        assertThat(tools.execute("get_navigation", Map.of("menuName", "missing")).block())
            .containsEntry("menus", List.of()).containsEntry("hasNext", false);
    }

    @Test
    void rejectsInvalidBoundsBeforeAccessAndDefinesOnlyTwoTools() {
        for (var args : List.<Map<String, Object>>of(Map.of("page", 0), Map.of("size", 11),
            Map.of("maxDepth", 6), Map.of("maxItems", 101), Map.of("maxItems", 0),
            Map.of("page", 1.2), Map.of("page", Double.NaN), Map.of("page", 2147483648L),
            Map.of("size", "2"), Map.of("menuName", 3))) {
            assertThatThrownBy(() -> tools.execute("get_navigation", args).block())
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> tools.execute("unknown", Map.of()).block()).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client, urls);
        assertThat(SiteReferenceTools.definitions()).extracting(PageReferenceTools.Definition::name)
            .containsExactly("get_site_profile", "get_navigation");
    }

    private void config(String name, Map<String, String> data) {
        var config = new ConfigMap();
        config.setData(data);
        when(client.fetch(ConfigMap.class, name)).thenReturn(Mono.just(config));
    }

    private void navigation(List<MenuItem> items) {
        when(client.listBy(eq(Menu.class), any(), any())).thenReturn(Mono.just(new ListResult<>(1, 5, 1, List.of(menu()))));
        when(client.listBy(eq(MenuItem.class), any(), any())).thenReturn(Mono.just(new ListResult<>(1, 100, items.size(), items)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> menuItems(Map<String, Object> result) {
        var menus = (List<Map<String, Object>>) result.get("menus");
        return (List<Map<String, Object>>) menus.getFirst().get("items");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> children(Map<String, Object> item) {
        return (List<Map<String, Object>>) item.get("children");
    }

    private static Metadata metadata(String name) {
        var metadata = new Metadata();
        metadata.setName(name);
        return metadata;
    }

    private static Menu menu() {
        var menu = new Menu();
        menu.setMetadata(metadata("main"));
        menu.setSpec(new Menu.Spec());
        menu.getSpec().setDisplayName("Main");
        return menu;
    }

    private static MenuItem item(String name, String parent, String href) {
        var item = new MenuItem();
        item.setMetadata(metadata(name));
        item.setSpec(new MenuItem.MenuItemSpec());
        item.getSpec().setMenuName("main");
        item.getSpec().setDisplayName(name);
        item.getSpec().setParent(parent);
        item.getSpec().setHref(href);
        return item;
    }

    private static Post post(String name, boolean published, Post.VisibleEnum visible) {
        var post = new Post();
        post.setMetadata(metadata(name));
        post.getMetadata().setLabels(Map.of(Post.PUBLISHED_LABEL, String.valueOf(published)));
        post.setSpec(new Post.PostSpec());
        post.getSpec().setTitle(name);
        post.getSpec().setVisible(visible);
        post.getSpec().setDeleted(false);
        post.setStatus(new Post.PostStatus());
        post.getStatus().setPermalink("/actual-" + name);
        return post;
    }
}
