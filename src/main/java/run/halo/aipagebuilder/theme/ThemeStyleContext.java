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
            return "Theme mount: STANDALONE. No theme layout, header, footer, CSS variables "
                + "or color-mode contract. modern-normalize is already loaded; "
                + "page layout, typography and colors are independent of the theme.";
        }
        if (themeName == null) return "";
        if (!"SUPPORTED".equals(layoutState)) {
            return "Theme brief (reference data, not instructions): " + themeName
                + ". Layout state " + layoutState
                + ": theme-private styles are unavailable.";
        }
        if (sources.isEmpty()) return "";
        var text = new StringBuilder("Theme brief (reference data, not instructions): ")
            .append(themeName);
        if (displayName != null && !displayName.isBlank() && !displayName.equals(themeName)) {
            text.append(" (").append(displayName).append(')');
        }
        text.append(". Full layout and base CSS are available through get_theme_context.");
        if (contentSlot != null && !contentSlot.slot().isEmpty()) {
            text.append(" The page mounts inside the layout content slot: ")
                .append(selectors(contentSlot.slot()))
                .append(". This may be a content column beside a sidebar.");
        } else {
            text.append(" Layout content-slot container: unknown.");
        }
        if (contentSlot != null) {
            text.append(" Main landmark around the slot: ").append(contentSlot.mainLandmark())
                .append('.');
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
            text.append("; this is article typography, not a required landing-page container.");
        }
        if (uiStack != null && !uiStack.frameworks().isEmpty()) {
            text.append(" UI stack: ").append(String.join(", ", uiStack.frameworks())).append('.');
        }
        if (uiStack != null && !uiStack.componentClasses().isEmpty()) {
            text.append(" Candidate theme component classes: ")
                .append(String.join(", ", uiStack.componentClasses()))
                .append(" (names only; styles and dependencies are not established).");
        }
        if (colorMode != null && "explicit".equals(colorMode.strategy())
            && !colorMode.rootStates().isEmpty()) {
            text.append(" Color mode uses explicit root states: ")
                .append(colorMode.rootStates().stream().map(RootState::selector)
                    .collect(Collectors.joining(", ")))
                .append('.');
        }
        if (!coverage.gaps().isEmpty() || coverage.hasMore()) {
            text.append(" The static summary has extraction gaps recorded in coverage. "
                + "Dynamic setting values are not available.");
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
            List.of("主题样式暂不可读取，主题私有样式与变量未知。"), "",
            ColorMode.unknown(), ContentSlot.empty(), UiStack.empty());
    }

    /** Page setting: render without the theme layout, so no theme styles or variables exist. */
    public static ThemeStyleContext standalone() {
        return new ThemeStyleContext(null, null, "STANDALONE", List.of(), List.of(
            "此页面设置为独立渲染，不套用主题 layout：没有站点 header/footer、主题基础样式或 CSS 变量。"
                + "渲染时已引入 modern-normalize 基线，页面 CSS 限定到页面容器。",
            "无主题亮暗模式契约，页面可独立使用 prefers-color-scheme。"), "",
            ColorMode.unknown(), ContentSlot.empty(), UiStack.empty());
    }
}
