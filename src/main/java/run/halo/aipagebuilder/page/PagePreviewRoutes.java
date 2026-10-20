package run.halo.aipagebuilder.page;

import java.util.Objects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
public class PagePreviewRoutes {
    static final PathPattern PATH = PathPatternParser.defaultInstance
        .parse(PagePreviewStore.PATH + "{token}");

    @Bean
    RouterFunction<ServerResponse> aiPageBuilderTokenPreview(PageService pages,
        PagePreview preview, PagePreviewStore store) {
        return RouterFunctions.route().GET(PATH.getPatternString(), request ->
            Mono.defer(() -> {
                var entry = store.get(request.pathVariable("token"));
                if (entry == null) return ServerResponse.notFound().build();
                return pages.get(entry.name())
                    .filter(detail -> Objects.equals(entry.version(), detail.page().version())
                        && entry.revision().equals(detail.page().draftRevision()))
                    .flatMap(detail -> preview.token(request, entry.name(),
                        detail.page().slug() == null ? entry.name() : detail.page().slug(),
                        detail.page().themeLayout(), detail.document()))
                    .switchIfEmpty(ServerResponse.notFound().build())
                    .onErrorResume(ResponseStatusException.class, error ->
                        error.getStatusCode().value() == 404 ? ServerResponse.notFound().build()
                            : Mono.error(error));
            })).build();
    }
}
