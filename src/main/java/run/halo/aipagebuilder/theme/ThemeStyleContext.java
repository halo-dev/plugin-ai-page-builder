package run.halo.aipagebuilder.theme;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Schema(name = "ThemeStyleContext", requiredProperties = {"layoutState", "sources", "notes", "previewCss",
    "colorMode"})
public record ThemeStyleContext(String themeName, String displayName, String layoutState,
    List<ThemeContextExtractor.Source> sources, List<String> notes, String previewCss,
    ColorMode colorMode) {

    public ThemeStyleContext(String themeName, String displayName, String layoutState,
        List<ThemeContextExtractor.Source> sources, List<String> notes, String previewCss) {
        this(themeName, displayName, layoutState, sources, notes, previewCss, ColorMode.unknown());
    }

    @Schema(name = "ThemeColorMode", requiredProperties = {"strategy", "rootStates", "evidence"})
    public record ColorMode(String strategy, List<RootState> rootStates, List<String> evidence) {
        public static ColorMode unknown() {
            return new ColorMode("unknown", List.of(), List.of());
        }
    }

    @Schema(name = "ThemeRootState", requiredProperties = {"mode", "selector", "classes", "attributes"})
    public record RootState(String mode, String selector, List<String> classes,
        Map<String, String> attributes) {
        private static final Pattern CONDITION = Pattern.compile(
            "^(?:html|:root)(?:\\.(dark|light)|\\[data-theme\\s*=\\s*(['\"]?)(dark|light)\\2\\])$");

        /** Only explicit document-root conditions; a bare .dark class is not evidence. */
        public static RootState fromSelector(String selector) {
            var match = CONDITION.matcher(selector);
            if (!match.matches()) return null;
            var mode = match.group(1) == null ? match.group(3) : match.group(1);
            return new RootState(mode, selector,
                match.group(1) == null ? List.of() : List.of(mode),
                match.group(1) == null ? Map.of("data-theme", mode) : Map.of());
        }
    }

    public static ThemeStyleContext unavailable() {
        return new ThemeStyleContext(null, null, "UNAVAILABLE", List.of(),
            List.of("主题样式暂不可读取，请保留页面独立的基础样式，并以主题预览为准。"), "");
    }

    /** Page setting: render without the theme layout, so no theme styles or variables exist. */
    public static ThemeStyleContext standalone() {
        return new ThemeStyleContext(null, null, "STANDALONE", List.of(), List.of(
            "此页面设置为独立渲染，不套用主题 layout：没有站点 header/footer、主题基础样式或 CSS 变量，"
                + "必须提供完整自包含的样式（盒模型、字体栈、配色与响应式规则）。",
            "无主题亮暗模式契约；如需暗色适配可自行使用 prefers-color-scheme。"), "");
    }
}
