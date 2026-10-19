package run.halo.aipagebuilder.page;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.content.Builder.contentBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;
import static org.springdoc.core.fn.builders.schema.Builder.schemaBuilder;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import org.springdoc.core.fn.builders.operation.Builder;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.aipagebuilder.model.PageDocument;
import run.halo.aipagebuilder.page.PageApi.CreatePageRequest;
import run.halo.aipagebuilder.page.PageApi.ImportReview;
import run.halo.aipagebuilder.page.PageApi.MutationRequest;
import run.halo.aipagebuilder.page.PageApi.PageDetail;
import run.halo.aipagebuilder.page.PageApi.PageList;
import run.halo.aipagebuilder.page.PageApi.RestoreRequest;
import run.halo.aipagebuilder.page.PageApi.RevisionDetail;
import run.halo.aipagebuilder.page.PageApi.RevisionSummary;
import run.halo.aipagebuilder.page.PageApi.SavePageRequest;
import run.halo.aipagebuilder.page.PageApi.SiteUrlResponse;

@Component
public class PageEndpoint implements CustomEndpoint {
    private final PageService service;
    private final PageRenderer renderer;
    private final PageImportReviewService reviewService;

    public PageEndpoint(PageService service, PageRenderer renderer,
        PageImportReviewService reviewService) {
        this.service = service;
        this.renderer = renderer;
        this.reviewService = reviewService;
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.aipagebuilder.halo.run", "v1alpha1");
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            .GET("/pages", request -> json(service.list(integer(request, "page", 1),
                    integer(request, "size", 20), request.queryParam("keyword").orElse(null),
                    request.queryParam("status").orElse(null),
                    request.queryParam("sort").orElse(null))),
                builder -> operation(builder, "listPages", PageList.class)
                    .parameter(parameterBuilder().name("page").in(ParameterIn.QUERY)
                        .implementation(Integer.class).description("Page number, starting at 1")
                        .schema(schemaBuilder().type("integer").minimum("1").defaultValue("1")))
                    .parameter(parameterBuilder().name("size").in(ParameterIn.QUERY)
                        .implementation(Integer.class).description("Page size, at most 100")
                        .schema(schemaBuilder().type("integer").minimum("1").maximum("100")
                            .defaultValue("20")))
                    .parameter(parameterBuilder().name("keyword").in(ParameterIn.QUERY)
                        .implementation(String.class).description("Title or slug keyword"))
                    .parameter(parameterBuilder().name("status").in(ParameterIn.QUERY)
                        .implementation(String.class)
                        .description("Publish status: published or unpublished"))
                    .parameter(parameterBuilder().name("sort").in(ParameterIn.QUERY)
                        .implementation(String.class)
                        .description("Sort order: defaults to recently updated; created sorts by "
                            + "creation time")))
            .POST("/pages", request -> body(request, CreatePageRequest.class)
                    .flatMap(input -> actor().flatMap(actor -> json(service.create(input, actor)))),
                builder -> operation(builder, "createPage", PageDetail.class)
                    .requestBody(requestBodyBuilder().required(true)
                        .implementation(CreatePageRequest.class)))
            .POST("/pages/import/review", request -> {
                    var model = request.queryParam("model").map(String::trim)
                        .filter(value -> !value.isEmpty()).orElse(null);
                    return archiveBody(request)
                        .flatMap(bytes -> actor().flatMap(actor ->
                            json(reviewService.review(bytes, actor, model))));
                },
                builder -> operation(builder, "reviewPageImport", ImportReview.class)
                    .parameter(parameterBuilder().name("model").in(ParameterIn.QUERY)
                        .implementation(String.class)
                        .description("AI Foundation language model name; defaults to the "
                            + "configured default model"))
                    .requestBody(requestBodyBuilder().required(true).content(contentBuilder()
                        .mediaType("application/zip")
                        .schema(schemaBuilder().type("string").format("binary")))))
            .POST("/pages/import", request -> {
                    var reviewId = requiredQuery(request, "reviewId");
                    var confirmed = Boolean.parseBoolean(
                        request.queryParam("confirmed").orElse("false"));
                    return archiveBody(request).flatMap(bytes -> actor().flatMap(actor ->
                        reviewService.authorize(reviewId, bytes, actor, confirmed)
                            .then(Mono.defer(() -> json(service.importPage(bytes, actor))))));
                },
                builder -> operation(builder, "importPage", PageDetail.class)
                    .parameter(parameterBuilder().name("reviewId").in(ParameterIn.QUERY)
                        .required(true).implementation(String.class)
                        .description("Review identifier returned by the import review endpoint"))
                    .parameter(parameterBuilder().name("confirmed").in(ParameterIn.QUERY)
                        .implementation(Boolean.class)
                        .description("Confirms import despite suspicious findings"))
                    .requestBody(requestBodyBuilder().required(true).content(contentBuilder()
                        .mediaType("application/zip")
                        .schema(schemaBuilder().type("string").format("binary")))))
            .GET("/pages/{name}", request -> json(service.get(name(request))),
                builder -> named(builder, "getPage", PageDetail.class))
            .PUT("/pages/{name}", request -> body(request, SavePageRequest.class)
                    .flatMap(input -> actor().flatMap(actor ->
                        json(service.save(name(request), input, actor)))),
                builder -> named(builder, "savePage", PageDetail.class)
                    .requestBody(requestBodyBuilder().required(true)
                        .implementation(SavePageRequest.class)))
            .PUT("/pages/{name}/slug", request -> body(request, PageApi.UpdatePageSlugRequest.class)
                    .flatMap(input -> json(service.updateSlug(name(request), input))),
                builder -> named(builder, "updatePageSlug", PageDetail.class)
                    .requestBody(requestBodyBuilder().required(true)
                        .implementation(PageApi.UpdatePageSlugRequest.class)))
            .PUT("/pages/{name}/layout", request -> body(request, PageApi.UpdatePageLayoutRequest.class)
                    .flatMap(input -> json(service.updateLayout(name(request), input))),
                builder -> named(builder, "updatePageLayout", PageDetail.class)
                    .requestBody(requestBodyBuilder().required(true)
                        .implementation(PageApi.UpdatePageLayoutRequest.class)))
            .POST("/pages/{name}/clone", request -> body(request, PageApi.ClonePageRequest.class)
                    .flatMap(input -> actor().flatMap(actor -> json(service.clonePage(name(request), input, actor)))),
                builder -> named(builder, "clonePage", PageDetail.class)
                    .requestBody(requestBodyBuilder().required(true).implementation(PageApi.ClonePageRequest.class)))
            .GET("/pages/{name}/export", request -> service.get(name(request)).flatMap(detail ->
                    Mono.fromCallable(() -> PageExport.zip(detail))
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                        .flatMap(bytes -> ServerResponse.ok().contentType(MediaType.parseMediaType("application/zip"))
                            .header("Content-Disposition", "attachment; filename=\"" + name(request) + ".zip\"")
                            .cacheControl(org.springframework.http.CacheControl.noStore()).bodyValue(bytes))),
                builder -> builder.operationId("exportPage").tag("AiPageBuilder").parameter(nameParameter())
                    .response(responseBuilder().responseCode("200").content(contentBuilder()
                        .mediaType("application/zip").schema(schemaBuilder().type("string").format("binary")))))
            .POST("/pages/{name}/publish", request -> body(request, MutationRequest.class)
                    .flatMap(input -> json(service.publish(name(request), input.version()))),
                builder -> named(builder, "publishPage", PageDetail.class)
                    .requestBody(requestBodyBuilder().required(true)
                        .implementation(MutationRequest.class)))
            .POST("/pages/{name}/unpublish", request -> body(request, MutationRequest.class)
                    .flatMap(input -> json(service.unpublish(name(request), input.version()))),
                builder -> named(builder, "unpublishPage", PageDetail.class)
                    .requestBody(requestBodyBuilder().required(true)
                        .implementation(MutationRequest.class)))
            .DELETE("/pages/{name}", request -> service.delete(name(request), version(request))
                    .then(ServerResponse.noContent().build()),
                builder -> builder.operationId("deletePage").tag("AiPageBuilder")
                    .parameter(nameParameter())
                    .parameter(parameterBuilder().name("version").in(ParameterIn.QUERY)
                        .required(true).implementation(Long.class)
                        .description("Current page metadata version"))
                    .response(responseBuilder().responseCode("204").description("Page deleted")))
            .GET("/pages/{name}/revisions", request -> json(service.revisions(name(request))),
                builder -> builder.operationId("listPageRevisions").tag("AiPageBuilder")
                    .parameter(nameParameter())
                    .response(responseBuilder().responseCode("200")
                        .implementationArray(RevisionSummary.class)))
            .GET("/pages/{name}/revisions/{revision}", request ->
                    json(service.revisionDetail(name(request), request.pathVariable("revision"))),
                builder -> named(builder, "getPageRevision", RevisionDetail.class)
                    .parameter(parameterBuilder().name("revision").in(ParameterIn.PATH)
                        .required(true).implementation(String.class).description("Revision name")))
            .GET("/pages/{name}/revisions/{revision}/preview", request ->
                    service.get(name(request)).flatMap(detail ->
                        service.revisionDetail(name(request), request.pathVariable("revision"))
                            .flatMap(revision -> renderer.preview(request, detail.page().name(),
                                detail.page().slug() != null ? detail.page().slug()
                                    : detail.page().name(),
                                detail.page().themeLayout(), revision.document()))),
                builder -> builder.operationId("previewPageRevision").tag("AiPageBuilder")
                    .parameter(nameParameter())
                    .parameter(parameterBuilder().name("revision").in(ParameterIn.PATH)
                        .required(true).implementation(String.class).description("Revision name"))
                    .response(responseBuilder().responseCode("200")
                        .content(contentBuilder().mediaType(MediaType.TEXT_HTML_VALUE)
                            .schema(schemaBuilder().type("string")))))
            .POST("/pages/{name}/restore", request -> body(request, RestoreRequest.class)
                    .flatMap(input -> actor().flatMap(actor ->
                        json(service.restore(name(request), input, actor)))),
                builder -> named(builder, "restorePageRevision", PageDetail.class)
                    .requestBody(requestBodyBuilder().required(true)
                        .implementation(RestoreRequest.class)))
            .GET("/pages/{name}/preview", request -> service.get(name(request))
                    .flatMap(detail -> renderer.preview(request, detail.page().name(),
                        detail.page().slug() != null ? detail.page().slug() : detail.page().name(),
                        detail.page().themeLayout(), detail.document())),
                builder -> builder.operationId("previewPage").tag("AiPageBuilder")
                    .parameter(nameParameter())
                    .response(responseBuilder().responseCode("200")
                        .content(contentBuilder().mediaType(MediaType.TEXT_HTML_VALUE)
                            .schema(schemaBuilder().type("string")))))
            .POST("/pages/{name}/preview", request -> body(request, PageDocument.class)
                    .flatMap(document -> service.get(name(request))
                        .flatMap(detail -> renderer.preview(request, detail.page().name(),
                            detail.page().slug() != null ? detail.page().slug() : detail.page().name(),
                            detail.page().themeLayout(), document))),
                builder -> builder.operationId("previewPageDocument").tag("AiPageBuilder")
                    .parameter(nameParameter())
                    .requestBody(requestBodyBuilder().required(true)
                        .implementation(PageDocument.class))
                    .response(responseBuilder().responseCode("200")
                        .content(contentBuilder().mediaType(MediaType.TEXT_HTML_VALUE)
                            .schema(schemaBuilder().type("string")))))
            .GET("/site-url", request -> json(Mono.just(
                    new SiteUrlResponse(renderer.origin(request)))),
                builder -> builder.operationId("getSiteUrl").tag("AiPageBuilder")
                    .response(responseBuilder().responseCode("200")
                        .implementation(SiteUrlResponse.class)))
            .build()
            .filter((request, next) -> actor().then(Mono.defer(() -> next.handle(request))));
    }

