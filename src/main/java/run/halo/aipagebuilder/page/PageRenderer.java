package run.halo.aipagebuilder.page;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.theme.TemplateNameResolver;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.model.PageDocument;

@Component
public class PageRenderer {
    private final TemplateNameResolver templateNameResolver;
    private final ContentPolicy contentPolicy;

    public PageRenderer(TemplateNameResolver templateNameResolver, ContentPolicy contentPolicy) {
        this.templateNameResolver = templateNameResolver;
        this.contentPolicy = contentPolicy;
    }

    public Mono<ServerResponse> render(ServerRequest request, String name, String slug,
        boolean themeLayout, PageDocument document) {
        return Mono.defer(() -> {
            contentPolicy.validate(document);
            var rootId = "ai-page-builder-" + name;
            var pageView = new PageView(slug, document.title(), document.description(), rootId,
                renderHtml(document), contentPolicy.renderCss(document.css(), rootId));
            var templateName = themeLayout ? "ai-page-builder" : "ai-page-builder-standalone";
            return templateNameResolver.resolveTemplateNameOrDefault(request.exchange(), templateName)
                .flatMap(template -> ServerResponse.ok().contentType(MediaType.TEXT_HTML)
                    .cacheControl(CacheControl.noStore())
                    .render(template, Map.of("aiPageBuilder", pageView,
                        "_templateId", "plugin:ai-page-builder:" + templateName)));
        });
    }

    private static String renderHtml(PageDocument document) {
        if (document.js().isEmpty()) {
            return document.html();
        }
        // Encode source as data so script end tags cannot escape into the theme markup.
        var source = Base64.getEncoder().encodeToString(document.js().getBytes(StandardCharsets.UTF_8));
        return document.html() + """
            <script>(() => {
              const script = document.createElement('script');
              script.textContent = new TextDecoder().decode(Uint8Array.from(atob('%s'), char => char.charCodeAt(0)));
              document.currentScript.parentNode.appendChild(script);
            })();</script>
            """.formatted(source);
    }

    /** Public template contract; html includes the body followed by its script bootstrap, if any. */
    public record PageView(String slug, String title, String description, String rootId,
        String html, String css) {
    }
}
