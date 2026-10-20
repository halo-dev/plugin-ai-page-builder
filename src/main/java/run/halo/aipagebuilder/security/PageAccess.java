package run.halo.aipagebuilder.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;

/** Shared authorization for page management; callers retain their transport-specific errors. */
public final class PageAccess {
    private PageAccess() { }

    public static Mono<String> superAdministrator() {
        return ReactiveSecurityContextHolder.getContext()
            .flatMap(context -> Mono.justOrEmpty(context.getAuthentication()))
            .filter(authentication -> authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken))
            .filter(authentication -> authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_super-role".equals(authority.getAuthority())))
            .map(Authentication::getName);
    }
}
