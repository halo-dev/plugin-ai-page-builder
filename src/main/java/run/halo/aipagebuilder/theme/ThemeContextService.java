package run.halo.aipagebuilder.theme;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.Theme;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.SystemSetting;
import run.halo.aipagebuilder.Json;
import run.halo.aipagebuilder.content.ContentPolicy;

@Service
public class ThemeContextService {
    private final ReactiveExtensionClient client;
    private final ThemeContextExtractor extractor;

    public ThemeContextService(ReactiveExtensionClient client, ContentPolicy policy) {
        this.client = client;
        this.extractor = new ThemeContextExtractor(policy);
    }

    public Mono<ThemeStyleContext> current() {
        return client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG)
            .flatMap(config -> activeName(config).switchIfEmpty(Mono.defer(() ->
                client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG_DEFAULT)
                    .flatMap(this::activeName))))
            .filter(name -> !name.isBlank())
            .flatMap(name -> client.fetch(Theme.class, name)
                .flatMap(theme -> Mono.fromCallable(() -> extract(theme))
                    .subscribeOn(Schedulers.boundedElastic()))
                .defaultIfEmpty(ThemeStyleContext.unavailable()))
            .defaultIfEmpty(new ThemeStyleContext(null, null, "FALLBACK", List.of(),
                List.of("没有已激活的主题，页面使用系统备用布局。"), ""))
            .onErrorReturn(ThemeStyleContext.unavailable());
    }

    public Mono<String> activeThemeName() {
        return client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG)
            .flatMap(config -> activeName(config).switchIfEmpty(Mono.defer(() ->
                client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG_DEFAULT)
                    .flatMap(this::activeName))))
            .filter(name -> !name.isBlank())
            .switchIfEmpty(Mono.error(new IllegalStateException("No active theme")));
    }

    public Mono<Map<String, Object>> listFiles(String themeName, String directory, int offset,
        int limit) {
        if (offset < 0 || offset > 2000 || limit < 1 || limit > 100) {
            return Mono.error(new IllegalArgumentException("Invalid file pagination"));
        }
        return withTheme(themeName, root -> {
            var folder = sourcePath(root, directory == null ? "templates" : directory, false);
            var paths = new ArrayList<String>();
            boolean scanTruncated;
            // Do not follow directory symlinks; also bound traversal work for very large themes.
            try (var walk = Files.walk(folder)) {
                var iterator = walk.iterator();
                int scanned = 0;
                while (iterator.hasNext() && scanned++ < 4000) {
                    var entry = iterator.next();
                    if (Files.isRegularFile(entry) && sourceExtension(entry.toString())) {
                        var relative = root.relativize(entry).toString().replace('\\', '/');
                        try {
                            sourcePath(root, relative, true);
                            paths.add(relative);
                        } catch (IOException | IllegalArgumentException ignored) {
                            // Exclude escaping links and paths outside the source allowlist.
                        }
                    }
                }
                scanTruncated = iterator.hasNext();
            }
            paths.sort(Comparator.naturalOrder());
            var start = Math.min(offset, paths.size());
            var end = Math.min(start + limit, paths.size());
            return Map.<String, Object>of("themeName", themeName, "path",
                root.relativize(folder).toString().replace('\\', '/'), "items",
                paths.subList(start, end), "offset", offset, "limit", limit,
                "truncated", scanTruncated || end < paths.size(), "scanTruncated", scanTruncated);
        });
    }

    public Mono<Map<String, Object>> readFile(String themeName, String path, int offset, int limit) {
        if (offset < 0 || offset > 256 * 1024 || limit < 1 || limit > 16 * 1024) {
            return Mono.error(new IllegalArgumentException("Invalid source pagination"));
        }
        return withTheme(themeName, root -> {
            var file = sourcePath(root, path, true);
            byte[] bytes;
            try (var input = Files.newInputStream(file)) {
                bytes = input.readNBytes(256 * 1024 + 1);
            }
            var byteTruncated = bytes.length > 256 * 1024;
            var source = new String(bytes, 0, Math.min(bytes.length, 256 * 1024), StandardCharsets.UTF_8);
            var start = Math.min(offset, source.length());
            var end = Math.min(start + limit, source.length());
            return Map.<String, Object>of("themeName", themeName, "path", path,
                "content", source.substring(start, end), "offset", offset, "limit", limit,
                "nextOffset", end, "truncated", byteTruncated || end < source.length(),
                "byteTruncated", byteTruncated);
        });
    }

    private Mono<Map<String, Object>> withTheme(String name, SourceAction action) {
        return activeThemeName().filter(name::equals)
            .switchIfEmpty(Mono.error(new IllegalStateException("Active theme changed")))
            .flatMap(active -> client.fetch(Theme.class, active))
            .switchIfEmpty(Mono.error(new IllegalStateException("Active theme unavailable")))
            .flatMap(theme -> Mono.fromCallable(() -> {
                if (theme.getMetadata().getDeletionTimestamp() != null || theme.getStatus() == null
                    || theme.getStatus().getLocation() == null) {
                    throw new IllegalStateException("Active theme unavailable");
                }
                var path = Path.of(theme.getStatus().getLocation());
                if (!path.isAbsolute()) throw new IllegalStateException("Invalid theme location");
                return action.apply(path.toRealPath());
            }).subscribeOn(Schedulers.boundedElastic()))
            .flatMap(result -> activeThemeName().filter(name::equals)
                .switchIfEmpty(Mono.error(new IllegalStateException("Active theme changed")))
                .thenReturn(result));
    }

    private static Path sourcePath(Path root, String relative, boolean file) throws IOException {
        if (relative == null || relative.isBlank() || relative.length() > 512
            || relative.contains("\\") || relative.contains("\u0000")) {
            throw new IllegalArgumentException("Invalid source path");
        }
        var path = Path.of(relative);
        if (path.isAbsolute() || !path.startsWith("templates")) {
            throw new IllegalArgumentException("Only templates sources are available");
        }
        for (var segment : path) {
            if ("..".equals(segment.toString()) || segment.toString().startsWith(".")) {
                throw new IllegalArgumentException("Invalid source path");
            }
        }
        var ancestor = root;
        for (var segment : path) {
            ancestor = ancestor.resolve(segment);
            if (Files.isSymbolicLink(ancestor)) {
                throw new IllegalArgumentException("Source symlinks are unavailable");
            }
        }
        var templates = root.resolve("templates").toRealPath();
        var resolved = root.resolve(path).toRealPath();
        if (!templates.startsWith(root) || !resolved.startsWith(templates)
            || (file ? !Files.isRegularFile(resolved) || !sourceExtension(relative)
                || !sourceExtension(resolved.toString()) : !Files.isDirectory(resolved))) {
            throw new IllegalArgumentException("Source path is unavailable");
        }
        return resolved;
    }

    private static boolean sourceExtension(String path) {
        return path.endsWith(".html") || path.endsWith(".css") || path.endsWith(".js");
    }

    @FunctionalInterface
    private interface SourceAction {
        Map<String, Object> apply(Path root) throws IOException;
    }

    // Only a missing field inherits the default; explicit null/empty disables that default.
    private Mono<String> activeName(ConfigMap config) {
        return Mono.fromCallable(() -> {
            var json = config.getData() == null ? null : config.getData().get(SystemSetting.Theme.GROUP);
            if (json == null) return null;
            var group = Json.MAPPER.readTree(json);
            if (group.isNull()) return "";
            if (!group.isObject()) throw new IllegalArgumentException("Invalid theme group");
            if (!group.has("active")) return null;
            var active = group.get("active");
            if (active.isNull()) return "";
            if (!active.isString()) throw new IllegalArgumentException("Invalid active theme");
            return active.asString();
        });
    }

    private ThemeStyleContext extract(Theme theme) throws IOException {
        var name = theme.getMetadata().getName();
        var displayName = theme.getSpec() == null ? name : theme.getSpec().getDisplayName();
        var status = theme.getStatus();
        if (status == null || status.getPageLayout() == null || status.getPageLayout().getState() == null) {
            return new ThemeStyleContext(name, displayName, "UNAVAILABLE", List.of(),
                List.of("主题布局状态尚未就绪，不能确定基础样式。"), "");
        }
        var state = String.valueOf(status.getPageLayout().getState());
        if (!"SUPPORTED".equals(state)) {
            return new ThemeStyleContext(name, displayName, state, List.of(),
                List.of("当前主题未提供有效页面布局，页面使用系统备用布局，不应依赖此主题的私有样式。"), "");
        }
        if (status.getLocation() == null || status.getLocation().isBlank()) {
            return ThemeStyleContext.unavailable();
        }
        var declared = Path.of(status.getLocation());
        if (!declared.isAbsolute()) return ThemeStyleContext.unavailable();
        var root = declared.toRealPath();
        var result = extractor.extract(relative -> read(root, relative), name);
        var notes = new ArrayList<>(result.notes());
        notes.add("先读取 layout.html 及其引用，检查 content 插槽祖先已有的宽度、padding 和 margin，避免重复添加外层留白。");
        notes.add("同时读取亮色与暗色规则及切换脚本；主题源码仅供参考，不执行其中的模板、HTML 或 JavaScript。");
        return new ThemeStyleContext(name, displayName, state, result.sources(), notes,
            result.previewCss(), result.colorMode());
    }

    // The extractor provides only local HTML/CSS paths. Never follow a theme reference to another directory.
    static String read(Path root, String relative) {
        try {
            if (relative == null || relative.contains("\\") || relative.contains("\u0000")) return null;
            var path = Path.of(relative);
            if (path.isAbsolute() || !relative.startsWith("templates/")
                || !(relative.endsWith(".html") || relative.endsWith(".css"))) return null;
            for (var segment : path) if ("..".equals(segment.toString())) return null;
            var templates = root.resolve("templates").toRealPath();
            if (!templates.startsWith(root)) return null;
            var file = root.resolve(path).toRealPath();
            if (!file.startsWith(templates) || !Files.isRegularFile(file)) return null;
            try (var input = Files.newInputStream(file)) {
                var bytes = input.readNBytes(256 * 1024 + 1);
                if (bytes.length > 256 * 1024) return null;
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (IOException | IllegalArgumentException error) {
            return null;
        }
    }
}
