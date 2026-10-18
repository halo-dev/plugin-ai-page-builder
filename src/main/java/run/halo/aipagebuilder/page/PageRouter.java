package run.halo.aipagebuilder.page;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
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
                .map(detail -> incoming -> isSuperAdmin()
                    .flatMap(superAdmin -> renderer.render(incoming, detail.page().name(),
                        detail.page().slug(), detail.page().themeLayout(), detail.document(),
                        superAdmin)));
        };
    }

    /**
     * Published page scripts must not run with a super administrator's session, including an
     * impersonated session that can switch back to the original administrator.
     */
    private static Mono<Boolean> isSuperAdmin() {
        return ReactiveSecurityContextHolder.getContext()
            .map(context -> context.getAuthentication())
            .filter(authentication -> authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken))
            .filter(authentication -> authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_super-role".equals(authority.getAuthority())
                    || "ROLE_PREVIOUS_ADMINISTRATOR".equals(authority.getAuthority())))
            .hasElement();
    }
}
