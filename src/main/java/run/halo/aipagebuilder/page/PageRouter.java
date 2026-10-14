package run.halo.aipagebuilder.page;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
public class PageRouter {
    @Bean
    RouterFunction<ServerResponse> aiPageBuilderPublicRoutes(PageService service,
        PageRenderer renderer) {
        return request -> {
            if (request.method() != HttpMethod.GET
                || !RequestPredicates.accept(MediaType.TEXT_HTML).test(request)) {
                return Mono.empty();
            }
            var path = request.requestPath().pathWithinApplication().value();
            var slug = path.startsWith("/") ? path.substring(1) : path;
            if (!PagePaths.allowed(slug)) return Mono.empty();
            return service.findPublished(slug)
                .map(detail -> incoming -> renderer.render(incoming, detail.page().name(),
                    detail.page().slug(), detail.page().themeLayout(), detail.document()));
        };
    }
}
