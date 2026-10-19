package run.halo.aipagebuilder.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.aipagebuilder.Json;
import run.halo.aipagebuilder.ai.DocumentHash;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;
import run.halo.aipagebuilder.page.PageApi;
import run.halo.aipagebuilder.page.PagePreviewStore;
import run.halo.aipagebuilder.page.PageService;
import run.halo.aipagebuilder.theme.ThemeContextService;
import run.halo.aipagebuilder.theme.ThemeContextExtractor;
import run.halo.aipagebuilder.theme.ThemeStyleContext;
import run.halo.mcpserver.api.*;

class PageMcpToolProviderTest {
    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(
        SpecificationVersion.DRAFT_2020_12);
    private final PageService pages = mock(PageService.class);
    private final ContentPolicy policy = new ContentPolicy();
    private final ThemeContextService themes = mock(ThemeContextService.class);
    private final PagePreviewStore previews = new PagePreviewStore();
    private final ExternalUrlSupplier urls = mock(ExternalUrlSupplier.class);
    private final PageMcpToolProvider provider = new PageMcpToolProvider(pages, policy, themes,
        previews, urls);
    private PageApi.PageDetail current;

    @BeforeEach
    void setup() throws Exception {
        current = detail(new PageDocument(1, "visual", "editor", Map.of("components", List.of()),
            "<h1>Before</h1>", "h1 {color: red}", "Title", "Description"));
        when(pages.get("page")).thenReturn(Mono.just(current));
        when(pages.save(eq("page"), any(), eq("key-owner")))
            .thenAnswer(call -> Mono.just(detail(call.<PageApi.SavePageRequest>getArgument(1)
                .document())));
        when(themes.current()).thenReturn(Mono.just(ThemeStyleContext.unavailable()));
        when(urls.getRaw()).thenReturn(URI.create("https://example.test/site/").toURL());
    }

    @Test
    void declaresTwelveStrictSchemasAndPermissions() {
        var tools = provider.tools().collectList().block();
        assertThat(tools).hasSize(12);
        assertThat(tools).extracting(McpToolDefinition::name).doesNotHaveDuplicates();
        for (var tool : tools) {
            assertThat(tool.inputSchema()).containsEntry("additionalProperties", false)
                .containsEntry("type", "object");
            assertThat(tool.outputSchema()).containsEntry("additionalProperties", false)
                .containsEntry("type", "object");
            assertThat(SCHEMAS.getSchema(SchemaLocation.of(
                "https://json-schema.org/draft/2020-12/schema"))
                .validate(Json.MAPPER.valueToTree(tool.outputSchema())))
                .as("Valid output schema for %s", tool.name()).isEmpty();
            assertThat(tool.displayDescription()).isNotBlank().isNotEqualTo(tool.displayTitle());
            assertThat(tool.permission().check(new McpToolInvocation(tool.name(),
                Map.of())).block())
                .isFalse();
            assertThat(tool.permission().check(new McpToolInvocation(tool.name(), Map.of()))
                .contextWrite(identity("ROLE_super-role")).block()).isTrue();
        }
        assertThat(tool("get_page").annotations().readOnlyHint()).isTrue();
        assertThat(tool("publish_page").annotations().destructiveHint()).isTrue();
        assertThat(tool("preview_page").annotations().readOnlyHint()).isFalse();
    }

    @Test
    void capabilitiesAndThemeOutputsConformIncludingNestedSourcesAndRootAttributes() {
        assertThat(call("get_page_capabilities", Map.of()).error()).isFalse();
        assertThat(call("get_theme_context", Map.of()).structuredContent())
            .doesNotContainKeys("themeName", "displayName");
        when(themes.current()).thenReturn(Mono.just(new ThemeStyleContext("theme", "Theme",
            "SUPPORTED", List.of(new ThemeContextExtractor.Source("style.css", "css", "html{}")),
            List.of("Read as data"), "html{}", new ThemeStyleContext.ColorMode("explicit",
                List.of(ThemeStyleContext.RootState.fromSelector("html[data-theme=dark]")),
                List.of("style.css")))));
        assertThat(call("get_theme_context", Map.of()).error()).isFalse();
        var p = current.page();
        when(pages.get("page")).thenReturn(Mono.just(new PageApi.PageDetail(new PageApi.PageSummary(
            p.name(), p.slug(), p.title(), p.description(), p.version(), false, p.draftRevision(),
            p.publishedRevision(), p.updatedAt(), p.hasUnpublishedChanges()), current.document())));
        assertThat(call("get_theme_context", Map.of("name", "page")).structuredContent())
            .containsEntry("layoutState", "STANDALONE");
    }

