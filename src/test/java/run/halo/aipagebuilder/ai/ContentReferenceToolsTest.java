package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import run.halo.app.content.ContentWrapper;
import run.halo.app.content.PatchUtils;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequest;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Ref;

class ContentReferenceToolsTest {
    private ReactiveExtensionClient client;
    private PostContentService content;
    private ContentReferenceTools tools;

    @BeforeEach
    void setUp() {
        client = mock(ReactiveExtensionClient.class);
        content = mock(PostContentService.class);
        tools = new ContentReferenceTools(client, content);
    }

    @Test
    void readsOnlyReleaseThroughContentServiceAndBoundsSource() {
        var post = post();
        when(client.fetch(Post.class, "post")).thenReturn(Mono.just(post));
        when(content.getReleaseContent("post")).thenReturn(Mono.just(ContentWrapper.builder()
            .snapshotName("release").content("x".repeat(17000)).raw("unpublished raw must not leak").build()));
        var result = read("Post", 10, 16384);
        assertThat((String) result.get("source")).hasSize(16384);
        assertThat(result).containsEntry("revision", "release").containsEntry("offset", 10)
            .containsEntry("nextOffset", 16394).containsEntry("truncated", true)
            .containsEntry("permalink", "http://localhost:8090/posts/post")
            .doesNotContainKeys("raw", "rawPatch", "headSnapshot");
        verify(content, never()).getHeadContent(any());
        verify(client, never()).fetch(eq(Snapshot.class), any());
        var end = read("Post", Integer.MAX_VALUE, 100);
        assertThat(end).containsEntry("source", "").containsEntry("offset", 17000)
            .containsEntry("hasNext", false).containsEntry("nextOffset", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "private", "recycled", "deleted", "missing-release"})
    void rejectsInvisiblePostsBeforeReadingContent(String state) {
        var post = post();
        switch (state) {
            case "draft" -> post.getMetadata().setLabels(Map.of(Post.PUBLISHED_LABEL, "false"));
            case "private" -> post.getSpec().setVisible(Post.VisibleEnum.PRIVATE);
            case "recycled" -> post.getSpec().setDeleted(true);
            case "deleted" -> post.getMetadata().setDeletionTimestamp(Instant.now());
            case "missing-release" -> post.getSpec().setReleaseSnapshot(null);
            default -> throw new AssertionError(state);
        }
        when(client.fetch(Post.class, "post")).thenReturn(Mono.just(post));
        assertThatThrownBy(() -> read("Post", 0, 10)).isInstanceOf(IllegalArgumentException.class);
        verify(content, never()).getReleaseContent(any());
    }

