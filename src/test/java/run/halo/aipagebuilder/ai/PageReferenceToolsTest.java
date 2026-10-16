package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequest;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.aipagebuilder.model.PageDocument;
import run.halo.aipagebuilder.model.ThemePage;
import run.halo.aipagebuilder.model.ThemePageRevision;
import run.halo.aipagebuilder.theme.ThemeContextService;

class PageReferenceToolsTest {
    private ReactiveExtensionClient client;
    private ThemeContextService themes;
    private WebReferenceTools web;
    private ApiReferenceTools apis;
    private PageReferenceTools tools;

    @BeforeEach
    void setUp() {
        client = mock(ReactiveExtensionClient.class);
        themes = mock(ThemeContextService.class);
        web = mock(WebReferenceTools.class);
        apis = mock(ApiReferenceTools.class);
        tools = new PageReferenceTools(client, themes,
            mock(SiteReferenceTools.class), mock(ContentReferenceTools.class), web, apis);
    }

    @Test
    void listsOnlyPublishedPublicLivePostsAndKeepsActualPermalinks() {
        var publicPost = post("public", true, Post.VisibleEnum.PUBLIC, false);
        publicPost.getStatus().setExcerpt("x".repeat(900));
        var draft = post("draft", false, Post.VisibleEnum.PUBLIC, false);
        var privatePost = post("private", true, Post.VisibleEnum.PRIVATE, false);
        var recycled = post("recycled", true, Post.VisibleEnum.PUBLIC, true);
        var deleted = post("deleted", true, Post.VisibleEnum.PUBLIC, false);
        deleted.getMetadata().setDeletionTimestamp(Instant.now());
        when(client.listBy(eq(Post.class), any(), any())).thenReturn(Mono.just(
            new ListResult<>(2, 5, 20, List.of(publicPost, draft, privatePost, recycled, deleted))));
        var result = tools.session(null, true).listPosts(Map.of("page", 2, "size", 5, "keyword", "public")).block();
        var items = items(result);
        assertThat(items).hasSize(1);
        assertThat(items.getFirst()).containsEntry("permalink", "https://example.org/custom/public")
            .containsEntry("title", "public").doesNotContainKeys("spec", "metadata", "releaseSnapshot");
        assertThat((String) items.getFirst().get("excerpt")).hasSize(512);
        var options = ArgumentCaptor.forClass(ListOptions.class);
        var pageable = ArgumentCaptor.forClass(PageRequest.class);
        verify(client).listBy(eq(Post.class), options.capture(), pageable.capture());
        assertThat(options.getValue().toString()).contains("content.halo.run/published", "true",
            "spec.visible", "PUBLIC", "spec.deleted", "false", "metadata.deletionTimestamp", "spec.title");
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        assertThat(result).containsEntry("hasNext", true);
    }

    @Test
    void appliesSamePublicFilteringToSinglePages() {
        var publicPage = singlePage("public", true, Post.VisibleEnum.PUBLIC, false);
        var draft = singlePage("draft", false, Post.VisibleEnum.PUBLIC, false);
        var privatePage = singlePage("private", true, Post.VisibleEnum.PRIVATE, false);
        var recycled = singlePage("recycled", true, Post.VisibleEnum.PUBLIC, true);
        var deleted = singlePage("deleted", true, Post.VisibleEnum.PUBLIC, false);
        deleted.getMetadata().setDeletionTimestamp(Instant.now());
        when(client.listBy(eq(SinglePage.class), any(), any())).thenReturn(Mono.just(
            new ListResult<>(1, 20, 5, List.of(publicPage, draft, privatePage, recycled, deleted))));
        var result = tools.session(null, true).listSinglePages(Map.of()).block();
        assertThat(items(result)).hasSize(1);
        assertThat(items(result).getFirst()).containsEntry("permalink", "https://example.org/about");
    }

    @Test
    void rejectsUnboundedOrFractionalPaginationBeforeQuerying() {
        for (var args : List.<Map<String, Object>>of(Map.of("page", 0), Map.of("size", 0),
            Map.of("size", 51), Map.of("size", 1.5), Map.of("page", 2147483648L), Map.of("page", "2"))) {
            assertThatThrownBy(() -> tools.session(null, true).listPosts(args).block())
                .isInstanceOf(IllegalArgumentException.class);
        }
        verify(client, never()).listBy(eq(Post.class), any(), any());
    }

