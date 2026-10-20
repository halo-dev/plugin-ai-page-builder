package run.halo.aipagebuilder.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import reactor.core.publisher.Mono;

class PageAccessTest {
    @Test
    void managementRequiresAuthenticatedNonAnonymousCurrentSuperAdministrator() {
        assertThat(PageAccess.superAdministrator().block()).isNull();
        assertThat(PageAccess.superAdministrator().contextWrite(
            ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl())))
            .block()).isNull();
        var authorities = AuthorityUtils.createAuthorityList("ROLE_super-role");
        for (var rejected : java.util.List.of(
            new AnonymousAuthenticationToken("anonymous", "anonymous", authorities),
            new UsernamePasswordAuthenticationToken("owner", "unused"),
            new UsernamePasswordAuthenticationToken("owner", "unused",
                AuthorityUtils.createAuthorityList("ROLE_PREVIOUS_ADMINISTRATOR")),
            new UsernamePasswordAuthenticationToken("owner", "unused",
                AuthorityUtils.createAuthorityList("ROLE_authenticated")))) {
            assertThat(PageAccess.superAdministrator().contextWrite(
                ReactiveSecurityContextHolder.withAuthentication(rejected)).block()).isNull();
        }
        var accepted = new UsernamePasswordAuthenticationToken("owner", "unused", authorities);
        assertThat(PageAccess.superAdministrator().contextWrite(
            ReactiveSecurityContextHolder.withAuthentication(accepted)).block()).isEqualTo("owner");
    }
}