    @Test
    void rejectsReleaseOrVisibilityChangesDuringRead() {
        var post = post();
        when(client.fetch(Post.class, "post")).thenReturn(Mono.just(post));
        when(content.getReleaseContent("post")).thenReturn(Mono.just(ContentWrapper.builder()
            .snapshotName("head").content("secret").build()));
        assertThatThrownBy(() -> read("Post", 0, 10)).isInstanceOf(IllegalArgumentException.class);
        when(content.getReleaseContent("post")).thenReturn(Mono.just(ContentWrapper.builder()
            .snapshotName("release").content("published").build()));
        var nowPrivate = post();
        nowPrivate.getSpec().setVisible(Post.VisibleEnum.PRIVATE);
        when(client.fetch(Post.class, "post")).thenReturn(Mono.just(post), Mono.just(nowPrivate));
        assertThatThrownBy(() -> read("Post", 0, 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reconstructsSinglePageReleasePatchAgainstItsBaseInsteadOfReturningPatchOrHead() {
        var page = page();
        when(client.fetch(SinglePage.class, "post")).thenReturn(Mono.just(page));
        var base = snapshot("base", page, true, "<p>base</p>");
        var release = snapshot("release", page, false,
            PatchUtils.diffToJsonPatch("<p>base</p>", "<p>published</p>"));
        when(client.fetch(Snapshot.class, "base")).thenReturn(Mono.just(base));
        when(client.fetch(Snapshot.class, "release")).thenReturn(Mono.just(release));
        assertThat(read("SinglePage", 0, 100)).containsEntry("source", "<p>published</p>")
            .containsEntry("revision", "release").containsEntry("truncated", false)
            .containsEntry("nextOffset", null);
        verify(client, never()).fetch(Snapshot.class, "head");
        release.getSpec().setSubjectRef(Ref.of(post()));
        assertThatThrownBy(() -> read("SinglePage", 0, 100)).isInstanceOf(IllegalArgumentException.class);
        release.getSpec().setSubjectRef(Ref.of(page));
        base.getMetadata().setAnnotations(Map.of());
        assertThatThrownBy(() -> read("SinglePage", 0, 100)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "private", "recycled", "deleted"})
    void rejectsInvisibleSinglePagesWithoutFetchingSnapshots(String state) {
        var page = page();
        switch (state) {
            case "draft" -> page.getMetadata().setLabels(Map.of(Post.PUBLISHED_LABEL, "false"));
            case "private" -> page.getSpec().setVisible(Post.VisibleEnum.PRIVATE);
            case "recycled" -> page.getSpec().setDeleted(true);
            case "deleted" -> page.getMetadata().setDeletionTimestamp(Instant.now());
            default -> throw new AssertionError(state);
        }
        when(client.fetch(SinglePage.class, "post")).thenReturn(Mono.just(page));
        assertThatThrownBy(() -> read("SinglePage", 0, 10)).isInstanceOf(IllegalArgumentException.class);
        verify(client, never()).fetch(eq(Snapshot.class), any());
    }

    @Test
    void handlesReleaseEqualToBaseAndMissingOrDeletedSnapshots() {
        var page = page();
        page.getSpec().setReleaseSnapshot("base");
        when(client.fetch(SinglePage.class, "post")).thenReturn(Mono.just(page));
        var base = snapshot("base", page, true, "<p>original</p>");
        when(client.fetch(Snapshot.class, "base")).thenReturn(Mono.just(base));
        assertThat(read("SinglePage", 0, 100)).containsEntry("source", "<p>original</p>");
        verify(client).fetch(Snapshot.class, "base");
        base.getMetadata().setDeletionTimestamp(Instant.now());
        assertThatThrownBy(() -> read("SinglePage", 0, 100)).isInstanceOf(IllegalArgumentException.class);
        when(client.fetch(Snapshot.class, "base")).thenReturn(Mono.empty());
        assertThatThrownBy(() -> read("SinglePage", 0, 100)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesOnlyWhitelistedAttachmentFieldsAndMarksUnknownMetadata() {
        var attachment = attachment("a", "https://cdn.example.org/photo.png");
        attachment.getSpec().setMediaType(null);
        attachment.getSpec().setSize(null);
        attachment.getMetadata().setAnnotations(Map.of("access-key", "SECRET", "width", "999"));
        attachment.getStatus().setThumbnails(Map.of("S", "https://cdn.example.org/photo.png?token=SECRET"));
        attachments(List.of(attachment));
        var result = tools.execute("search_attachments", Map.of()).block();
        assertThat(items(result)).hasSize(1);
        assertThat(items(result).getFirst()).containsEntry("width", null).containsEntry("height", null)
            .containsEntry("aspectRatio", null).containsEntry("mediaType", null).containsEntry("size", null)
            .containsEntry("dimensionsKnown", false).containsEntry("visibility", "unknown")
            .doesNotContainKeys("annotations", "spec", "metadata", "policyName", "ownerName", "thumbnails");
        assertThat(result.toString()).doesNotContain("SECRET").doesNotContain("999");
        assertThat(result).containsEntry("total", 1).containsEntry("scanTruncated", false);
    }

    @Test
    void filtersUnsafeAndDeletedAttachmentsBeforePaginatingKnownMatches() {
        var deleted = attachment("deleted", "/upload/deleted.png");
        deleted.getMetadata().setDeletionTimestamp(Instant.now());
        attachments(List.of(attachment("a", "/upload/a.png"),
            attachment("signed", "https://cdn.example.org/private.png?X-Amz-Signature=secret"),
            deleted, attachment("b", "/upload/b.png"), attachment("c", "/upload/c.png")));
        var result = tools.execute("search_attachments", Map.of("page", 2, "size", 1,
            "keyword", "photo", "mediaType", "image/*")).block();
        assertThat(items(result)).extracting(item -> item.get("name")).containsExactly("b");
        assertThat(result).containsEntry("hasNext", true).containsEntry("total", 3);
        var options = ArgumentCaptor.forClass(ListOptions.class);
        var pagination = ArgumentCaptor.forClass(PageRequest.class);
        verify(client).listBy(eq(Attachment.class), options.capture(), pagination.capture());
        assertThat(options.getValue().toString()).contains("spec.mediaType", "image/", "spec.displayName",
            "photo", "metadata.deletionTimestamp");
        assertThat(pagination.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void boundsCandidateScanAndDoesNotClaimAnExactTotalOrKnownNextPage() {
        when(client.listBy(eq(Attachment.class), any(), any())).thenAnswer(invocation -> {
            PageRequest request = invocation.getArgument(2);
            var candidates = IntStream.range(0, 100)
                .mapToObj(i -> attachment(request.getPageNumber() + "-" + i, "/upload/a.png")).toList();
            return Mono.just(new ListResult<>(request.getPageNumber(), 100, 1500, candidates));
        });
        var result = tools.execute("search_attachments", Map.of("page", 21, "size", 50)).block();
        assertThat(items(result)).isEmpty();
        assertThat(result).containsEntry("scanTruncated", true).containsEntry("truncated", true)
            .containsEntry("scanned", 1000).containsEntry("matchedInScan", 1000)
            .containsEntry("total", null).containsEntry("hasNext", false);
        verify(client, times(10)).listBy(eq(Attachment.class), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "data:image/png;base64,xx", "file:///tmp/a.png",
        "//evil.example/a.png", "/\\evil.example/a.png", "https://user:pass@example.org/a.png",
        "https://example.org/a.png?token=secret", "https://example.org/a.png?width=100",
        "/upload/a.png#secret", "/upload/../private/a.png", "/upload/%2e%2e/private.png",
        "/upload/%252e%252e/private.png", "/%2fexample.org/a.png", "/upload/%0asecret.png",
        "https:///missing-host.png", "https://example.org"})
    void rejectsUnsafeOrNonPersistentUrls(String url) {
        assertThat(ContentReferenceTools.safeUrl(url)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/upload/photo.png", "https://cdn.example.org/a%20b.png",
        "http://localhost:8090/upload/test.png", "http://[::1]:8090/upload/test.png"})
    void acceptsStoredPermanentResourceCandidatesWithoutMakingNetworkRequests(String url) {
        assertThat(ContentReferenceTools.safeUrl(url)).isTrue();
    }

    @Test
    void rejectsInvalidPaginationAndUnavailableDimensionFiltersBeforeQuerying() {
        for (var args : List.<Map<String, Object>>of(Map.of("page", 0), Map.of("size", 51),
            Map.of("page", "1"), Map.of("size", 1.5), Map.of("mediaType", "image"), Map.of("minWidth", 100))) {
            assertThatThrownBy(() -> tools.execute("search_attachments", args).block())
                .isInstanceOf(IllegalArgumentException.class);
        }
        for (var args : List.<Map<String, Object>>of(Map.of("offset", -1), Map.of("limit", 16385),
            Map.of("limit", 2.5))) {
            var arguments = new java.util.HashMap<>(args);
            arguments.put("type", "Post");
            arguments.put("name", "post");
            assertThatThrownBy(() -> tools.execute("read_content", arguments).block())
                .isInstanceOf(IllegalArgumentException.class);
        }
        verify(client, never()).listBy(eq(Attachment.class), any(), any());
        verify(client, never()).fetch(eq(Post.class), any());
        assertThat(ContentReferenceTools.definitions()).extracting(PageReferenceTools.Definition::name)
            .containsExactly("read_content", "search_attachments");
    }

    private Map<String, Object> read(String type, int offset, int limit) {
        return tools.execute("read_content", Map.of("type", type, "name", "post", "offset", offset, "limit", limit)).block();
    }

    private void attachments(List<Attachment> attachments) {
        when(client.listBy(eq(Attachment.class), any(), any()))
            .thenReturn(Mono.just(new ListResult<>(1, 100, attachments.size(), attachments)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("items");
    }

    private static Metadata metadata(String name) {
        var metadata = new Metadata();
        metadata.setName(name);
        metadata.setLabels(Map.of(Post.PUBLISHED_LABEL, "true"));
        return metadata;
    }

    private static Post post() {
        var post = new Post();
        post.setMetadata(metadata("post"));
        post.setSpec(new Post.PostSpec());
        post.getSpec().setVisible(Post.VisibleEnum.PUBLIC);
        post.getSpec().setDeleted(false);
        post.getSpec().setBaseSnapshot("base");
        post.getSpec().setReleaseSnapshot("release");
        post.getSpec().setHeadSnapshot("head");
        post.setStatus(new Post.PostStatus());
        post.getStatus().setPermalink("http://localhost:8090/posts/post");
        return post;
    }

    private static SinglePage page() {
        var page = new SinglePage();
        page.setMetadata(metadata("post"));
        page.setSpec(new SinglePage.SinglePageSpec());
        page.getSpec().setVisible(Post.VisibleEnum.PUBLIC);
        page.getSpec().setDeleted(false);
        page.getSpec().setBaseSnapshot("base");
        page.getSpec().setReleaseSnapshot("release");
        page.getSpec().setHeadSnapshot("head");
        page.setStatus(new SinglePage.SinglePageStatus());
        page.getStatus().setPermalink("/about");
        return page;
    }

    private static Snapshot snapshot(String name, SinglePage page, boolean base, String body) {
        var snapshot = new Snapshot();
        snapshot.setMetadata(metadata(name));
        snapshot.getMetadata().setAnnotations(Map.of(Snapshot.KEEP_RAW_ANNO, String.valueOf(base)));
        snapshot.setSpec(new Snapshot.SnapShotSpec());
        snapshot.getSpec().setSubjectRef(Ref.of(page));
        snapshot.getSpec().setRawType("html");
        snapshot.getSpec().setRawPatch(body);
        snapshot.getSpec().setContentPatch(body);
        return snapshot;
    }

    private static Attachment attachment(String name, String url) {
        var attachment = new Attachment();
        attachment.setMetadata(metadata(name));
        attachment.setSpec(new Attachment.AttachmentSpec());
        attachment.getSpec().setDisplayName("photo " + name);
        attachment.getSpec().setMediaType("image/png");
        attachment.getSpec().setSize(100L);
        attachment.setStatus(new Attachment.AttachmentStatus());
        attachment.getStatus().setPermalink(url);
        return attachment;
    }
}
