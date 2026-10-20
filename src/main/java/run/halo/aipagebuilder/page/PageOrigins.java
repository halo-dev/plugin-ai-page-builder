package run.halo.aipagebuilder.page;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.reactive.function.server.ServerRequest;
import run.halo.app.infra.ExternalUrlSupplier;

/** Shared URL validation with distinct public-link and preview-origin precedence. */
final class PageOrigins {
    private static final Set<String> WEB_SCHEMES = Set.of("http", "https");
    private static final Pattern AUTHORITY = Pattern.compile("[A-Za-z0-9._\\-:\\[\\]]+");
    private final ExternalUrlSupplier externalUrlSupplier;

    PageOrigins(ExternalUrlSupplier externalUrlSupplier) {
        this.externalUrlSupplier = externalUrlSupplier;
    }

    /**
     * Canonical public origin used for public links. Prefers the configured external URL so
     * visitors behind a reverse proxy see the address they actually use.
     */
    String publicOrigin(ServerRequest request) {
        var external = externalOrigin(request);
        return external != null ? external : requestOrigin(request);
    }

    /** Origin serving the preview document; the configured external URL is the fallback. */
    String servingOrigin(ServerRequest request) {
        var requestBase = requestOrigin(request);
        return requestBase != null ? requestBase : externalOrigin(request);
    }

    private String externalOrigin(ServerRequest request) {
        java.net.URL url = null;
        try {
            url = externalUrlSupplier.getURL(request.exchange().getRequest());
        } catch (RuntimeException error) {
            org.slf4j.LoggerFactory.getLogger(PageOrigins.class)
                .debug("Unable to resolve the external URL; falling back to the request", error);
        }
        if (url == null) return null;
        var base = checkedOrigin(url.getProtocol(), url.getAuthority());
        if (base == null) return null;
        var path = url.getPath();
        if (path != null && !path.isBlank() && !"/".equals(path)) {
            // A configured path must not be able to inject another CSP directive.
            if (!path.matches("[A-Za-z0-9._~!$&'()*+=:@%/\\-]*")) return null;
            return base + (path.endsWith("/") ? path.substring(0, path.length() - 1) : path);
        }
        return base;
    }

    private static String requestOrigin(ServerRequest request) {
        var forwarded = request.headers().firstHeader("X-Forwarded-Proto");
        var scheme = forwarded != null ? forwarded.split(",", 2)[0].trim()
            : request.uri().getScheme();
        if (scheme == null || scheme.isBlank()) scheme = "http";
        var authority = request.uri().getRawAuthority();
        if (authority == null) authority = request.headers().firstHeader("Host");
        return checkedOrigin(scheme, authority);
    }

    /** Rejects values that would break out of the CSP source list. */
    private static String checkedOrigin(String scheme, String authority) {
        if (scheme == null || !WEB_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) return null;
        if (authority == null || !AUTHORITY.matcher(authority).matches()) return null;
        return scheme.toLowerCase(Locale.ROOT) + "://" + authority;
    }
}