    @Test
    void aiPageBuilderListingUsesPublishedRevisionTitleAndFiltersKeywordBeforePagination() {
        var first = page("a", "Draft secret", "published-a");
        var second = page("b", "Another draft", "published-b");
        second.getSpec().setSlug("company/about");
        var unpublished = page("c", "Secret", null);
        when(client.listBy(eq(ThemePage.class), any(), any())).thenReturn(Mono.just(
            new ListResult<>(1, 100, 3, List.of(first, second, unpublished))));
        when(client.fetch(ThemePageRevision.class, "published-a")).thenReturn(Mono.just(revision("published-a", "a", "Public title A", "")));
        when(client.fetch(ThemePageRevision.class, "published-b")).thenReturn(Mono.just(revision("published-b", "b", "Public title B", "")));
        var session = tools.session(null, true);
        var result = session.listAiPageBuilderPages(Map.of("page", 2, "size", 1, "keyword", "Public title")).block();
        assertThat(items(result)).hasSize(1);
        assertThat(items(result).getFirst()).containsEntry("title", "Public title B")
            .containsEntry("name", "b").containsEntry("permalink", "/company/about");
        assertThat(items(session.listAiPageBuilderPages(Map.of("keyword", "company/about")).block()))
            .hasSize(1);
        assertThat(items(session.listAiPageBuilderPages(Map.of("keyword", "Draft secret")).block())).isEmpty();
    }

    @Test
    void aiPageBuilderReadDefaultsToPublishedAndDraftNeedsAdministratorAndIsMarked() {
        var page = page("a", "Draft title", "published-a");
        page.getSpec().setDraftRevision("draft-a");
        when(client.fetch(ThemePage.class, "a")).thenReturn(Mono.just(page));
        when(client.fetch(ThemePageRevision.class, "published-a"))
            .thenReturn(Mono.just(revision("published-a", "a", "Published title", "public source")));
        when(client.fetch(ThemePageRevision.class, "draft-a"))
            .thenReturn(Mono.just(revision("draft-a", "a", "Draft title", "draft source")));
        var published = tools.session(null, false).readAiPageBuilder(Map.of("name", "a")).block();
        assertThat(published).containsEntry("version", "PUBLISHED").containsEntry("title", "Published title")
            .containsEntry("draft", false).containsEntry("js", "window.test = true;");
        assertThatThrownBy(() -> tools.session(null, false)
            .readAiPageBuilder(Map.of("name", "a", "version", "DRAFT")).block())
            .isInstanceOf(IllegalStateException.class);
        var draft = tools.session(null, true).readAiPageBuilder(Map.of("name", "a", "version", "DRAFT")).block();
        assertThat(draft).containsEntry("draft", true).containsEntry("version", "DRAFT")
            .containsEntry("title", "Draft title").containsEntry("permalink", "");
    }

