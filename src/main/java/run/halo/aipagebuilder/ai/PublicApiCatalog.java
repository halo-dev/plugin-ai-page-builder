package run.halo.aipagebuilder.ai;

import static org.springdoc.core.utils.Constants.OPERATION_ATTRIBUTE;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.pf4j.PluginStateListener;
import org.pf4j.PluginWrapper;
import org.springdoc.core.fn.RouterFunctionData;
import org.springdoc.core.fn.builders.operation.Builder;
import org.springdoc.webflux.core.visitor.RouterFunctionVisitor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.reactive.function.server.RouterFunction;
import run.halo.app.plugin.SpringPluginManager;

@Component
public class PublicApiCatalog {
    private static final long TTL = Duration.ofSeconds(60).toNanos();
    private final SpringPluginManager manager;
    private final LongSupplier clock;
    private final AtomicLong generation = new AtomicLong();
    private final PluginStateListener listener = event -> generation.incrementAndGet();
    private final Object rebuildLock = new Object();
    private volatile Snapshot snapshot;
    private volatile boolean closed;

    @Autowired
    public PublicApiCatalog(ApplicationContext context) {
        this((SpringPluginManager) context
            .getBean("pluginWrapper", PluginWrapper.class).getPluginManager(), System::nanoTime);
    }

    PublicApiCatalog(SpringPluginManager manager, LongSupplier clock) {
        this.manager = manager;
        this.clock = clock;
        manager.addPluginStateListener(listener);
    }

    /** Public API path prefixes that sandboxed previews may call; never Console API paths. */
    public List<String> publicPathPrefixes() {
        return snapshot().publicPathPrefixes();
    }

    public Map<String, Object> search(String query, int limit) {
        var current = snapshot();
        var terms = words(query);
        var matches = current.items().stream()
            .map(item -> new Match(item, score(item, query, terms)))
            .filter(match -> terms.isEmpty() || match.score() > 0)
            .sorted(Comparator.comparingInt(Match::score).reversed()
                .thenComparing(match -> String.valueOf(match.item().get("id"))))
            .toList();
        var result = new LinkedHashMap<String, Object>();
        result.put("items", matches.stream().limit(limit).map(Match::item).toList());
        result.put("totalMatches", matches.size());
        result.put("truncated", matches.size() > limit);
        result.put("partial", current.partial());
        result.put("warnings", current.warnings());
        result.put("anonymousAccess", "unverified");
        return result;
    }

    public Map<String, Object> details(List<String> ids) {
        for (int attempt = 0; attempt < 2; attempt++) {
            ensureOpen();
            long version = generation.get();
            var scan = scan();
            var items = new ArrayList<Map<String, Object>>();
            for (var id : ids) {
                var candidates = scan.routes().get(id);
                if (candidates == null) {
                    items.add(failure(id, "not_found", "当前路由中未找到该公共接口"));
                } else if (candidates.size() > 1) {
                    items.add(failure(id, "ambiguous", "同一路径和方法存在多个接口契约"));
                } else {
                    var route = candidates.getFirst();
                    if (route.operation() == null) {
                        items.add(failure(id, "unavailable", "接口未声明可读取的契约"));
                        continue;
                    }
                    try {
                        var detail = new LinkedHashMap<>(summary(id, route, false));
                        detail.putAll(new PublicApiSchemaResolver().resolve(route.operation()));
                        if (!route.conditions().isEmpty()) {
                            detail.put("routeConditions", route.conditions());
                            detail.put("partial", true);
                            var warnings = new ArrayList<Object>((List<?>)
                                detail.getOrDefault("warnings", List.of()));
                            warnings.add("Route predicates impose additional request conditions; "
                                + "routeConditions contains visitor metadata, "
                                + "not a complete parameter schema");
                            detail.put("warnings", warnings);
                        }
                        detail.put("status", "ok");
                        detail.put("anonymousAccess", "unverified");
                        items.add(detail);
                    } catch (RuntimeException | LinkageError error) {
                        items.add(failure(id, "unavailable", "接口契约无法完整解析："
                            + bounded(error.getMessage(), 200)));
                    }
                }
            }
            ensureOpen();
            if (version == generation.get()) {
                return Map.of("items", items, "partial", scan.partial()
                    || items.stream().anyMatch(item -> !"ok".equals(item.get("status"))
                        || Boolean.TRUE.equals(item.get("partial"))),
                    "warnings", scan.warnings(), "anonymousAccess", "unverified");
            }
        }
        throw new IllegalStateException("接口目录正在变化，请重试");
    }

