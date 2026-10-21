package run.halo.aipagebuilder.page;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.aipagebuilder.model.ThemePage;

@Configuration(proxyBeanMethods = false)
public class PageRouter {
    private static final String ROLE_PREFIX = "ROLE_";

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
                .map(detail -> incoming -> viewer().flatMap(viewer -> {
                    if (!canRead(detail.page(), viewer)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
                    }
                    return renderer.render(incoming, detail.page().name(),
                            detail.page().slug(), detail.page().themeLayout(), detail.document(),
                            detail.page().title(), detail.page().description(),
                            viewer.superAdmin())
                        // Theme layouts can render user-specific markup, and the super
                        // administrator variant strips scripts. Only anonymous responses may
                        // be shared by a CDN.
                        .map(response -> viewer.anonymous()
                            ? new PageCacheResponse(response) : (ServerResponse) response);
                }));
        };
    }

    /**
     * Published page scripts must not run with a super administrator's session, including an
     * impersonated session that can switch back to the original administrator.
     */
    private static Mono<Viewer> viewer() {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .filter(authentication -> authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken))
            .map(authentication -> new Viewer(authentication.getName(),
                roles(authentication.getAuthorities()),
                authentication.getAuthorities().stream()
                    .anyMatch(authority -> "ROLE_super-role".equals(authority.getAuthority())
                        || "ROLE_PREVIOUS_ADMINISTRATOR".equals(authority.getAuthority()))))
            .defaultIfEmpty(Viewer.ANONYMOUS);
    }

    private static Set<String> roles(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority)
            .filter(authority -> authority.startsWith(ROLE_PREFIX))
            .map(authority -> authority.substring(ROLE_PREFIX.length()))
            .collect(Collectors.toSet());
    }

    private static boolean canRead(PageApi.PageSummary page, Viewer viewer) {
        if (!page.authorizeRequired()) {
            return true;
        }
        if (viewer.superAdmin()) {
            return true;
        }
        if (viewer.username() == null || page.members() == null) {
            return false;
        }
        return page.members().stream().filter(Objects::nonNull)
            .anyMatch(member -> member.kind() == ThemePage.MemberType.USER
                ? viewer.username().equals(member.name())
                : viewer.roles().contains(member.name()));
    }

    private record Viewer(String username, Set<String> roles, boolean superAdmin) {
        static final Viewer ANONYMOUS = new Viewer(null, Set.of(), false);

        boolean anonymous() {
            return username == null;
        }
    }
}
