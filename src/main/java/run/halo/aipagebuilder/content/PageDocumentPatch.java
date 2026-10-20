package run.halo.aipagebuilder.content;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import run.halo.aipagebuilder.ai.DocumentHash;
import run.halo.aipagebuilder.model.PageDocument;

/** Pure, atomic exact-text patching shared by internal and external agents. */
public final class PageDocumentPatch {
    private PageDocumentPatch() { }

    public static Map<String, String> apply(PageDocument document, Map<String, Object> input) {
        if (input == null || !(input.get("baseHash") instanceof String hash)
            || !DocumentHash.of(document.html(), document.css(), document.js()).equals(hash)) {
            throw new IllegalArgumentException("baseHash 与当前工作副本不一致，请重新读取页面");
        }
        if (!(input.get("patches") instanceof List<?> patches)
            || patches.isEmpty() || patches.size() > 20) {
            throw new IllegalArgumentException("patches 必须包含 1–20 项修改");
        }
        var replacements = new ArrayList<Replacement>();
        for (var entry : patches) {
            if (!(entry instanceof Map<?, ?> patch)
                || !(patch.get("field") instanceof String field)
                || !("html".equals(field) || "css".equals(field) || "js".equals(field))
                || !(patch.get("oldText") instanceof String oldText) || oldText.isEmpty()
                || !(patch.get("newText") instanceof String newText)) {
                throw new IllegalArgumentException("每项修改须指定 html/css/js 字段、非空 oldText "
                    + "和字符串 newText");
            }
            var original = switch (field) {
                case "html" -> document.html();
                case "css" -> document.css();
                default -> document.js();
            };
            var start = original.indexOf(oldText);
            if (start < 0 || original.indexOf(oldText, start + 1) >= 0) {
                throw new IllegalArgumentException("oldText 必须在原始字段中精确且唯一匹配");
            }
            var end = start + oldText.length();
            for (var replacement : replacements) {
                if (replacement.field().equals(field)
                    && start < replacement.end() && replacement.start() < end) {
                    throw new IllegalArgumentException("同批修改的匹配区间不能重叠");
                }
            }
            replacements.add(new Replacement(field, start, end, newText));
        }
        var html = new StringBuilder(document.html());
        var css = new StringBuilder(document.css());
        var js = new StringBuilder(document.js());
        replacements.sort(Comparator.comparingInt(Replacement::start).reversed());
        for (var replacement : replacements) {
            var target = switch (replacement.field()) {
                case "html" -> html;
                case "css" -> css;
                default -> js;
            };
            target.replace(replacement.start(), replacement.end(), replacement.newText());
        }
        return Map.of("html", html.toString(), "css", css.toString(), "js", js.toString());
    }

    private record Replacement(String field, int start, int end, String newText) { }
}