    private Snapshot snapshot() {
        ensureOpen();
        var current = snapshot;
        if (fresh(current)) return current;
        synchronized (rebuildLock) {
            ensureOpen();
            current = snapshot;
            if (fresh(current)) return current;
            for (int attempt = 0; attempt < 2; attempt++) {
                long version = generation.get();
                var scan = scan();
                var items = scan.routes().entrySet().stream()
                    .map(entry -> summary(entry.getKey(), entry.getValue().getFirst(),
                        entry.getValue().size() > 1)).toList();
                ensureOpen();
                if (version == generation.get()) {
                    current = new Snapshot(version, clock.getAsLong(), items,
                        scan.partial() || items.stream().anyMatch(item ->
                            !"declared".equals(item.get("contract"))), List.copyOf(scan.warnings()),
                        scan.publicPathPrefixes());
                    snapshot = current;
                    return current;
                }
            }
            throw new IllegalStateException("接口目录正在变化，请重试");
        }
    }

    private boolean fresh(Snapshot current) {
        return current != null && current.generation() == generation.get()
            && clock.getAsLong() - current.createdAt() < TTL;
    }

    private Scan scan() {
        var routes = new TreeMap<String, List<Discovered>>();
        var warnings = new ArrayList<String>();
        var prefixes = new TreeSet<String>();
        boolean partial = false;
        var beans = manager.getRootContext().getBeansOfType(RouterFunction.class);
        for (var bean : beans.entrySet()) {
            var visitor = new RouterFunctionVisitor();
            try {
                bean.getValue().accept(visitor);
            } catch (RuntimeException | LinkageError error) {
                partial = true;
                warn(warnings, "部分路由无法遍历：" + bounded(bean.getKey(), 120));
            }
            for (var data : visitor.getRouterFunctionDatas()) {
                if (!publicPath(data.getPath())) continue;
                prefixes.add(pathPrefix(data.getPath()));
                var attribute = data.getAttributes().get(OPERATION_ATTRIBUTE);
                Operation operation = null;
                try {
                    if (attribute instanceof Builder builder) operation = builder.build().getOperation();
                } catch (RuntimeException | LinkageError error) {
                    partial = true;
                    warn(warnings, "接口元数据无法读取：" + bounded(data.getPath(), 180));
                }
                if (operation != null && operation.hidden()) continue;
                if (data.getMethods() == null) {
                    partial = true;
                    continue;
                }
                for (var method : data.getMethods()) {
                    var id = method.name() + " " + data.getPath();
                    var candidates = routes.computeIfAbsent(id, ignored -> new ArrayList<>());
                    // The same router can be exposed by more than one aggregate bean.
                    if (attribute != null && candidates.stream()
                        .anyMatch(candidate -> candidate.attribute() == attribute)) continue;
                    candidates.add(new Discovered(method.name(), data.getPath(), operation,
                        attribute, conditions(data)));
                }
            }
        }
        return new Scan(routes, partial, warnings, List.copyOf(prefixes));
    }

    /** Turns a public route path into the group/version prefix shared by its siblings. */
    private static String pathPrefix(String path) {
        var parts = path.split("/", -1);
        // ["", "apis", group, version, ...]
        if (parts.length > 4) {
            return String.join("/", Arrays.copyOfRange(parts, 0, 4)) + "/";
        }
        return path.endsWith("/") ? path : path + "/";
    }

