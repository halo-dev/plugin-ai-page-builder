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
import run.halo.aipagebuilder.theme.ThemeContextExtractor.SourceText;

@Service
public class ThemeContextService {
    public static final int MAX_SOURCE_BYTES = 1024 * 1024;
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
            var relative = root.relativize(folder).toString().replace('\\', '/');
            return Map.<String, Object>of("themeName", themeName, "path", relative, "items",
                paths.subList(start, end), "offset", offset, "limit", limit,
                "truncated", scanTruncated || end < paths.size(), "scanTruncated", scanTruncated,
                "summary", relative + " · 共 " + paths.size() + " 个文件");
        });
    }

    public Mono<Map<String, Object>> readFile(String themeName, String path, int offset, int limit) {
        if (offset < 0 || offset > MAX_SOURCE_BYTES || limit < 1 || limit > 16 * 1024) {
            return Mono.error(new IllegalArgumentException("Invalid source pagination"));
        }
        return withTheme(themeName, root -> {
            var read = readSource(root, path);
            var source = read.text();
            var byteTruncated = read.byteTruncated();
            var start = Math.min(offset, source.length());
            var end = Math.min(start + limit, source.length());
            var summary = path + " · " + (start == 0 && !byteTruncated && end == source.length()
                ? "共 " + source.length() + " 字符"
                : "已读取 " + (end - start) + " / " + source.length() + " 字符");
            return Map.<String, Object>of("themeName", themeName, "path", path,
                "content", source.substring(start, end), "offset", offset, "limit", limit,
                "nextOffset", end, "truncated", byteTruncated || end < source.length(),
                "byteTruncated", byteTruncated, "summary", summary);
        });
    }

    public Mono<Map<String, Object>> searchFile(String themeName, String path, String query,
        int offset, int limit) {
        if (query == null || query.isBlank() || query.length() > 200
            || offset < 0 || offset > MAX_SOURCE_BYTES || limit < 1 || limit > 20) {
            return Mono.error(new IllegalArgumentException("Invalid source search"));
        }
        return withTheme(themeName, root -> {
            var read = readSource(root, path);
            var source = read.text();
            var matches = new ArrayList<Map<String, Object>>();
            int cursor = Math.min(offset, source.length());
            while (matches.size() < limit) {
                int index = source.indexOf(query, cursor);
                if (index < 0) break;
                int start = Math.max(0, index - 128);
                if (start > 0 && Character.isLowSurrogate(source.charAt(start))) start--;
                int end = Math.min(source.length(), start + 512);
                if (end < source.length() && Character.isHighSurrogate(source.charAt(end - 1))) end--;
                matches.add(Map.of("offset", index, "start", start, "end", end,
                    "text", source.substring(start, end)));
                cursor = index + query.length();
            }
            boolean hasMore = source.indexOf(query, cursor) >= 0;
            return Map.<String, Object>of("themeName", themeName, "path", path, "matches", matches,
                "nextOffset", hasMore ? cursor : source.length(), "hasMore", hasMore,
                "byteTruncated", read.byteTruncated(), "scannedCharacters", source.length(),
                "summary", path + " · " + matches.size() + " 处匹配"
                    + (read.byteTruncated() ? "（仅搜索文件前 1 MiB）" : ""));
        });
    }

    private static SourceText readSource(Path root, String path) throws IOException {
        var file = sourcePath(root, path, true);
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(MAX_SOURCE_BYTES + 1);
        }
        return new SourceText(new String(bytes, 0, Math.min(bytes.length, MAX_SOURCE_BYTES),
            StandardCharsets.UTF_8), bytes.length > MAX_SOURCE_BYTES);
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
        var pageTemplate = pageTemplate(root, theme);
        var result = extractor.extractSources(relative -> read(root, relative), name, pageTemplate);
        var notes = new ArrayList<>(result.notes());
        notes.add("sources 包含已解析的布局与基础 CSS，contentSlot 描述页面挂载位置；未展开内容见 coverage。");
        notes.add("colorMode 描述静态提取的亮暗模式；动态设置值与模板表达式未执行。");
        if (!result.contentSlot().wrapper().isEmpty()
            || !result.contentSlot().contentClasses().isEmpty()) {
            notes.add("已读取主题页面模板 " + result.contentSlot().template()
                + " 的正文容器，仅用于文章页参考，不是落地页必需的容器。");
        }
        return new ThemeStyleContext(name, displayName, state, result.sources(), notes,
            result.previewCss(), result.colorMode(), result.contentSlot(), result.uiStack(),
            result.coverage());
    }

    /**
     * The generated page is a regular single page, so Halo renders it with page.html. The post
     * template is a last-resort content-slot reference for themes that reuse post markup.
     */
    private static String pageTemplate(Path root, Theme theme) {
        var candidates = new ArrayList<String>();
        candidates.add("templates/page.html");
        candidates.add("templates/post.html");
        var spec = theme.getSpec();
        var custom = spec == null ? null : spec.getCustomTemplates();
        if (custom != null && custom.getPage() != null) {
            for (var descriptor : custom.getPage()) {
                var file = descriptor.getFile();
                if (file == null || file.isBlank()) continue;
                candidates.add(file.startsWith("templates/") ? file : "templates/" + file);
            }
        }
        try {
            for (var candidate : candidates) {
                if (Files.isRegularFile(root.resolve(candidate))) return candidate;
            }
        } catch (RuntimeException ignored) {
            // Invalid custom template paths simply fall back to no page template.
        }
        return null;
    }

    // The extractor provides only local HTML/CSS paths. Never follow a theme reference to another directory.
    static SourceText read(Path root, String relative) {
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
                var bytes = input.readNBytes(MAX_SOURCE_BYTES + 1);
                if (bytes.length <= MAX_SOURCE_BYTES) {
                    return new SourceText(new String(bytes, StandardCharsets.UTF_8), false);
                }
                // Large compiled CSS still carries the base rules we need; parse the prefix
                // instead of discarding the whole file. Report byte truncation independently
                // of the decoded character count or the length of the complete CSS prefix.
                var text = new String(bytes, 0, MAX_SOURCE_BYTES, StandardCharsets.UTF_8);
                return new SourceText(relative.endsWith(".css") ? cssPrefix(text) : text, true);
            }
        } catch (IOException | IllegalArgumentException error) {
            return null;
        }
    }

    /**
     * Cut a truncated stylesheet at the last complete top-level rule. A hard byte cut can land
     * inside a comment, string or nested block, which makes the CSS parser reject the entire
     * prefix; the rules before that point are still useful reference data.
     */
    private static String cssPrefix(String text) {
        int end = text.length();
        int depth = 0;
        int lastRule = -1;
        char quote = 0;
        boolean comment = false;
        for (int index = 0; index < end; index++) {
            char current = text.charAt(index);
            char next = index + 1 < end ? text.charAt(index + 1) : 0;
            if (comment) {
                if (current == '*' && next == '/') {
                    comment = false;
                    index++;
                }
                continue;
            }
            if (quote != 0) {
                if (current == '\\') {
                    index++;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\\') {
                // Outside strings a backslash still escapes the next character, e.g. .x\{y.
                index++;
            } else if (current == '/' && next == '*') {
                comment = true;
                index++;
            } else if (current == '"' || current == '\'') {
                quote = current;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && depth > 0) {
                depth--;
                if (depth == 0) lastRule = index;
            }
        }
        return lastRule < 0 ? "" : text.substring(0, lastRule + 1);
    }
}
