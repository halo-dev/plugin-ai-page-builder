package run.halo.aipagebuilder.content;

import com.helger.css.decl.AbstractHasTopLevelRules;
import com.helger.css.decl.CSSMediaRule;
import com.helger.css.decl.CSSSelector;
import com.helger.css.decl.CSSSelectorSimpleMember;
import com.helger.css.decl.CSSStyleRule;
import com.helger.css.decl.CSSSupportsRule;
import com.helger.css.decl.CascadingStyleSheet;
import com.helger.css.decl.ECSSSelectorCombinator;
import com.helger.css.reader.CSSReader;
import com.helger.css.reader.errorhandler.ThrowingCSSParseErrorHandler;
import com.helger.css.writer.CSSWriter;
import java.net.URI;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import run.halo.app.infra.utils.JsonUtils;
import run.halo.aipagebuilder.model.PageDocument;
import run.halo.aipagebuilder.theme.ThemeStyleContext;

@Component
public class ContentPolicy {
    private static final int MAX_HTML = 200_000;
    private static final int MAX_CSS = 100_000;
    private static final int MAX_PROJECT = 600_000;
    private static final Set<String> TAGS = Set.of("a", "abbr", "address", "article", "aside", "b", "blockquote",
        "br", "button", "caption", "cite", "code", "col", "colgroup", "dd", "del", "details", "div",
        "dl", "dt", "em", "figcaption", "figure", "footer", "h1", "h2", "h3", "h4", "h5", "h6",
        "header", "hr", "i", "img", "label", "li", "main", "mark", "nav", "ol", "p", "pre", "q", "s",
        "section", "small", "span", "strong", "sub", "summary", "sup", "table", "tbody", "td",
        "tfoot", "th", "thead", "time", "tr", "u", "ul");
    private static final Set<String> ATTRIBUTES = Set.of("id", "class", "title", "style", "lang",
        "dir", "role", "tabindex", "hidden", "href", "target", "rel", "src", "alt", "width",
        "height", "loading", "decoding", "colspan", "rowspan", "scope", "start", "reversed",
        "type", "open", "datetime", "cite", "span", "disabled");
    private static final Pattern CSS_URL = Pattern.compile("(?i)url\\(\\s*(['\"]?)(.*?)\\1\\s*\\)");
    private static final Pattern HTML_SHELL = Pattern.compile("(?is)<\\s*/?\\s*(?:html|head|body|!doctype)(?:\\s|>)");

    public void validate(PageDocument document) {
        require(document != null, "缺少页面内容");
        require(document.schemaVersion() == 1 || document.schemaVersion() == 2, "不支持的页面格式版本");
        require(document.js().isEmpty() || document.schemaVersion() == 2,
            "包含 JavaScript 的页面须使用格式版本 2");
        require("visual".equals(document.mode()) || "code".equals(document.mode()), "不支持的编辑模式");
        require(document.title() != null && !document.title().isBlank()
            && document.title().length() <= 200, "页面标题须为 1–200 个字符");
        require(document.description() == null || document.description().length() <= 1000,
            "页面描述不能超过 1000 个字符");
        validateHtml(document.html());
        validateCss(document.css());
        validateJs(document.js());
        if (document.projectData() != null) {
            require(JsonUtils.objectToJson(document.projectData()).length() <= MAX_PROJECT,
                "可视化编辑数据超过 600 KB 限制");
            validateProject(document.projectData(), 0, false);
        }
        require(!"visual".equals(document.mode()) || document.projectData() != null,
            "可视化页面缺少编辑数据");
    }

    public void validateHtml(String html) {
        parseAndValidateHtml(html);
    }

