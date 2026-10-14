package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PagePathsTest {
    @Test
    void protectsCoreNamespacesAndAllDescendantsWithoutReservingSimilarNames() {
        for (var root : new String[] {"api", "apis", "actuator", "console", "uc", "login", "logout",
            "signup", "password-reset", "challenges", "captcha", "complete-profile", "oauth2",
            "social", "sudo", "system", "preview", "plugins", "themes", "upload", "ui-assets",
            "webjars", "js", "styles", "images", "error"}) {
            for (var slug : new String[] {root, root + "/child", root + "/child/nested"}) {
                assertThat(PagePaths.allowed(slug)).as(slug).isFalse();
                assertThatThrownBy(() -> PagePaths.validate(slug)).as(slug)
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
            }
            assertThat(PagePaths.allowed(root + "-guide")).as(root).isTrue();
        }
    }

    @Test
    void permitsBusinessRoutesWithoutCheckingThemeRulesOrSinglePages() {
        for (var slug : new String[] {"about", "about/company", "categories", "categories/company",
            "tags", "tags/new", "archives", "archives/2026", "authors/team", "page/2", "index",
            "news", "topics/business", "labels/new", "sitemap", "rss", "feed", "assets/gallery", "v3/docs"}) {
            PagePaths.validate(slug);
            assertThat(PagePaths.allowed(slug)).as(slug).isTrue();
        }
    }

    @Test
    void rejectsAmbiguousEncodedAndNonCanonicalPaths() {
        assertThat(PagePaths.allowed(null)).isFalse();
        for (var slug : new String[] {"", "/", "/about", "about/", "about//company", "about/../login",
            "about/./console", "%6cogin", "%256cogin", "login%2fcallback", "login%5ccallback",
            "login;anything", "login?x=1", "login#x", "login\\callback", "Login", "LOGIN",
            ".well-known/test", "favicon.ico", "robots.txt", "halo-tracker.js", "login\n",
            "login\u0000", "a".repeat(201)}) {
            assertThat(PagePaths.allowed(slug)).as(slug).isFalse();
        }
        assertThat(PagePaths.allowed("a".repeat(200))).isTrue();
    }
}
