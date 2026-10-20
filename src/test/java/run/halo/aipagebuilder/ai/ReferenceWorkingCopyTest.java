package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;
import run.halo.aipagebuilder.theme.ThemeStyleContext;

class ReferenceWorkingCopyTest {
    @Test
    void synchronousArgumentFailureBecomesBoundedErrorAndSummaryOnSubscription() {
        var session = mock(PageReferenceTools.Session.class);
        var copy = copy(session, ThemeStyleContext.unavailable());
        var input = Map.<String, Object>of("size", 0);
        var message = "Invalid pagination ".repeat(30);
        when(session.execute("list_posts", input)).thenThrow(new IllegalArgumentException(message));
        var result = copy.reference("list_posts", input);
        verifyNoInteractions(session);
        assertThat(result.block()).containsEntry("error", message.substring(0, 300));
        assertThat(copy.summaries()).containsExactly(
            new ChatApi.Tool("list_posts", "error", message.substring(0, 300)));
        assertThat(copy.exhausted()).isFalse();
        assertThat(copy.hasInvalidUpdate()).isFalse();
    }

    @Test
    void reactiveExpectedFailuresBecomeErrorsWhileUnexpectedFailuresStillPropagate() {
        for (var error : List.of(new IllegalArgumentException("Invalid path"),
            new IllegalStateException("Active theme changed"),
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Page unavailable"))) {
            var session = mock(PageReferenceTools.Session.class);
            var copy = copy(session, ThemeStyleContext.unavailable());
            when(session.execute(anyString(), any())).thenReturn(Mono.error(error));
            String message = error instanceof ResponseStatusException status
                ? status.getReason() : error.getMessage();
            assertThat(copy.reference("read_ai_page", Map.of("name", "missing")).block())
                .containsEntry("error", message);
            assertThat(copy.summaries()).containsExactly(new ChatApi.Tool("read_ai_page", "error", message));
            assertThat(copy.exhausted()).isFalse();
        }
        var session = mock(PageReferenceTools.Session.class);
        var copy = copy(session, ThemeStyleContext.unavailable());
        var failure = new UnsupportedOperationException("Unexpected backend failure");
        when(session.execute(anyString(), any())).thenReturn(Mono.error(failure));
        assertThatThrownBy(() -> copy.reference("list_posts", Map.of()).block()).isSameAs(failure);
        assertThat(copy.summaries()).isEmpty();
    }

    @Test
    void referenceErrorsAndCurrentPageToolsShareTwentyCallLimit() {
        var session = mock(PageReferenceTools.Session.class);
        var copy = copy(session, ThemeStyleContext.unavailable());
        when(session.execute(anyString(), any())).thenReturn(Mono.error(new IllegalArgumentException("Invalid size")));
        copy.read();
        for (int i = 1; i < RunBudget.MAX_CALLS; i++) {
            assertThat(copy.reference("list_posts", Map.of("size", 0)).block()).containsKey("error");
        }
        assertThat(copy.exhausted()).isFalse();
        assertThatThrownBy(() -> copy.reference("list_posts", Map.of()).block())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("调用预算");
        assertThat(copy.exhausted()).isTrue();
        assertThat(copy.summaries()).hasSize(RunBudget.MAX_CALLS);
        verify(session, times(RunBudget.MAX_CALLS - 1)).execute(anyString(), any());
    }

    @Test
    void oversizedReferenceInputExhaustsBudgetBeforeCallingSession() {
        var session = mock(PageReferenceTools.Session.class);
        var copy = copy(session, ThemeStyleContext.unavailable());
        assertThatThrownBy(() -> copy.reference("list_posts",
            Map.of("keyword", "x".repeat(RunBudget.MAX_BYTES))).block())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("输出预算");
        assertThat(copy.exhausted()).isTrue();
        assertThat(copy.summaries()).isEmpty();
        verifyNoInteractions(session);
    }

    @Test
    void oversizedReferenceOutputCannotBeRecoveredAsToolError() {
        var session = mock(PageReferenceTools.Session.class);
        var copy = copy(session, ThemeStyleContext.unavailable());
        when(session.execute(anyString(), any())).thenReturn(Mono.just(
            Map.of("content", "x".repeat(RunBudget.MAX_BYTES))));
        assertThatThrownBy(() -> copy.reference("read_theme_file", Map.of()).block())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("输出预算");
        assertThat(copy.exhausted()).isTrue();
        assertThat(copy.summaries()).isEmpty();
        assertThatThrownBy(() -> copy.reference("list_posts", Map.of()).block())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("调用预算");
        verify(session, times(1)).execute(anyString(), any());
    }

    @Test
    void recoveredErrorOutputAlsoConsumesTheSharedByteBudget() {
        var session = mock(PageReferenceTools.Session.class);
        var copy = copy(session, ThemeStyleContext.unavailable());
        copy.consume("x".repeat(RunBudget.MAX_BYTES - 2));
        when(session.execute(anyString(), any())).thenReturn(Mono.error(new IllegalArgumentException("Invalid size")));
        assertThatThrownBy(() -> copy.reference("list_posts", Map.of()).block())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("输出预算");
        assertThat(copy.exhausted()).isTrue();
        assertThat(copy.summaries()).isEmpty();
        verify(session).execute("list_posts", Map.of());
    }

    @Test
    void editingBindsContextThemeBeforeReferenceExecutionWithoutAnExtraToolCall() {
        var session = mock(PageReferenceTools.Session.class);
        var context = new ThemeStyleContext("theme-a", "Theme A", "SUPPORTED", List.of(), List.of(), "");
        var copy = copy(session, context);
        when(session.execute(anyString(), any())).thenReturn(Mono.just(Map.of("themeName", "theme-a")));
        assertThat(copy.update(Map.of("html", "<p>Edited</p>")).block()).containsEntry("valid", true);
        verify(session).bindTheme("theme-a");
        assertThat(copy.summaries()).extracting(ChatApi.Tool::name)
            .containsExactly("update_current_page");
        for (int i = 1; i < RunBudget.MAX_CALLS; i++) {
            copy.reference("list_theme_files", Map.of()).block();
        }
        assertThat(copy.exhausted()).isFalse();
        verify(session, times(RunBudget.MAX_CALLS - 1)).execute("list_theme_files", Map.of());
    }

    @Test
    void themeBindingConflictPropagatesAndUnavailableThemeIsNotBound() {
        var session = mock(PageReferenceTools.Session.class);
        var context = new ThemeStyleContext("theme-a", "Theme A", "SUPPORTED", List.of(), List.of(), "");
        var copy = copy(session, context);
        doThrow(new IllegalStateException("Active theme changed")).doNothing()
            .when(session).bindTheme("theme-a");
        assertThatThrownBy(() -> copy.update(Map.of("html", "<p>Edited</p>")).block())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("changed");
        verify(session, never()).execute(anyString(), any());
        assertThat(copy.summaries()).isEmpty();
        assertThat(copy.document().html()).isEqualTo("<p>Original</p>");
        copy.setTitle(Map.of("title", "Independent title")).block();
        assertThat(copy.update(Map.of("html", "<p>Edited</p>")).block()).containsEntry("valid", true);
        verify(session, times(2)).bindTheme("theme-a");
        assertThat(copy.title()).isEqualTo("Independent title");
        var unavailableSession = mock(PageReferenceTools.Session.class);
        copy(unavailableSession, ThemeStyleContext.unavailable())
            .update(Map.of("html", "<p>Edited</p>")).block();
        verifyNoInteractions(unavailableSession);
    }

    private static PageWorkingCopy copy(PageReferenceTools.Session session, ThemeStyleContext context) {
        var copy = new PageWorkingCopy(
            new PageDocument(1, "code", null, null, "<p>Original</p>", ""),
            new ContentPolicy(), Mono.just(context));
        copy.references(session);
        return copy;
    }
}