    /**
     * Validates the fragment and returns the DOM serialization used for rendering.
     * Rendering the parsed DOM instead of the original source prevents differences between
     * Jsoup's parse and the browser's parse from introducing unchecked markup.
     */
    public String sanitizeHtml(String html) {
        var body = parseAndValidateHtml(html);
        // Serialize the parsed DOM as-is: Jsoup's default pretty printing collapses
        // whitespace that CSS (for example white-space: pre) renders literally.
        body.ownerDocument().outputSettings().prettyPrint(false);
        // HTML parsers drop one leading newline inside <pre>, including one produced by a
        // character reference. Jsoup only drops a literal newline, so compensate only when
        // the source really has one; otherwise an entity would gain an extra line.
        for (var pre : body.select("pre")) {
            if (pre.childNodeSize() > 0 && pre.childNode(0) instanceof TextNode text
                && text.getWholeText().startsWith("\n") && literalLeadingNewline(html, pre)) {
                pre.prependChild(new TextNode("\n"));
            }
        }
        for (var link : body.select("a[target=_blank]")) {
            var rel = link.attr("rel");
            var safe = false;
            for (var token : rel.split("\\s+")) {
                if ("noopener".equalsIgnoreCase(token) || "noreferrer".equalsIgnoreCase(token)) {
                    safe = true;
                }
            }
            if (!safe) {
                link.attr("rel", rel.isBlank() ? "noopener" : rel + " noopener");
            }
        }
        return body.html();
    }

    private Element parseAndValidateHtml(String html) {
        require(html != null && html.length() <= MAX_HTML, "正文不能超过 200 KB");
        require(!HTML_SHELL.matcher(html).find(), "仅支持正文片段，请移除 html/head/body 外壳");
        var parser = org.jsoup.parser.Parser.htmlParser().setTrackPosition(true);
        var body = org.jsoup.nodes.Document.createShell("").body();
        for (var node : parser.parseFragmentInput(html, body, "")) {
            body.appendChild(node);
        }
        for (var element : body.getAllElements()) {
            if (element == body) {
                continue;
            }
            require(TAGS.contains(element.normalName()), "不支持的 HTML 元素：" + element.normalName());
            for (var attribute : element.attributes()) {
                validateAttribute(attribute.getKey(), attribute.getValue());
            }
        }
        return body;
    }

    /** True when the source has a literal newline right after this pre's start tag. */
    private static boolean literalLeadingNewline(String html, Element pre) {
        var range = pre.sourceRange();
        if (!range.isTracked()) return false;
        // endPos is the first character after the start tag, so this does not need a
        // hand-written scan of (possibly malformed) attributes.
        var index = range.endPos();
        if (index >= html.length()) return false;
        var next = html.charAt(index);
        return next == '\n' || next == '\r';
    }

    public void validateCss(String css) {
        parseCss(css);
    }

    /** Size/transport validation only; published JavaScript executes directly in the theme DOM. */
    public void validateJs(String js) {
        require(js != null && js.length() <= 100_000, "JavaScript 不能超过 100000 个字符");
        require(!js.contains("\u0000"), "JavaScript 不能包含 NUL 字符");
    }

    /** Call when generated CSS changes; unchanged legacy CSS may accompany a text-only patch. */
    public void validateThemeColorMode(String css, ThemeStyleContext context) {
        if (context == null || context.colorMode() == null
            || !"explicit".equals(context.colorMode().strategy())) return;
        require(!hasSystemColorCondition(parseCss(css)),
            "主题使用显式根状态控制亮暗模式，请移除 prefers-color-scheme，优先引用主题变量或使用 colorMode.rootStates 中的根条件；不要覆盖主题变量定义。");
    }

    private boolean hasSystemColorCondition(AbstractHasTopLevelRules container) {
        for (var rule : container.getAllRules()) {
            if (rule instanceof CSSMediaRule media && media.getAllMediaQueries().stream()
                .flatMap(query -> query.getAllMediaExpressions().stream())
                .anyMatch(expression -> "prefers-color-scheme".equalsIgnoreCase(expression.getFeature()))) {
                return true;
            }
            if (rule instanceof AbstractHasTopLevelRules nested && hasSystemColorCondition(nested)) {
                return true;
            }
        }
        return false;
    }

    public String renderCss(String css, String rootId) {
        require(rootId != null && rootId.matches("[a-zA-Z][a-zA-Z0-9_-]*"), "无效的页面根节点");
        var sheet = parseCss(css);
        scopeRules(sheet, rootId);
        return new CSSWriter().setWriteHeaderText(false).setWriteFooterText(false)
            .getCSSAsString(sheet);
    }