    private static Map<String, Object> conditions(RouterFunctionData data) {
        var result = new LinkedHashMap<String, Object>();
        if (data.getQueryParams() != null && !data.getQueryParams().isEmpty()) {
            result.put("queryParams", Map.copyOf(data.getQueryParams()));
        }
        if (data.getHeaders() != null && data.getHeaders().length > 0) {
            result.put("headers", List.of(data.getHeaders()));
        }
        if (data.getConsumes() != null && data.getConsumes().length > 0) {
            result.put("consumes", List.of(data.getConsumes()));
        }
        if (data.getProduces() != null && data.getProduces().length > 0) {
            result.put("produces", List.of(data.getProduces()));
        }
        return Map.copyOf(result);
    }

    static boolean publicPath(String path) {
        if (path == null) return false;
        var matcher = new AntPathMatcher();
        return matcher.match("/apis/api.*/**", path)
            && !matcher.match("/apis/api.console.*/v1alpha1/**", path)
            && !matcher.match("/apis/api.notification.halo.run/v1alpha1/userspaces/**", path)
            && !matcher.match("/apis/api.notification.halo.run/v1alpha1/notifiers/**", path);
    }

    private static Map<String, Object> summary(String id, Discovered route, boolean ambiguous) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("method", route.method());
        result.put("path", route.path());
        result.put("contract", ambiguous ? "ambiguous" : route.operation() == null ? "missing" : "declared");
        if (!ambiguous && route.operation() != null) {
            var operation = route.operation();
            result.put("operationId", bounded(operation.operationId(), 200));
            result.put("summary", bounded(operation.summary(), 400));
            result.put("description", bounded(operation.description(), 1200));
            result.put("tags", Arrays.stream(operation.tags()).limit(12)
                .map(tag -> bounded(tag, 120)).toList());
        }
        return Map.copyOf(result);
    }

    private static int score(Map<String, Object> item, String query, List<String> terms) {
        if (terms.isEmpty()) return 0;
        int score = 0;
        for (var field : List.of("operationId", "path", "tags", "summary", "description")) {
            String text = String.valueOf(item.getOrDefault(field, ""));
            if (text.equalsIgnoreCase(query.trim())) score += 100;
            var tokens = words(text);
            for (var term : terms) {
                if (tokens.contains(term)) score += field.equals("description") ? 1 : 4;
                else if (text.toLowerCase(Locale.ROOT).contains(term)) score++;
            }
        }
        return score;
    }

    private static List<String> words(String value) {
        return Arrays.stream(value.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
            .toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
            .filter(word -> !word.isBlank()).distinct().toList();
    }

    static Map<String, Object> failure(String id, String status, String message) {
        return Map.of("id", id, "status", status, "error", message);
    }

    private static String bounded(String value, int max) {
        if (value == null) return "";
        return value.substring(0, Math.min(value.length(), max));
    }

    private static void warn(List<String> warnings, String message) {
        if (warnings.size() < 10) warnings.add(message);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("接口目录已关闭");
    }

    @PreDestroy
    public void close() {
        if (closed) return;
        closed = true;
        generation.incrementAndGet();
        manager.removePluginStateListener(listener);
        synchronized (rebuildLock) { snapshot = null; }
    }

    private record Snapshot(long generation, long createdAt, List<Map<String, Object>> items,
                            boolean partial, List<String> warnings,
                            List<String> publicPathPrefixes) { }
    private record Discovered(String method, String path, Operation operation, Object attribute,
                              Map<String, Object> conditions) { }
    private record Scan(Map<String, List<Discovered>> routes, boolean partial, List<String> warnings,
                        List<String> publicPathPrefixes) { }
    private record Match(Map<String, Object> item, int score) { }
}
