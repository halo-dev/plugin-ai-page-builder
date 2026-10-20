package run.halo.aipagebuilder.page;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.jsoup.Jsoup;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Keep the bearer URL in a script-free host, outside the untrusted preview document. */
record PagePreviewResponse(ServerResponse delegate, URI documentUri, String childPolicy)
    implements ServerResponse {
    static ServerRequest withoutCredential(ServerRequest request) {
        var uri = UriComponentsBuilder.fromUri(request.uri())
            .replacePath(request.requestPath().contextPath().value() + "/")
            .replaceQuery(null).fragment(null).build(true).toUri();
        return ServerRequest.create(withoutCredential(request.exchange(), uri),
            request.messageReaders());
    }

    private static ServerWebExchange withoutCredential(ServerWebExchange exchange, URI uri) {
        var attributes = new HashMap<>(exchange.getAttributes());
        // Template helpers can also obtain the original URI from cached routing attributes.
        attributes.remove(RouterFunctions.REQUEST_ATTRIBUTE);
        attributes.remove(RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        attributes.remove(RouterFunctions.MATCHING_PATTERN_ATTRIBUTE);
        attributes.remove(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        var safe = exchange.mutate().request(request -> request.uri(uri)
            .headers(headers -> headers.remove(HttpHeaders.REFERER))).build();
        return new ServerWebExchangeDecorator(safe) {
            @Override
            public Map<String, Object> getAttributes() {
                return attributes;
            }
        };
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
        var response = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                return DataBufferUtils.join(body).flatMap(buffer -> {
                    final String html;
                    try {
                        html = buffer.toString(StandardCharsets.UTF_8);
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                    var bytes = host(html, documentUri, childPolicy)
                        .getBytes(StandardCharsets.UTF_8);
                    getHeaders().setContentLength(bytes.length);
                    return super.writeWith(Mono.just(bufferFactory().wrap(bytes)));
                });
            }

            @Override
            public Mono<Void> writeAndFlushWith(
                Publisher<? extends Publisher<? extends DataBuffer>> body) {
                return writeWith(Flux.from(body).concatMap(Flux::from));
            }
        };
        // View rendering happens in writeTo, so sanitizing only ServerRequest is insufficient.
        return delegate.writeTo(withoutCredential(exchange, documentUri).mutate()
            .response(response).build(), context);
    }

    private static String host(String html, URI base, String policy) {
        var child = Jsoup.parse(html);
        child.select("base").remove();
        child.head().prependElement("base").attr("href", base.toASCIIString());
        child.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy")
            .attr("content", policy);
        var source = Base64.getEncoder().encodeToString(
            child.outerHtml().getBytes(StandardCharsets.UTF_8));
        var host = Jsoup.parse("<!doctype html><html><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>页面预览</title></head><body style='margin:0'></body></html>");
        // Unlike srcdoc, a data document cannot fall back to the parent's credential URL
        // when a script removes <base>. Its opaque origin also prevents reading parent DOM.
        host.body().appendElement("iframe").attr("title", "页面预览")
            .attr("sandbox", "allow-scripts").attr("referrerpolicy", "no-referrer")
            .attr("style", "position:fixed;inset:0;width:100%;height:100%;border:0")
            .attr("src", "data:text/html;base64," + source);
        return host.outerHtml();
    }
}