    private CascadingStyleSheet parseCss(String css) {
        require(css != null && css.length() <= MAX_CSS, "CSS 不能超过 100 KB");
        // Reject escapes so URL/property checks cannot be bypassed with CSS escape sequences.
        require(!css.contains("\\") && !css.contains("<") && !css.contains("\u0000"),
            "CSS 不支持转义字符或 HTML 标签");
        var normalized = css.replaceAll("(?s)/\\*.*?\\*/", "").toLowerCase(Locale.ROOT);
        require(!normalized.contains("expression(") && !normalized.contains("javascript:")
            && !normalized.contains("-moz-binding") && !normalized.contains("behavior:")
            && !normalized.contains("image-set("), "CSS 包含不支持的可执行或外部资源语法");
        var urls = CSS_URL.matcher(normalized);
        while (urls.find()) {
            validateUrl(urls.group(2), false);
        }
        try {
            var parseProblem = new AtomicReference<String>();
            var sheet = CSSReader.readFromString(css, new ThrowingCSSParseErrorHandler(),
                error -> parseProblem.compareAndSet(null, error.getMessage()));
            require(sheet != null, "CSS 语法无效" + detail(parseProblem.get()));
            require(sheet.getAllImportRules().isEmpty() && sheet.getAllNamespaceRules().isEmpty(),
                "不支持 CSS @import 或 @namespace");
            validateRules(sheet, 0);
            return sheet;
        } catch (ResponseStatusException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "CSS 语法无效" + detail(error.getMessage()));
        }
    }

    private static String detail(String message) {
        return message == null ? "" : "：" + (message.length() > 200 ? message.substring(0, 200) : message);
    }

    private void validateRules(AbstractHasTopLevelRules container, int depth) {
        require(depth < 20, "CSS 嵌套过深");
        for (var rule : container.getAllRules()) {
            if (rule instanceof CSSStyleRule style) {
                require(style.getAllRules().isEmpty(), "请展开 CSS 嵌套选择器");
                require(!style.getAllSelectors().isEmpty(), "CSS 缺少选择器");
                for (var selector : style.getAllSelectors()) {
                    require(!(selector.getMemberAtIndex(0) instanceof ECSSSelectorCombinator),
                        "CSS 选择器不能以组合符开头");
                    rootConditionEnd(selector);
                }
                for (var declaration : style.getAllDeclarations()) {
                    require(!Set.of("behavior", "-moz-binding").contains(
                        declaration.getProperty().toLowerCase(Locale.ROOT)), "不支持的 CSS 属性");
                }
            } else if (rule instanceof CSSMediaRule media) {
                validateRules(media, depth + 1);
            } else if (rule instanceof CSSSupportsRule supports) {
                validateRules(supports, depth + 1);
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "第一版 CSS 仅支持普通样式、@media 和 @supports");
            }
        }
    }

    private void scopeRules(AbstractHasTopLevelRules container, String rootId) {
        for (var rule : container.getAllRules()) {
            if (rule instanceof CSSStyleRule style) {
                for (var selector : style.getAllSelectors()) {
                    var text = selector.getAsCSSString(new CSSWriter().getSettings(), 0).trim();
                    if (Set.of("body", "html", ":root").contains(text)) {
                        selector.removeAllMembers();
                        selector.addMember(new CSSSelectorSimpleMember("#" + rootId));
                    } else {
                        int conditionEnd = rootConditionEnd(selector);
                        if (conditionEnd > 0) {
                            selector.addMember(conditionEnd, ECSSSelectorCombinator.BLANK);
                            selector.addMember(conditionEnd + 1, new CSSSelectorSimpleMember("#" + rootId));
                        } else {
                            selector.addMember(0, ECSSSelectorCombinator.BLANK);
                            selector.addMember(0, new CSSSelectorSimpleMember("#" + rootId));
                        }
                    }
                }
            } else if (rule instanceof AbstractHasTopLevelRules nested) {
                scopeRules(nested, rootId);
            }
        }
    }

    private int rootConditionEnd(CSSSelector selector) {
        var compound = new StringBuilder();
        int end = 0;
        while (end < selector.getMemberCount()
            && !(selector.getMemberAtIndex(end) instanceof ECSSSelectorCombinator)) {
            compound.append(selector.getMemberAtIndex(end)
                .getAsCSSString(new CSSWriter().getSettings(), 0));
            end++;
        }
        var text = compound.toString();
        if (!(text.equals("html") || text.startsWith("html.") || text.startsWith("html[")
            || text.startsWith("html:") || text.startsWith("html#") || text.startsWith(":root"))) return 0;
        if (Set.of("html", ":root").contains(text)) return 0;
        require(ThemeStyleContext.RootState.fromSelector(text) != null,
            "仅支持 html/:root 的明确 dark/light class 或 data-theme 根条件");
        require(end == selector.getMemberCount()
                || selector.getMemberAtIndex(end) == ECSSSelectorCombinator.BLANK,
            "主题根条件后仅支持后代选择器，不能使用兄弟或子级组合符");
        return end;
    }

    private void validateAttribute(String name, String value) {
        var key = name.toLowerCase(Locale.ROOT);
        require(ATTRIBUTES.contains(key) || key.startsWith("aria-")
            || key.startsWith("data-") && !key.startsWith("data-gjs-"),
            "不支持的 HTML 属性：" + name);
        if (Set.of("href", "src", "cite").contains(key)) {
            validateUrl(value, "href".equals(key));
        }
        if ("style".equals(key)) {
            validateCss(".inline {" + value + "}");
        }
        if ("target".equals(key)) {
            require(Set.of("_self", "_blank").contains(value), "不支持的链接 target");
        }
    }

    private void validateUrl(String value, boolean link) {
        require(value != null && value.chars().noneMatch(c -> c < 32 || c == 127)
            && !value.contains("\\"), "URL 包含无效字符");
        try {
            var uri = URI.create(value.trim());
            var scheme = uri.getScheme();
            require(scheme == null || Set.of("https", "http").contains(scheme.toLowerCase(Locale.ROOT))
                || link && Set.of("mailto", "tel").contains(scheme.toLowerCase(Locale.ROOT)),
                "仅支持相对地址、HTTP(S) 图片和常规链接");
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL 格式无效");
        }
    }

    private void validateProject(Object value, int depth, boolean documentShell) {
        require(depth < 60, "编辑数据嵌套过深");
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                var key = String.valueOf(entry.getKey());
                var lower = key.toLowerCase(Locale.ROOT);
                var child = entry.getValue();
                if (Set.of("script", "script-export", "scriptexport", "scripts", "script-props",
                    "scriptprops", "datasources").contains(lower)) {
                    require(child == null || "".equals(child) || Boolean.FALSE.equals(child)
                        || child instanceof Collection<?> list && list.isEmpty(),
                        "编辑数据不能包含脚本");
                }
                if ("type".equals(lower) && child instanceof String type) {
                    require(!Set.of("script", "iframe", "svg", "video", "form", "input")
                        .contains(type.toLowerCase(Locale.ROOT)), "不支持的编辑器组件");
                }
                if ("tagname".equals(lower)) {
                    require(child instanceof String tag && (TAGS.contains(tag.toLowerCase(Locale.ROOT))
                        || documentShell && Set.of("html", "head", "body").contains(tag)),
                        "编辑数据包含不支持的元素");
                }
                if ("attributes".equals(lower) && child instanceof Map<?, ?> attributes) {
                    attributes.forEach((name, attribute) -> validateAttribute(String.valueOf(name),
                        String.valueOf(attribute)));
                }
                if (Set.of("content", "component", "components").contains(lower)
                    && child instanceof String content) {
                    validateHtml(content);
                }
                if ("style".equals(lower) && child instanceof Map<?, ?> styles) {
                    var declarations = new StringBuilder(".component{");
                    styles.forEach((property, expression) -> declarations.append(property)
                        .append(':').append(expression).append(';'));
                    validateCss(declarations.append('}').toString());
                }
                validateProject(child, depth + 1, Set.of("head", "docEl").contains(key));
            }
        } else if (value instanceof Collection<?> collection) {
            collection.forEach(child -> validateProject(child, depth + 1, false));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }
}