    private static Mono<String> actor() {
        return ReactiveSecurityContextHolder.getContext()
            .map(context -> context.getAuthentication())
            .filter(authentication -> authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken))
            .filter(authentication -> authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_super-role".equals(authority.getAuthority())))
            .map(Authentication::getName)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Super administrator access is required")));
    }

    private static Builder operation(Builder builder, String operationId, Class<?> response) {
        return builder.operationId(operationId).tag("AiPageBuilder")
            .response(responseBuilder().responseCode("200").implementation(response));
    }

    private static Builder named(Builder builder, String operationId, Class<?> response) {
        return operation(builder, operationId, response).parameter(nameParameter());
    }

    private static org.springdoc.core.fn.builders.parameter.Builder nameParameter() {
        return parameterBuilder().name("name").in(ParameterIn.PATH).required(true)
            .implementation(String.class).description("Stable page identifier");
    }

    private static Mono<byte[]> archiveBody(ServerRequest request) {
        return DataBufferUtils.join(request.exchange().getRequest().getBody(), PageArchive.MAX_BYTES)
            .map(buffer -> {
                try {
                    var bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    return bytes;
                } finally {
                    DataBufferUtils.release(buffer);
                }
            })
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "请选择 ZIP 文件")))
            .onErrorMap(DataBufferLimitException.class, error ->
                new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "ZIP 文件不能超过 4 MB"));
    }

    private static <T> Mono<T> body(ServerRequest request, Class<T> type) {
        return request.bodyToMono(type).switchIfEmpty(Mono.error(
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required")));
    }

    private static Mono<ServerResponse> json(Mono<?> result) {
        return result.flatMap(value -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
            .bodyValue(value));
    }

    private static String name(ServerRequest request) {
        return request.pathVariable("name");
    }

    private static int integer(ServerRequest request, String parameter, int fallback) {
        try {
            return Integer.parseInt(request.queryParam(parameter).orElse(String.valueOf(fallback)));
        } catch (NumberFormatException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + parameter);
        }
    }

    private static Long version(ServerRequest request) {
        try {
            return Long.valueOf(request.queryParam("version").orElseThrow());
        } catch (NumberFormatException | java.util.NoSuchElementException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "version is required");
        }
    }

    private static String requiredQuery(ServerRequest request, String name) {
        return request.queryParam(name).filter(value -> !value.isBlank())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                name + " is required"));
    }
}