    @Test
    void outputsAllowOmittedNullsAndEmptyPagesWithoutLeakingEditorData() {
        var document = new PageDocument(1, "code", null, null, "", "", "Title", null);
        var page = new PageApi.PageSummary("page", null, "Title", null, 4L, true, "draft", null,
            Instant.now(), true);
        when(pages.get("page")).thenReturn(Mono.just(new PageApi.PageDetail(page, document)));
        when(pages.create(any(), any())).thenReturn(Mono.just(new PageApi.PageDetail(page,
            document)));
        when(urls.getRaw()).thenReturn(null);
        var read = call("get_page", Map.of("name", "page")).structuredContent();
        assertThat(read).doesNotContainKeys("slug", "description", "publishedRevision",
            "publicPath",
            "publicUrl");
        assertThat(((Map<?, ?>) read.get("document")).get("description")).isEqualTo("");
        assertThat(call("create_page", Map.of("title", "Title", "slug", "new")).error()).isFalse();
        when(pages.list(1, 20, null, null, null)).thenReturn(Mono.just(
            new PageApi.PageList(List.of(page, current.page()), 2, 1, 20)));
        assertThat(call("list_pages", Map.of()).error()).isFalse();
        when(pages.list(2, 20, null, null, null)).thenReturn(Mono.just(
            new PageApi.PageList(List.of(), 2, 2, 20)));
        assertThat(call("list_pages", Map.of("page", 2)).error()).isFalse();
        when(pages.revisions("page", 1, 20)).thenReturn(Mono.just(new PageService.RevisionList(
            List.of(new PageApi.RevisionSummary("first", Instant.now(), "owner", "ai", "Title",
                null),
                new PageApi.RevisionSummary("second", Instant.now(), "owner", "manual",
                    "Title", "first")),
            2, 1, 20)));
        assertThat(call("list_page_revisions", Map.of("name", "page")).error()).isFalse();
        when(pages.revisions("page", 2, 20)).thenReturn(Mono.just(
            new PageService.RevisionList(List.of(), 2, 2, 20)));
        assertThat(call("list_page_revisions", Map.of("name", "page", "page",
            2)).error()).isFalse();
    }

    @Test
    void outputSchemaRejectsMissingFieldsWrongTypesAndUnexpectedNestedProperties() {
        var valid = call("get_page", Map.of("name", "page")).structuredContent();
        var schema = SCHEMAS.getSchema(Json.MAPPER.valueToTree(tool("get_page").outputSchema()));
        for (var field : List.of("version", "document", "baseHash")) {
            var invalid = new LinkedHashMap<>(valid);
            invalid.remove(field);
            assertThat(schema.validate(Json.MAPPER.valueToTree(invalid))).isNotEmpty();
        }
        var invalid = new LinkedHashMap<>(valid);
        invalid.put("version", "4");
        assertThat(schema.validate(Json.MAPPER.valueToTree(invalid))).isNotEmpty();
        invalid = new LinkedHashMap<>(valid);
        invalid.put("document", Map.of("title", "Title", "description", "", "html", "", "css", "",
            "js", "", "projectData", Map.of()));
        assertThat(schema.validate(Json.MAPPER.valueToTree(invalid))).isNotEmpty();
        invalid = new LinkedHashMap<>(valid);
        invalid.put("publishedRevision", null);
        // Json.MAPPER omits nulls; use a plain mapper to exercise an explicit null wire value.
        assertThat(schema.validate(tools.jackson.databind.json.JsonMapper.builder().build()
            .valueToTree(invalid))).isNotEmpty();
        invalid = new LinkedHashMap<>(valid);
        invalid.put("unexpected", true);
        assertThat(schema.validate(Json.MAPPER.valueToTree(invalid))).isNotEmpty();
    }

