package run.halo.aipagebuilder.ai;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import org.springdoc.core.fn.builders.operation.Builder;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.aipagebuilder.security.PageAccess;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.aipagebuilder.Json;
import run.halo.aipagebuilder.content.ContentPolicy;

@Component
public class PageChatEndpoint implements CustomEndpoint {
    private final PageChatService chats;
    private final ContentPolicy policy;

    private final run.halo.aipagebuilder.theme.ThemeContextService themes;

    public PageChatEndpoint(PageChatService chats, ContentPolicy policy,
        run.halo.aipagebuilder.theme.ThemeContextService themes) {
        this.chats = chats;
        this.policy = policy;
        this.themes = themes;
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.aipagebuilder.halo.run", "v1alpha1");
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            .GET("/theme-context", request -> themes.current().flatMap(context -> ServerResponse.ok()
                    .header("Cache-Control", "no-store").bodyValue(context)),
                builder -> builder.operationId("getThemeContext").tag("AiPageBuilderChat")
                    .response(responseBuilder().responseCode("200")
                        .implementation(run.halo.aipagebuilder.theme.ThemeStyleContext.class)))
            .GET("/pages/{name}/chat", request -> json(chats.session(request.pathVariable("name"),
                    request.queryParam("sessionId").orElse(null), request.queryParam("before").orElse(null),
                    integer(request, "size", 20))),
                builder -> named(builder, "getPageChat", ChatApi.Session.class)
                    .parameter(parameterBuilder().name("sessionId").in(ParameterIn.QUERY).implementation(String.class))
                    .parameter(parameterBuilder().name("before").in(ParameterIn.QUERY).implementation(String.class))
                    .parameter(parameterBuilder().name("size").in(ParameterIn.QUERY).implementation(Integer.class)))
            .GET("/pages/{name}/sessions", request -> json(chats.sessions(request.pathVariable("name"))),
                builder -> named(builder, "listPageChatSessions", ChatApi.SessionList.class))
            .POST("/pages/{name}/sessions", request -> body(request, ChatApi.CreateSession.class)
                    .flatMap(input -> json(chats.createSession(request.pathVariable("name"), input))),
                builder -> named(builder, "createPageChatSession", ChatApi.SessionSummary.class)
                    .requestBody(requestBodyBuilder().required(true).implementation(ChatApi.CreateSession.class)))
            .PATCH("/pages/{name}/sessions/{sessionId}", request -> body(request, ChatApi.RenameSession.class)
                    .flatMap(input -> json(chats.renameSession(request.pathVariable("name"),
                        request.pathVariable("sessionId"), input))),
                builder -> sessionNamed(builder, "renamePageChatSession")
                    .response(responseBuilder().responseCode("200").implementation(ChatApi.SessionSummary.class))
                    .requestBody(requestBodyBuilder().required(true).implementation(ChatApi.RenameSession.class)))
            .DELETE("/pages/{name}/sessions/{sessionId}", request -> chats.deleteSession(
                    request.pathVariable("name"), request.pathVariable("sessionId"), version(request))
                    .then(ServerResponse.noContent().build()),
                builder -> sessionNamed(builder, "deletePageChatSession")
                    .parameter(parameterBuilder().name("version").in(ParameterIn.QUERY).required(true)
                        .implementation(Long.class))
                    .response(responseBuilder().responseCode("204")))
            .GET("/pages/{name}/sessions/{sessionId}/checkpoint", request -> actor().flatMap(actor ->
                    json(chats.checkpoint(request.pathVariable("name"), request.pathVariable("sessionId"), actor))),
                builder -> sessionNamed(builder, "getPageChatCheckpoint")
                    .response(responseBuilder().responseCode("200").implementation(ChatApi.CheckpointDetail.class)))
            .POST("/pages/{name}/chat", this::chat,
                builder -> named(builder, "runPageChat", String.class)
                    .description("Halo UIMessage SSE; page commit is emitted as data-page-committed before finish")
                    .requestBody(requestBodyBuilder().required(true).implementation(ChatApi.Request.class)))
            .GET("/pages/chat/runs", request -> json(chats.activeRuns()),
                builder -> builder.operationId("listActivePageChatRuns").tag("AiPageBuilderChat")
                    .response(responseBuilder().responseCode("200")
                        .implementation(ChatApi.ActiveRunList.class)))
            .POST("/pages/{name}/chat/runs", request -> body(request, ChatApi.Request.class)
                    .flatMap(input -> actor().flatMap(actor -> chats.startBackground(
                        request.pathVariable("name"), input, actor)))
                    .then(ServerResponse.noContent().build()),
                builder -> named(builder, "startPageChatRun", String.class)
                    .description("Starts a run without streaming; results are available from the session and run endpoints")
                    .requestBody(requestBodyBuilder().required(true)
                        .implementation(ChatApi.Request.class))
                    .response(responseBuilder().responseCode("204")))
            .GET("/pages/{name}/chat/runs/{runId}", request -> json(chats.result(
                    request.pathVariable("name"), request.pathVariable("runId"))),
                builder -> run(builder, "getPageChatRun"))
            .POST("/pages/{name}/chat/runs/{runId}/cancel", request -> json(chats.cancel(
                    request.pathVariable("name"), request.pathVariable("runId"))),
                builder -> run(builder, "cancelPageChatRun"))
            .POST("/pages/{name}/chat/runs/{runId}/steer",
                request -> body(request, ChatApi.SteerRequest.class).flatMap(input -> actor()
                    .flatMap(actor -> json(chats.steer(request.pathVariable("name"),
                        request.pathVariable("runId"), input, actor)))),
                builder -> run(builder, "steerPageChatRun")
                    .requestBody(requestBodyBuilder().required(true)
                        .implementation(ChatApi.SteerRequest.class)))
            .POST("/pages/{name}/chat/runs/{runId}/questions/{questionId}/answer",
                request -> body(request, ChatApi.AnswerRequest.class).flatMap(input -> actor()
                    .flatMap(actor -> json(chats.answer(request.pathVariable("name"),
                        request.pathVariable("runId"), request.pathVariable("questionId"), input, actor)))),
                builder -> run(builder, "answerPageChatQuestion")
                    .parameter(parameterBuilder().name("questionId").in(ParameterIn.PATH).required(true)
                        .implementation(String.class))
                    .requestBody(requestBodyBuilder().required(true).implementation(ChatApi.AnswerRequest.class)))
            .POST("/content/validate", request -> body(request, ChatApi.Content.class)
                    .flatMap(content -> Mono.fromRunnable(() -> {
                        policy.validateHtml(content.html());
                        policy.validateCss(content.css());
                        policy.validateJs(content.js());
                    })).then(ServerResponse.noContent().build()),
                builder -> builder.operationId("validatePageContent").tag("AiPageBuilderChat")
                    .requestBody(requestBodyBuilder().required(true).implementation(ChatApi.Content.class))
                    .response(responseBuilder().responseCode("204").description("Content is valid")))
            .build().filter((request, next) -> actor().then(Mono.defer(() -> next.handle(request))));
    }

