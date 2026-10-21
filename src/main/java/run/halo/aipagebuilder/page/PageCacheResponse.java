package run.halo.aipagebuilder.page;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
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
 * Lets a CDN revalidate a published page against the rendered body. The ETag covers theme and
 * property changes that happen without a page republish. Only anonymous responses are wrapped:
 * authenticated pages can render user-specific theme markup and must not be shared.
 */
final class PageCacheResponse implements ServerResponse {
    private final ServerResponse delegate;

    PageCacheResponse(ServerResponse delegate) {
        this.delegate = delegate;
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
                var etag = '"' + sha256(buffer) + '"';
                var headers = getHeaders();
                headers.setCacheControl(CacheControl.noCache().getHeaderValue());
                // The page timestamp does not track theme changes, so date validation is unsafe.
                headers.remove(HttpHeaders.LAST_MODIFIED);
                headers.setETag(etag);
                if (notModified(requestHeaders, etag)) {
                    headers.remove(HttpHeaders.CONTENT_LENGTH);
                    DataBufferUtils.release(buffer);
                    getDelegate().setStatusCode(HttpStatus.NOT_MODIFIED);
                    return getDelegate().setComplete();
                }
                headers.setContentLength(buffer.readableByteCount());
                return getDelegate().writeWith(Mono.just(buffer));
            }
        };
        return delegate.writeTo(exchange.mutate().response(response).build(), context);
    }

    private static boolean notModified(HttpHeaders requestHeaders, String etag) {
        final List<String> candidates;
        try {
            candidates = requestHeaders.getIfNoneMatch();
        } catch (IllegalArgumentException malformed) {
            // Malformed validators must be ignored, not fail the already rendered response.
            return false;
        }
        return candidates.stream().anyMatch(candidate -> "*".equals(candidate)
            || candidate.equals(etag) || ("W/" + etag).equals(candidate));
    }

    private static String sha256(DataBuffer buffer) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(buffer.asByteBuffer(buffer.readPosition(), buffer.readableByteCount()));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
