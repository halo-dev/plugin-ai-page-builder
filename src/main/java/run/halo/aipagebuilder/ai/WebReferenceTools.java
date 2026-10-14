package run.halo.aipagebuilder.ai;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import run.halo.app.infra.utils.HttpSecurityUtils;

/** Public web references only; never forwards console credentials or executes page scripts. */
@Service
public class WebReferenceTools {
    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    static final int MAX_TEXT_CHARS = 16000;
    private final WebClient client;

    @Autowired
    public WebReferenceTools() {
        this(WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(
                HttpSecurityUtils.secureHttpClient().compress(true)))
            .filter(HttpSecurityUtils.maxResponseSizeFilter(MAX_BODY_BYTES))
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_BODY_BYTES))
            .defaultHeader("User-Agent", "Halo-AI-Page-Builder/1.0")
            .defaultHeader("Accept", "text/html, application/xhtml+xml, text/plain")
            .build());
    }

    WebReferenceTools(WebClient client) {
        this.client = client;
    }

    public Mono<Map<String, Object>> execute(Map<String, Object> arguments) {
        return Mono.defer(() -> {
            if (!(arguments.get("url") instanceof String url)) {
                return Mono.error(new IllegalArgumentException("url is required"));
            }
            return fetch(validateUrl(url), 0);
        }).timeout(Duration.ofSeconds(15))
            .onErrorMap(error -> !(error instanceof IllegalArgumentException),
                error -> new IllegalArgumentException("Unable to read web page: request failed, timed out, or exceeded the size limit"));
    }

    private Mono<Map<String, Object>> fetch(URI uri, int redirects) {
        return client.get().uri(uri).exchangeToMono(response -> {
            int status = response.statusCode().value();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                var location = response.headers().asHttpHeaders().getLocation();
                return response.releaseBody().then(Mono.defer(() -> {
                    if (location == null || redirects >= 3) {
                        return Mono.error(new IllegalArgumentException("Missing location or too many redirects"));
                    }
                    return fetch(validateUrl(uri.resolve(location).toString()), redirects + 1);
                }));
            }
            if (!response.statusCode().is2xxSuccessful()) {
                return response.releaseBody().then(Mono.error(
                    new IllegalArgumentException("Web page returned HTTP " + status)));
            }
            var type = response.headers().contentType().orElse(MediaType.APPLICATION_OCTET_STREAM);
            boolean html = MediaType.TEXT_HTML.isCompatibleWith(type)
                || MediaType.APPLICATION_XHTML_XML.isCompatibleWith(type);
            if (!html && !MediaType.TEXT_PLAIN.isCompatibleWith(type)) {
                return response.releaseBody().then(Mono.error(
                    new IllegalArgumentException("Only HTML and plain-text web pages are supported")));
            }
            if (response.headers().contentLength().orElse(0) > MAX_BODY_BYTES) {
                return response.releaseBody().then(Mono.error(
                    new IllegalArgumentException("Web page exceeds the 2 MiB limit")));
            }
            return response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0])
                .map(body -> extract(uri, type, body, html));
        });
    }

    static URI validateUrl(String url) {
        if (url.isBlank() || url.length() > 2048 || url.contains("\\")) {
            throw new IllegalArgumentException("A valid HTTP(S) URL is required");
        }
        var uri = URI.create(url.trim());
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
            || uri.getHost() == null || uri.getRawUserInfo() != null
            || uri.getHost().contains("%")
            || (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)) {
            throw new IllegalArgumentException("Only public HTTP(S) URLs on ports 80 and 443 are supported");
        }
        var host = uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
        if (host.equals("localhost") || host.endsWith(".localhost")
            || host.endsWith(".local")) {
            throw new IllegalArgumentException("Local and private network addresses are not allowed");
        }
        // Fragments are local browser navigation, not part of the fetched resource.
        return URI.create(uri.toString().split("#", 2)[0]);
    }

    private static Map<String, Object> extract(URI uri, MediaType type, byte[] body, boolean html) {
        String title = "";
        String description = "";
        String text;
        try {
            if (html) {
                var document = Jsoup.parse(new ByteArrayInputStream(body),
                    type.getCharset() == null ? null : type.getCharset().name(), uri.toString());
                title = document.title();
                var meta = document.selectFirst("meta[name=description]");
                description = meta == null ? "" : meta.attr("content");
                document.select("script, style, noscript, template, iframe, svg, [hidden], [aria-hidden=true]").remove();
                var main = document.selectFirst("main, article, [role=main]");
                text = (main == null ? document.body() : main).text();
            } else {
                text = new String(body, type.getCharset() == null ? StandardCharsets.UTF_8 : type.getCharset());
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Unable to parse web page", e);
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("No readable text found; the page may require JavaScript or login");
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("url", uri.toString());
        result.put("title", clip(title, 500));
        result.put("description", clip(description, 1000));
        result.put("text", clip(text, MAX_TEXT_CHARS));
        result.put("truncated", text.length() > MAX_TEXT_CHARS);
        result.put("sourceTrust", "untrusted_external_content");
        return result;
    }

    private static String clip(String value, int limit) {
        return value.substring(0, Math.min(value.length(), limit));
    }
}
