package run.halo.aipagebuilder.page;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RenderingResponse;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;
import run.halo.app.extension.GVK;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.app.theme.Constant;
import run.halo.app.theme.TemplateNameResolver;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;
import run.halo.aipagebuilder.model.ThemePage;

@Component
public class PageRenderer {
    /** Halo's tracker head processor reads these variables to emit the visit counter script. */
    private static final GVK THEME_PAGE_GVK = ThemePage.class.getAnnotation(GVK.class);

    private final TemplateNameResolver templateNameResolver;
    private final PageRenderCache content;
    private final PageOrigins origins;

    public PageRenderer(TemplateNameResolver templateNameResolver, ContentPolicy contentPolicy,
        ExternalUrlSupplier externalUrlSupplier) {
        this.templateNameResolver = templateNameResolver;
        this.content = new PageRenderCache(contentPolicy);
        this.origins = new PageOrigins(externalUrlSupplier);
    }

    public Mono<RenderingResponse> render(ServerRequest request, String name, String slug,
        boolean themeLayout, PageDocument document, String title, String description) {
        return render(request, name, slug, themeLayout, document, title, description, false, false);
    }

    public Mono<RenderingResponse> render(ServerRequest request, String name, String slug,
        boolean themeLayout, PageDocument document, String title, String description,
        boolean disableScripts, boolean trackVisit) {
        return Mono.defer(() -> {
            var rootId = "ai-page-builder-" + name;
            var compiled = content.compile(rootId, document);
            var scriptBlocked = disableScripts && !document.js().isEmpty();
            var pageView = new PageView(slug, title, description, rootId,
                renderHtml(compiled.html(), document.js(), disableScripts),
                compiled.css(), scriptBlocked);
            var templateName = themeLayout ? "ai-page-builder" : "ai-page-builder-standalone";
            return templateNameResolver.resolveTemplateNameOrDefault(request.exchange(), templateName)
                .flatMap(template -> {
                    var response = RenderingResponse.create(template)
                        .header("Content-Type", MediaType.TEXT_HTML_VALUE)
                        .header("Cache-Control", CacheControl.noStore().getHeaderValue())
                        .modelAttribute("aiPageBuilder", pageView)
                        .modelAttribute("_templateId", "plugin:ai-page-builder:" + templateName);
                    if (trackVisit) {
                        response.modelAttribute("name", name)
                            .modelAttribute("groupVersionKind",
                                new GroupVersionKind(THEME_PAGE_GVK.group(),
                                    THEME_PAGE_GVK.version(), THEME_PAGE_GVK.kind()))
                            .modelAttribute("plural", THEME_PAGE_GVK.plural());
                    }
                    // Imported pages may omit the optional description.
                    if (description != null) {
                        response.modelAttribute(Constant.META_DESCRIPTION_VARIABLE_NAME,
                            description);
                    }
                    return response.build();
                });
        });
    }

    @jakarta.annotation.PreDestroy
    public void clearCache() { content.clear(); }

    String origin(ServerRequest request) {
        return origins.publicOrigin(request);
    }

    private static String renderHtml(String html, String js, boolean disableScripts) {
        if (js.isEmpty() || disableScripts) {
            return html;
        }
        // Encode source as data so script end tags cannot escape into the theme markup.
        var source = Base64.getEncoder().encodeToString(js.getBytes(StandardCharsets.UTF_8));
        return html + """
            <script>(() => {
              const script = document.createElement('script');
              script.textContent = new TextDecoder().decode(Uint8Array.from(atob('%s'), char => char.charCodeAt(0)));
              document.currentScript.parentNode.appendChild(script);
            })();</script>
            """.formatted(source);
    }

    /** Public template contract; html includes the body followed by its script bootstrap, if any. */
    public record PageView(String slug, String title, String description, String rootId,
        String html, String css, boolean scriptBlocked) {
    }
}
