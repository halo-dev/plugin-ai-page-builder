package run.halo.aipagebuilder.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.aipagebuilder.content.ContentPolicy;
import run.halo.aipagebuilder.page.PageService;
import run.halo.aipagebuilder.page.PagePreviewStore;
import run.halo.aipagebuilder.theme.ThemeContextService;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "run.halo.mcpserver.api.McpToolProvider")
public class PageMcpConfiguration {
    @Bean
    PageMcpToolProvider pageMcpToolProvider(PageService pages, ContentPolicy policy,
        ThemeContextService themes, PagePreviewStore previews, ExternalUrlSupplier urls) {
        return new PageMcpToolProvider(pages, policy, themes, previews, urls);
    }
}
