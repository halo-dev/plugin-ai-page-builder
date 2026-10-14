package run.halo.aipagebuilder.page;

import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import run.halo.aipagebuilder.model.ThemePage;

public final class PagePaths {
    public static final String SLUG_PATTERN = "^[a-z0-9]+(?:-[a-z0-9]+)*(?:/[a-z0-9]+(?:-[a-z0-9]+)*)*$";
    // Protect the entire namespace, including authentication callbacks and Console assets.
    private static final Set<String> RESERVED = Set.of(
        "api", "apis", "actuator", "console", "uc", "login", "logout", "signup",
        "password-reset", "challenges", "captcha", "complete-profile", "oauth2", "social", "sudo", "system",
        "preview", "plugins", "themes", "upload", "ui-assets", "webjars", "js", "styles",
        "images", "error");

    private PagePaths() {
    }

    public static String slug(ThemePage page) {
        var slug = page.getSpec().getSlug();
        return slug == null || slug.isBlank() ? page.getMetadata().getName() : slug;
    }

    public static boolean allowed(String slug) {
        return slug != null && slug.length() <= 200 && slug.matches(SLUG_PATTERN)
            && !RESERVED.contains(slug.split("/", 2)[0]);
    }

    public static void validate(String slug) {
        if (!allowed(slug)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "访问路径须由小写字母、数字和连字符组成，可用 / 分层，且不能使用系统保留路径");
        }
    }
}
