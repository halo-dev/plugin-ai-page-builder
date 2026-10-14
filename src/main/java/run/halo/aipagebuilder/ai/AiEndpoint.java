package run.halo.aipagebuilder.ai;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.webflux.core.fn.SpringdocRouteBuilder.route;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/** Halo's /apis RBAC applies; no role template grants non-super-admin access. */
@Component
public class AiEndpoint implements CustomEndpoint {
    private final AiGateway gateway;

    public AiEndpoint(AiGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("console.api.aipagebuilder.halo.run/v1alpha1");
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route().GET("ai/status", this::status, operation -> operation
            .operationId("GetAiPageBuilderAiStatus").tag("AiPageBuilderAiV1alpha1Console")
            .description("Check for an enabled AI Foundation service; does not call a model")
            .response(responseBuilder().implementation(AiStatus.class))).build();
    }

    private Mono<ServerResponse> status(ServerRequest request) {
        return ReactiveSecurityContextHolder.getContext()
            .filter(context -> context.getAuthentication() != null
                && context.getAuthentication().isAuthenticated())
            .filter(context -> context.getAuthentication().getAuthorities().stream()
                .anyMatch(authority -> "ROLE_super-role".equals(authority.getAuthority())))
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN)))
            .then(Mono.defer(() -> gateway.status().timeout(Duration.ofSeconds(5))
                .onErrorReturn(new AiStatus(false, "AI Foundation 暂不可用。"))
                .flatMap(status -> ServerResponse.ok().bodyValue(status))));
    }

    @Schema(name = "AiStatus")
    public record AiStatus(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean available,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message
    ) { }
}
