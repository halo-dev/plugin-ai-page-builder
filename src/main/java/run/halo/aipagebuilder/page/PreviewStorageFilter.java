package run.halo.aipagebuilder.page;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.security.AdditionalWebFilter;

/**
 * Injects the preview storage shim into rendered preview documents. The document body is
 * produced by the theme view, so it is rewritten here rather than in the renderer. Only HTML
 * responses for the two preview routes are touched; errors and other routes pass through.
 */
@Component
public class PreviewStorageFilter implements AdditionalWebFilter {
    private static final Pattern PATH = Pattern.compile(
        "/apis/console\\.api\\.aipagebuilder\\.halo\\.run/v1alpha1/pages/[^/]+/"
            + "(?:revisions/[^/]+/)?preview");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        var path = request.getPath().pathWithinApplication().value();
        if (!HttpMethod.GET.equals(request.getMethod()) || !PATH.matcher(path).matches()) {
            return chain.filter(exchange);
        }
        return chain.filter(exchange.mutate()
            .response(new InjectingResponse(exchange.getResponse())).build());
    }

    private static final class InjectingResponse extends ServerHttpResponseDecorator {
        InjectingResponse(ServerHttpResponse delegate) {
            super(delegate);
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return DataBufferUtils.join(body).flatMap(buffer -> {
                var type = getHeaders().getContentType();
                if (buffer.readableByteCount() == 0 || type == null
                    || !MediaType.TEXT_HTML.isCompatibleWith(type)) {
                    return super.writeWith(Mono.just(buffer));
                }
                final String html;
                try {
                    html = buffer.toString(StandardCharsets.UTF_8);
                } finally {
                    DataBufferUtils.release(buffer);
                }
                var bytes = PreviewStorageShim.inject(html).getBytes(StandardCharsets.UTF_8);
                getHeaders().setContentLength(bytes.length);
                return super.writeWith(Mono.just(bufferFactory().wrap(bytes)));
            });
        }

        @Override
        public Mono<Void> writeAndFlushWith(
            Publisher<? extends Publisher<? extends DataBuffer>> body) {
            return writeWith(Flux.from(body).concatMap(Flux::from));
        }
    }
}