    @Test
    void optionalConfigurationDoesNotLoadWithoutMcpOrRequireAiFoundation() {
        var context = new ApplicationContextRunner()
            .withUserConfiguration(PageMcpConfiguration.class)
            .withBean(PageService.class, () -> pages).withBean(ContentPolicy.class, () -> policy)
            .withBean(ThemeContextService.class, () -> themes)
            .withBean(PagePreviewStore.class,
                () -> previews).withBean(ExternalUrlSupplier.class, () -> urls);
        context.withClassLoader(new FilteredClassLoader("run.halo.mcpserver.api"))
            .run(value -> assertThat(value).hasNotFailed().doesNotHaveBean("pageMcpToolProvider"));
        context.withClassLoader(new FilteredClassLoader("run.halo.aifoundation"))
            .run(value -> assertThat(value).hasNotFailed()
                .hasSingleBean(PageMcpToolProvider.class));
    }

    @Test
    void handlerAlsoRejectsNonAdminIdentity() {
        assertThatThrownBy(() -> tool("get_page").handler()
            .execute(new McpToolInvocation("get_page", Map.of("name", "page")))
            .contextWrite(identity("ROLE_authenticated")).block())
            .isInstanceOf(McpToolException.class);
        verifyNoInteractions(pages);
    }

    @Test
    void readOmitsVisualProjectAndReturnsSelectedSourceHash() {
        var result = call("get_page", Map.of("name", "page"));
        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent()).containsEntry("mode", "visual")
            .containsEntry("revision", "draft").containsEntry("publicUrl",
                "https://example.test/site/about");
        var source = (Map<?, ?>) result.structuredContent().get("document");
        assertThat(source.containsKey("projectData")).isFalse();
        assertThat(source.containsKey("editorVersion")).isFalse();
        assertThat(source.get("html")).isEqualTo("<h1>Before</h1>");
        assertThat(result.structuredContent().get("baseHash"))
            .isEqualTo(DocumentHash.of(current.document().html(), current.document().css(), ""));
    }

    @Test
    void publishedAndHistoricalReadsDoNotReturnDraftSource() {
        var old = new PageDocument(1, "code", null, null, "<p>Old</p>", "", "Old", "");
        when(pages.revisionDetail("page", "live")).thenReturn(Mono.just(
            new PageApi.RevisionDetail(null, old)));
        when(pages.revisionDetail("page", "history")).thenReturn(Mono.just(
            new PageApi.RevisionDetail(null, old)));
        for (var args : List.of(Map.<String, Object>of("name", "page", "target", "published"),
            Map.<String, Object>of("name", "page", "target", "revision", "revision", "history"))) {
            var result = call("get_page", args);
            assertThat(result.error()).isFalse();
            assertThat(((Map<?, ?>) result.structuredContent().get("document")).get("html"))
                .isEqualTo("<p>Old</p>");
        }
        assertError(call("get_page", Map.of("name", "page", "revision", "history")),
            "INVALID_ARGUMENT");
    }

    @Test
    void sourceUpdateConvertsVisualPageAndPreservesOmittedFields() {
        var result = call("update_page", Map.of("name", "page", "expectedVersion", 4, "html",
            "<p>New</p>"));
        assertThat(result.error()).isFalse();
        var saved = savedDocument();
        assertThat(saved.mode()).isEqualTo("code");
        assertThat(saved.projectData()).isNull();
        assertThat(saved.css()).isEqualTo(current.document().css());
        assertThat(saved.title()).isEqualTo("Title");
        assertThat(saved.description()).isEqualTo("Description");
        assertThat(result.structuredContent()).doesNotContainKey("document");
        verifyNoInteractions(themes);
    }

    @Test
    void metadataAndNoOpEditsPreserveVisualState() {
        call("update_page", Map.of("name", "page", "expectedVersion", 4, "description", ""));
        assertThat(savedDocument().mode()).isEqualTo("visual");
        assertThat(savedDocument().projectData()).isEqualTo(current.document().projectData());
        assertThat(savedDocument().description()).isEmpty();
        clearInvocations(pages);
        call("update_page", Map.of("name", "page", "expectedVersion", 4, "html",
            "<h1>Before</h1>"));
        assertThat(savedDocument()).isEqualTo(current.document());
    }

    @Test
    void scriptsUseSchemaTwoAndInvalidContentNeverSaves() {
        call("update_page", Map.of("name", "page", "expectedVersion", 4, "js",
            "console.log('ok')"));
        assertThat(savedDocument().schemaVersion()).isEqualTo(2);
        clearInvocations(pages);
        assertError(call("update_page", Map.of("name", "page", "expectedVersion", 4,
            "html", "<script>alert(1)</script>")), "INVALID_ARGUMENT");
        assertError(call("update_page", Map.of("name", "page", "expectedVersion", 4)),
            "INVALID_ARGUMENT");
        verify(pages, never()).save(any(), any(), any());
    }

    @Test
    void patchRequiresDraftHashAndPreservesOtherFields() {
        var args = Map.<String, Object>of("name", "page", "expectedVersion", 4,
            "baseHash", DocumentHash.of(current.document().html(), current.document().css(), ""),
            "patches", List.of(Map.of("field", "html", "oldText", "Before", "newText", "After")));
        assertThat(call("patch_page", args).error()).isFalse();
        assertThat(savedDocument().html()).isEqualTo("<h1>After</h1>");
        clearInvocations(pages);
        var stale = new java.util.HashMap<>(args);
        stale.put("baseHash", "0".repeat(64));
        assertError(call("patch_page", stale), "CONFLICT");
        stale.put("baseHash", args.get("baseHash"));
        stale.put("patches", List.of(Map.of("field", "html", "oldText", "absent", "newText", "")));
        assertError(call("patch_page", stale), "INVALID_ARGUMENT");
        verify(pages, never()).save(any(), any(), any());
    }

    @Test
    void changedCssUsesThemeContractButStandaloneDoesNot() {
        when(themes.current()).thenReturn(Mono.just(new ThemeStyleContext("theme", "Theme",
            "SUPPORTED",
            List.of(), List.of(), "", new ThemeStyleContext.ColorMode("explicit", List.of(),
                List.of()))));
        var args = Map.<String, Object>of("name", "page", "expectedVersion", 4,
            "css", "@media (prefers-color-scheme: dark) { h1 {color: white} }");
        assertError(call("update_page", args), "INVALID_ARGUMENT");
        verify(pages, never()).save(any(), any(), any());
        var p = current.page();
        when(pages.get("page")).thenReturn(Mono.just(new PageApi.PageDetail(new PageApi.PageSummary(
            p.name(), p.slug(), p.title(), p.description(), p.version(), false, p.draftRevision(),
            p.publishedRevision(), p.updatedAt(), p.hasUnpublishedChanges()), current.document())));
        clearInvocations(themes);
        assertThat(call("update_page", args).error()).isFalse();
        verifyNoInteractions(themes);
    }

    @Test
    void staleVersionAndServiceConflictsAreNeverRetried() {
        assertError(call("update_page", Map.of("name", "page", "expectedVersion", 3,
            "title", "New")), "CONFLICT");
        verify(pages, never()).save(any(), any(), any());
        when(pages.save(any(), any(), any())).thenReturn(Mono.error(
            new ResponseStatusException(HttpStatus.CONFLICT, "Changed")));
        assertError(call("update_page", Map.of("name", "page", "expectedVersion", 4,
            "title", "New")), "CONFLICT");
        verify(pages, times(1)).save(any(), any(), any());
    }

    @Test
    void creationDerivesActorAndNeverPublishes() {
        when(pages.create(any(), eq("key-owner"))).thenReturn(Mono.just(current));
        assertThat(call("create_page", Map.of("title", "New", "slug", "new")).error()).isFalse();
        verify(pages).create(new PageApi.CreatePageRequest("new", "New", true), "key-owner");
        verify(pages, never()).publish(any(), any(), any());
    }

    @Test
    void publishUnpublishAndRestoreUseVersionedServiceBoundaries() {
        when(pages.publish("page", 4L, "draft")).thenReturn(Mono.just(current));
        when(pages.unpublish("page", 4L)).thenReturn(Mono.just(current));
        when(pages.restore(eq("page"), any(), eq("key-owner"))).thenReturn(Mono.just(current));
        assertThat(call("publish_page", draftArgs()).error()).isFalse();
        assertThat(call("unpublish_page", Map.of("name", "page", "expectedVersion",
            4)).error()).isFalse();
        assertThat(call("restore_page_revision", Map.of("name", "page", "expectedVersion", 4,
            "revision", "history")).error()).isFalse();
        verify(pages).publish("page", 4L, "draft");
        verify(pages).unpublish("page", 4L);
        verify(pages).restore("page", new PageApi.RestoreRequest(4L, "history"), "key-owner");
        verify(pages, times(1)).publish(any(), any(), any());
    }

    @Test
    void previewRequiresExactDraftAndAbsoluteConfiguredBase() {
        var result = call("preview_page", draftArgs());
        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent().get("previewUrl").toString())
            .startsWith("https://example.test/site/preview/ai-page-builder/");
        assertError(call("preview_page", Map.of("name", "page", "expectedVersion", 4,
            "expectedDraftRevision", "old")), "CONFLICT");
        when(urls.getRaw()).thenReturn(null);
        assertThatThrownBy(() -> call("preview_page",
            draftArgs())).isInstanceOf(McpToolException.class)
            .hasMessageContaining("external URL");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404, 409, 500})
    void mapsHttpErrorsWithoutLeakingServerErrors(int status) {
        when(pages.get("page")).thenReturn(Mono.error(new ResponseStatusException(
            HttpStatus.valueOf(status), "internal details")));
        var result = call("get_page", Map.of("name", "page"));
        assertError(result, Map.of(400, "INVALID_ARGUMENT", 403, "FORBIDDEN", 404, "NOT_FOUND",
            409, "CONFLICT", 500, "INTERNAL_ERROR").get(status));
        if (status == 500) assertThat(result.textContent()).doesNotContain("internal details");
    }

    private PageDocument savedDocument() {
        var captor = ArgumentCaptor.forClass(PageApi.SavePageRequest.class);
        verify(pages).save(eq("page"), captor.capture(), eq("key-owner"));
        assertThat(captor.getValue().source()).isEqualTo("ai");
        return captor.getValue().document();
    }

    private static PageApi.PageDetail detail(PageDocument document) {
        return new PageApi.PageDetail(new PageApi.PageSummary("page", "about", document.title(),
            document.description(), 4L, true, "draft", "live", Instant.now(), true), document);
    }

    private static Map<String, Object> draftArgs() {
        return Map.of("name", "page", "expectedVersion", 4, "expectedDraftRevision", "draft");
    }

    private McpToolDefinition tool(String name) {
        return provider.tools().filter(tool -> tool.name().equals(name)).blockFirst();
    }

    private McpToolResult call(String name, Map<String, Object> args) {
        var definition = tool(name);
        var result = definition.handler().execute(new McpToolInvocation(name, args))
            .contextWrite(identity("ROLE_super-role")).block();
        assertThat(result).isNotNull();
        if (!result.error()) {
            assertThat(SCHEMAS.getSchema(Json.MAPPER.valueToTree(definition.outputSchema()))
                .validate(Json.MAPPER.valueToTree(result.structuredContent())))
                .as("Output of %s conforms to its schema", name).isEmpty();
        }
        return result;
    }

    private static reactor.util.context.Context identity(String role) {
        return ReactiveSecurityContextHolder.withAuthentication(new
            UsernamePasswordAuthenticationToken(
            "key-owner", "unused", AuthorityUtils.createAuthorityList(role)));
    }

    private static void assertError(McpToolResult result, String code) {
        assertThat(result.error()).isTrue();
        assertThat(((Map<?,
            ?>) result.structuredContent().get("error")).get("code")).isEqualTo(code);
    }
}
