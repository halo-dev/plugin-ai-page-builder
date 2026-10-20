package run.halo.aipagebuilder.page;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.WebSession;
import org.springframework.web.server.session.InMemoryWebSessionStore;
import reactor.core.publisher.Mono;
import run.halo.app.security.AfterSecurityWebFilter;

/** Apply visitor identity to the whole render subscription, including template evaluation. */
@Component
public class PagePreviewIdentityFilter implements AfterSecurityWebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!PagePreviewRoutes.PATH.matches(exchange.getRequest().getPath()
            .pathWithinApplication())) return chain.filter(exchange);
        var headers = exchange.getResponse().getHeaders();
        headers.setCacheControl("no-store");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Robots-Tag", "noindex, nofollow, noarchive");
        var visitor = new AnonymousAuthenticationToken("ai-page-builder-preview", "anonymousUser",
            AuthorityUtils.createAuthorityList("ROLE_anonymous"));
        var request = exchange.getRequest().mutate().headers(values -> {
            values.remove("Authorization");
            values.remove("Cookie");
        }).build();
        // Halo's Thymeleaf security dialect reads the session repository directly, rather
        // than the Reactor context. Give rendering a fresh session without clearing or
        // invalidating the administrator's real session. It is never attached to a cookie.
        var session = new InMemoryWebSessionStore().createWebSession().cache();
        var anonymousExchange = new ServerWebExchangeDecorator(exchange.mutate()
            .request(request).principal(Mono.just(visitor)).build()) {
            @Override
            public Mono<WebSession> getSession() {
                return session;
            }
        };
        return chain.filter(anonymousExchange)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(visitor));
    }
}
