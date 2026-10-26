package run.halo.aipagebuilder.page;

import java.util.List;

/** Public site documents that sandboxed theme widgets may fetch. */
final class PreviewPublicPaths {
    static final List<String> ALL = List.of("/rss.xml", "/feed.xml", "/sitemap.xml", "/feed/");

    private PreviewPublicPaths() {
    }

    static boolean matches(String path) {
        return ALL.stream().anyMatch(publicPath -> publicPath.endsWith("/")
            ? path.startsWith(publicPath) : path.equals(publicPath));
    }
}
