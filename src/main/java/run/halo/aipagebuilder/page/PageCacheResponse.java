package run.halo.aipagebuilder.page;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Makes a published page revalidate with its CDN: the ETag hashes the rendered body, so theme
 * and property changes invalidate it without a page republish. Super administrator responses
 * are deliberately not wrapped because they render a different, script-free document.
 */
final class PageCacheResponse implements ServerResponse {
    private final ServerResponse delegate;
    private final Instant lastModified;

    PageCacheResponse(ServerResponse delegate, Instant lastModified) {
        this.delegate = delegate;
        this.lastModified = lastModified;
    }

    @Override
    public HttpStatusCode statusCode() {
        return delegate.statusCode();
    }

    @Override
    public HttpHeaders headers() {
        return delegate.headers();
    }

    @Override
    public MultiValueMap<String, ResponseCookie> cookies() {
        return delegate.cookies();
    }

    @Override
    public Mono<Void> writeTo(ServerWebExchange exchange, Context context) {
        var requestHeaders = exchange.getRequest().getHeaders();
        var response = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                return DataBufferUtils.join(body)
                    .flatMap(buffer -> complete(buffer, requestHeaders));
            }

            @Override
            public Mono<Void> writeAndFlushWith(
                Publisher<? extends Publisher<? extends DataBuffer>> body) {
                return writeWith(Flux.from(body).concatMap(Flux::from));
            }

            private Mono<Void> complete(DataBuffer buffer, HttpHeaders requestHeaders) {
                final byte[] bytes;
                try {
                    bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                } finally {
                    DataBufferUtils.release(buffer);
                }
                var etag = '"' + sha256(bytes) + '"';
                var headers = getHeaders();
                headers.setCacheControl(CacheControl.noCache().getHeaderValue());
                headers.setETag(etag);
                if (lastModified != null) {
                    headers.setLastModified(lastModified);
                }
                if (notModified(requestHeaders, etag)) {
                    headers.remove(HttpHeaders.CONTENT_LENGTH);
                    getDelegate().setStatusCode(HttpStatus.NOT_MODIFIED);
                    return getDelegate().setComplete();
                }
                headers.setContentLength(bytes.length);
                return getDelegate().writeWith(Mono.just(bufferFactory().wrap(bytes)));
            }
        };
        return delegate.writeTo(exchange.mutate().response(response).build(), context);
    }

    private boolean notModified(HttpHeaders requestHeaders, String etag) {
        var ifNoneMatch = requestHeaders.getIfNoneMatch();
        if (!ifNoneMatch.isEmpty()) {
            // RFC 9110: If-None-Match wins over If-Modified-Since even when it does not match.
            return ifNoneMatch.stream().flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .anyMatch(candidate -> "*".equals(candidate) || candidate.equals(etag)
                    || ("W/" + etag).equals(candidate));
        }
        var since = requestHeaders.getIfModifiedSince();
        return lastModified != null && since >= 0
            && lastModified.getEpochSecond() <= since / 1000;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
