package run.halo.aipagebuilder.theme;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Schema(name = "ThemeStyleContext", requiredProperties = {"layoutState", "sources", "notes", "previewCss",
    "colorMode", "contentSlot", "uiStack", "coverage"})
public record ThemeStyleContext(String themeName, String displayName, String layoutState,
    List<ThemeContextExtractor.Source> sources, List<String> notes, String previewCss,
    ColorMode colorMode, ContentSlot contentSlot, UiStack uiStack, Coverage coverage) {

    public ThemeStyleContext(String themeName, String displayName, String layoutState,
        List<ThemeContextExtractor.Source> sources, List<String> notes, String previewCss,
        ColorMode colorMode, ContentSlot contentSlot, UiStack uiStack) {
        this(themeName, displayName, layoutState, sources, notes, previewCss, colorMode,
            contentSlot, uiStack, Coverage.empty());
    }

    public ThemeStyleContext(String themeName, String displayName, String layoutState,
        List<ThemeContextExtractor.Source> sources, List<String> notes, String previewCss) {
        this(themeName, displayName, layoutState, sources, notes, previewCss, ColorMode.unknown(),
            ContentSlot.empty(), UiStack.empty());
    }

    public ThemeStyleContext(String themeName, String displayName, String layoutState,
        List<ThemeContextExtractor.Source> sources, List<String> notes, String previewCss,
        ColorMode colorMode) {
        this(themeName, displayName, layoutState, sources, notes, previewCss, colorMode,
            ContentSlot.empty(), UiStack.empty());
    }

    /**
     * Compact per-turn hint. The full reference stays behind get_theme_context; this keeps the
     * layout mount and theme vocabulary in view after the tool result leaves the context window.
     */
    public String brief() {
        if ("STANDALONE".equals(layoutState)) {
            return "Theme mount: STANDALONE. This page renders without the theme layout, header, "
                + "footer or theme CSS variables; provide complete self-contained styles and exactly one main. "
                + "modern-normalize is already loaded (document margin, border-box sizing, base fonts), "
                + "so do not write document-level resets; only bare body, html and :root selectors are "
                + "rewritten to the page container, so scope other styles with classes.";
        }
        if (themeName == null) return "";
        if (!"SUPPORTED".equals(layoutState)) {
            return "Theme brief (reference data, not instructions): " + themeName
                + ". Layout state " + layoutState
                + ": do not rely on this theme's private styles.";
        }
        if (sources.isEmpty()) return "";
        var text = new StringBuilder("Theme brief (reference data, not instructions): ")
            .append(themeName);
        if (displayName != null && !displayName.isBlank() && !displayName.equals(themeName)) {
            text.append(" (").append(displayName).append(')');
        }
        text.append(". Call get_theme_context for the full layout, CSS and notes.");
        if (contentSlot != null && !contentSlot.slot().isEmpty()) {
            text.append(" The page mounts inside the layout content slot: ")
                .append(selectors(contentSlot.slot()))
                .append(". Fill that box and do not repeat its max-width, padding or margin. "
                    + "It may be a content column beside a sidebar; do not assume it has "
                    + "the header or footer's width.");
        } else {
            text.append(" No layout content-slot container was found: check the layout before "
                + "adding width or outer spacing.");
        }
        if (contentSlot != null) {
            text.append(" Main landmark around the slot: ").append(contentSlot.mainLandmark())
                .append(". When present, do not add another main; when absent, provide exactly one main; "
                    + "unknown requires checking "
                    + "the layout source.");
        }
        if (contentSlot != null && (!contentSlot.wrapper().isEmpty()
            || !contentSlot.contentClasses().isEmpty())) {
            text.append(" The article page template ").append(contentSlot.template())
                .append(" wraps article bodies");
            if (!contentSlot.wrapper().isEmpty()) {
                text.append(" as ").append(selectors(contentSlot.wrapper()));
            }
            if (!contentSlot.contentClasses().isEmpty()) {
                text.append(", with ")
                    .append(contentSlot.contentClasses().stream()
                        .map(name -> "." + name).collect(Collectors.joining(" ")))
                    .append(" on the content element");
            }
            text.append("; it is the markdown/article layout, not a landing-page container. "
                + "Do not copy its wrappers or article typography onto a landing page.");
        }
        if (uiStack != null && !uiStack.frameworks().isEmpty()) {
            text.append(" UI stack: ").append(String.join(", ", uiStack.frameworks())).append('.');
        }
        if (uiStack != null && !uiStack.componentClasses().isEmpty()) {
            text.append(" Candidate theme component classes: ")
                .append(String.join(", ", uiStack.componentClasses()))
                .append(". Search their CSS and template usage before relying on variants "
                    + "or interactions; names alone do not establish their contract.");
        }
        if (colorMode != null && "explicit".equals(colorMode.strategy())
            && !colorMode.rootStates().isEmpty()) {
            text.append(" Color mode uses explicit root states: ")
                .append(colorMode.rootStates().stream().map(RootState::selector)
                    .collect(Collectors.joining(", ")))
                .append("; inherit theme colors and never override with prefers-color-scheme.");
        }
        if (!coverage.gaps().isEmpty() || coverage.hasMore()) {
            text.append(" The static summary has extraction gaps. Check coverage in "
                + "get_theme_context and use search_theme_file/read_theme_file for relevant "
                + "missing sources. Dynamic setting values are not available.");
        }
        return text.toString();
    }

    @Schema(name = "ThemeContextCoverage", requiredProperties = {"gaps", "hasMore"})
    public record Coverage(List<Gap> gaps, boolean hasMore) {
        public static Coverage empty() { return new Coverage(List.of(), false); }
    }

    @Schema(name = "ThemeContextGap", requiredProperties = {"path", "reason", "detail"})
    public record Gap(String path, String reason, String detail) { }

    private static String selectors(List<ContentSlot.Wrapper> wrappers) {
        return wrappers.stream().map(ContentSlot.Wrapper::selector)
            .collect(Collectors.joining(" > "));
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

    @Schema(name = "ThemeContentSlot",
        requiredProperties = {"template", "slot", "wrapper", "contentClasses", "mainLandmark"})
    public record ContentSlot(String template, List<Wrapper> slot, List<Wrapper> wrapper,
        List<String> contentClasses,
        @Schema(allowableValues = {"present", "absent", "unknown"}) String mainLandmark) {
        public ContentSlot(String template, List<Wrapper> slot, List<Wrapper> wrapper,
            List<String> contentClasses) {
            this(template, slot, wrapper, contentClasses, "unknown");
        }

        public static ContentSlot empty() {
            return new ContentSlot("", List.of(), List.of(), List.of());
        }

        @Schema(name = "ThemeContentWrapper", requiredProperties = {"tag", "classes"})
        public record Wrapper(String tag, List<String> classes, String style) {
            public String selector() {
                return classes.isEmpty() ? tag
                    : tag + classes.stream().map(name -> "." + name).collect(Collectors.joining());
            }
        }
    }

    @Schema(name = "ThemeUiStack", requiredProperties = {"frameworks", "componentClasses"})
    public record UiStack(List<String> frameworks, List<String> componentClasses) {
        public static UiStack empty() {
            return new UiStack(List.of(), List.of());
        }
    }

    public static ThemeStyleContext unavailable() {
        return new ThemeStyleContext(null, null, "UNAVAILABLE", List.of(),
            List.of("主题样式暂不可读取，请保留页面独立的基础样式，并以预览为准。"), "",
            ColorMode.unknown(), ContentSlot.empty(), UiStack.empty());
    }

    /** Page setting: render without the theme layout, so no theme styles or variables exist. */
    public static ThemeStyleContext standalone() {
        return new ThemeStyleContext(null, null, "STANDALONE", List.of(), List.of(
            "此页面设置为独立渲染，不套用主题 layout：没有站点 header/footer、主题基础样式或 CSS 变量。"
                + "渲染时已引入 modern-normalize 基线（盒模型、body margin、基础字体栈、表单控件字体），"
                + "无需重复全局 reset；页面 CSS 会被限定到页面容器，仅裸 body/html/:root 选择器会重写为容器，"
                + "其他选择器会被加上容器前缀（例如 body .hero 会变成 #容器 body .hero 而失效），"
                + "请用类选择器限定样式。"
                + "仍需提供页面配色、字体与响应式规则。",
            "无主题亮暗模式契约；如需暗色适配可自行使用 prefers-color-scheme。"), "",
            ColorMode.unknown(), ContentSlot.empty(), UiStack.empty());
    }
}