    private Mono<ServerResponse> chat(ServerRequest request) {
        return body(request, ChatApi.Request.class).flatMap(input -> actor().flatMap(actor ->
            chats.start(request.pathVariable("name"), input, actor))).flatMap(events ->
            ServerResponse.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-store")
                .header("X-Halo-AI-UI-Message-Stream", "v1")
                .body(events.map(chunk -> ServerSentEvent.builder(Json.toJson(chunk)).build())
                    .concatWithValues(ServerSentEvent.builder("[DONE]").build()),
                    new ParameterizedTypeReference<ServerSentEvent<String>>() { }));
    }

    private static Mono<String> actor() {
        return PageAccess.superAdministrator()
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN)));
    }

    private static Builder named(Builder builder, String id, Class<?> response) {
        return builder.operationId(id).tag("AiPageBuilderChat")
            .parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true)
                .implementation(String.class))
            .response(responseBuilder().responseCode("200").implementation(response));
    }

    private static Builder sessionNamed(Builder builder, String id) {
        return builder.operationId(id).tag("AiPageBuilderChat")
            .parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true)
                .implementation(String.class))
            .parameter(parameterBuilder().name("sessionId").in(ParameterIn.PATH).required(true)
                .implementation(String.class));
    }

    private static int integer(ServerRequest request, String name, int fallback) {
        try {
            return request.queryParam(name).map(Integer::parseInt).orElse(fallback);
        } catch (NumberFormatException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " 必须是整数");
        }
    }

    private static Long version(ServerRequest request) {
        try {
            return request.queryParam("version").map(Long::valueOf)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "version 必填"));
        } catch (NumberFormatException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "version 必须是整数");
        }
    }

    private static Builder run(Builder builder, String id) {
        return named(builder, id, ChatApi.Run.class)
            .parameter(parameterBuilder().name("runId").in(ParameterIn.PATH).required(true)
                .implementation(String.class));
    }

    private static <T> Mono<T> body(ServerRequest request, Class<T> type) {
        return request.bodyToMono(type).switchIfEmpty(Mono.error(new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Request body is required")));
    }

    private static Mono<ServerResponse> json(Mono<?> value) {
        return value.flatMap(result -> ServerResponse.ok().bodyValue(result));
    }
}
