package run.halo.aipagebuilder.ai;

import static org.mockito.Mockito.mock;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;
import run.halo.aipagebuilder.content.ContentPolicy;

class AiEndpointTest {
    @Test
    void deniesRequestsWithoutSuperRole() {
        var endpoint = new AiEndpoint(mock(AiGateway.class));
        WebTestClient.bindToRouterFunction(endpoint.endpoint()).build()
            .get().uri("/ai/status").exchange().expectStatus().isForbidden();
    }

    @Test
    void startsComponentsAndStatusWhenFoundationClassesAreAbsent() throws Exception {
        var parent = getClass().getClassLoader();
        var loader = new ClassLoader(parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("run.halo.aifoundation.") || name.endsWith("FoundationBridge")) {
                    throw new ClassNotFoundException(name);
                }
                if (name.startsWith("run.halo.aipagebuilder.ai.") && !name.endsWith("Test")) {
                    synchronized (getClassLoadingLock(name)) {
                        var loaded = findLoadedClass(name);
                        if (loaded == null) {
                            try (var stream = parent.getResourceAsStream(name.replace('.', '/') + ".class")) {
                                var bytes = stream.readAllBytes();
                                loaded = defineClass(name, bytes, 0, bytes.length);
                            } catch (java.io.IOException error) {
                                throw new ClassNotFoundException(name, error);
                            }
                        }
                        if (resolve) { resolveClass(loaded); }
                        return loaded;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setClassLoader(loader);
            context.getBeanFactory().setBeanClassLoader(loader);
            context.registerBean(ExtensionGetter.class, () -> mock(ExtensionGetter.class));
            var client = mock(run.halo.app.extension.ReactiveExtensionClient.class);
            var plugin = new run.halo.app.core.extension.Plugin();
            plugin.setSpec(new run.halo.app.core.extension.Plugin.PluginSpec());
            plugin.getSpec().setVersion("1.1.1");
            org.mockito.Mockito.when(client.fetch(run.halo.app.core.extension.Plugin.class, "ai-foundation"))
                .thenReturn(reactor.core.publisher.Mono.just(plugin));
            context.registerBean(run.halo.app.extension.ReactiveExtensionClient.class, () -> client);
            context.registerBean(ContentPolicy.class);
            context.registerBean(run.halo.aipagebuilder.memory.SiteMemoryService.class);
            context.registerBean(run.halo.aipagebuilder.theme.ThemeContextService.class);
            context.registerBean(run.halo.aipagebuilder.page.PageService.class,
                () -> mock(run.halo.aipagebuilder.page.PageService.class));
            for (var type : List.of("SiteReferenceTools", "ContentReferenceTools", "WebReferenceTools",
                "ApiReferenceTools")) {
                var referenceType = loader.loadClass("run.halo.aipagebuilder.ai." + type);
                context.getBeanFactory().registerSingleton(type, mock(referenceType));
            }
            for (var type : List.of("OptionalAiGateway", "AiEndpoint", "PageReferenceTools",
                "PageChatService", "PageChatEndpoint")) {
                context.register(loader.loadClass("run.halo.aipagebuilder.ai." + type));
            }
            context.refresh();
            var endpointType = loader.loadClass("run.halo.aipagebuilder.ai.AiEndpoint");
            var endpoint = (run.halo.app.core.extension.endpoint.CustomEndpoint) context.getBean(endpointType);
            var authentication = new UsernamePasswordAuthenticationToken("admin", "unused",
                List.of(new SimpleGrantedAuthority("ROLE_super-role")));
            var web = WebTestClient.bindToRouterFunction(endpoint.endpoint())
                .webFilter((exchange, chain) -> chain.filter(exchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))).build();
            web.get().uri("/ai/status").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.available").isEqualTo(false);
            web.post().uri("/ai/generate").exchange().expectStatus().isNotFound();
        }
    }
}