    @Test
    void boundsCombinedAiPageBuilderSourceAndRejectsForeignRevision() {
        var page = page("a", "Draft", "published-a");
        when(client.fetch(ThemePage.class, "a")).thenReturn(Mono.just(page));
        var revision = revision("published-a", "a", "Published", "x".repeat(16380));
        when(client.fetch(ThemePageRevision.class, "published-a")).thenReturn(Mono.just(revision));
        var result = tools.session(null, true).readAiPageBuilder(Map.of("name", "a")).block();
        assertThat(((String) result.get("html")).length() + ((String) result.get("css")).length()
            + ((String) result.get("js")).length()).isEqualTo(16384);
        assertThat(result).containsEntry("truncated", true);
        revision.getSpec().setPageName("another");
        assertThatThrownBy(() -> tools.session(null, true).readAiPageBuilder(Map.of("name", "a")).block())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pinsThemeAcrossCallsAndExposesReadOnlyDefinitions() {
        when(themes.activeThemeName()).thenReturn(Mono.just("a"), Mono.just("b"));
        when(themes.listFiles("a", "templates", 0, 50)).thenReturn(Mono.just(Map.of("themeName", "a")));
        var session = tools.session(null, true);
        assertThat(session.execute("list_theme_files", Map.of()).block()).containsEntry("themeName", "a");
        assertThatThrownBy(() -> session.execute("list_theme_files", Map.of()).block())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("changed");
        assertThat(PageReferenceTools.definitions()).extracting(PageReferenceTools.Definition::name)
            .containsExactly("list_theme_files", "read_theme_file", "list_posts", "list_single_pages",
                "list_ai_pages", "read_ai_page", "get_site_profile", "get_navigation",
                "read_content", "search_attachments", "fetch_webpage", "search_public_apis", "get_public_api");
    }

    @Test
    void dispatchesFetchWebpageToWebReferenceTools() {
        var arguments = Map.<String, Object>of("url", "https://example.org/reference");
        var result = Map.<String, Object>of("text", "Reference content");
        when(web.execute(arguments)).thenReturn(Mono.just(result));
        assertThat(tools.session(null, true).execute("fetch_webpage", arguments).block())
            .isSameAs(result);
        verify(web).execute(arguments);
    }

    @Test
    void dispatchesPublicApiToolsThroughReferenceSession() {
        for (var name : List.of("search_public_apis", "get_public_api")) {
            var arguments = Map.<String, Object>of("query", "posts");
            var result = Map.<String, Object>of("items", List.of());
            when(apis.execute(name, arguments)).thenReturn(Mono.just(result));
            assertThat(tools.session(null, true).execute(name, arguments).block()).isSameAs(result);
            verify(apis).execute(name, arguments);
        }
    }

    @Test
    void fetchWebpageRequiresOnlyAUrlString() {
        var definition = PageReferenceTools.definitions().stream()
            .filter(tool -> tool.name().equals("fetch_webpage")).findFirst().orElseThrow();
        assertThat(definition.inputSchema()).isEqualTo(Map.of("type", "object",
            "properties", Map.of("url", Map.of("type", "string")),
            "required", List.of("url"), "additionalProperties", false));
    }

    @Test
    void bindsContextThemeBeforeTheFirstFileCall() {
        var session = tools.session(null, true);
        session.bindTheme("a");
        session.bindTheme("a");
        assertThatThrownBy(() -> session.bindTheme("b"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("changed");
        when(themes.activeThemeName()).thenReturn(Mono.just("b"));
        assertThatThrownBy(() -> session.listThemeFiles(Map.of()).block())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("changed");
        verify(themes, never()).listFiles(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("items");
    }

    private static Metadata metadata(String name, boolean published) {
        var metadata = new Metadata();
        metadata.setName(name);
        metadata.setLabels(Map.of(Post.PUBLISHED_LABEL, String.valueOf(published)));
        return metadata;
    }

    private static Post post(String name, boolean published, Post.VisibleEnum visible, boolean deleted) {
        var post = new Post();
        post.setMetadata(metadata(name, published));
        post.setSpec(new Post.PostSpec());
        post.getSpec().setTitle(name);
        post.getSpec().setVisible(visible);
        post.getSpec().setDeleted(deleted);
        post.setStatus(new Post.PostStatus());
        post.getStatus().setPermalink("https://example.org/custom/" + name);
        return post;
    }

    private static SinglePage singlePage(String name, boolean published, Post.VisibleEnum visible, boolean deleted) {
        var page = new SinglePage();
        page.setMetadata(metadata(name, published));
        page.setSpec(new SinglePage.SinglePageSpec());
        page.getSpec().setTitle(name);
        page.getSpec().setVisible(visible);
        page.getSpec().setDeleted(deleted);
        page.setStatus(new SinglePage.SinglePageStatus());
        page.getStatus().setPermalink("https://example.org/about");
        return page;
    }

    private static ThemePage page(String name, String title, String publishedRevision) {
        var page = new ThemePage();
        page.setMetadata(metadata(name, false));
        page.setSpec(new ThemePage.Spec());
        page.getSpec().setTitle(title);
        page.getSpec().setPublishedRevision(publishedRevision);
        return page;
    }

    private static ThemePageRevision revision(String name, String pageName, String title, String html) {
        var revision = new ThemePageRevision();
        revision.setMetadata(metadata(name, false));
        revision.setSpec(new ThemePageRevision.Spec());
        revision.getSpec().setPageName(pageName);
        revision.getSpec().setDocument(new PageDocument(1, "code", "", Map.of(), html, "body{}", title,
            "Description", "window.test = true;"));
        return revision;
    }
}
