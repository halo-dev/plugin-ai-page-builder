package run.halo.aipagebuilder.content;

import run.halo.aipagebuilder.model.PageDocument;
import run.halo.aipagebuilder.theme.ThemeStyleContext;

/** Source edits produce validated documents; callers own staging and persistence. */
public final class PageSourceEdits {
    private final ContentPolicy policy;

    public PageSourceEdits(ContentPolicy policy) {
        this.policy = policy;
    }

    public PreparedEdit prepare(PageDocument base, String html, String css, String js) {
        var cssChanged = !css.equals(base.css());
        var changed = !html.equals(base.html()) || cssChanged || !js.equals(base.js());
        var candidate = changed
            ? new PageDocument(js.isEmpty() ? 1 : 2, "code", null, null, html, css,
                base.title(), base.description(), js)
            : base;
        policy.validate(candidate);
        return new PreparedEdit(candidate, changed, cssChanged);
    }

    /** The document is available only after the applicable theme check has completed. */
    public final class PreparedEdit {
        private final PageDocument candidate;
        private final boolean sourceChanged;
        private final boolean cssChanged;

        private PreparedEdit(PageDocument candidate, boolean sourceChanged, boolean cssChanged) {
            this.candidate = candidate;
            this.sourceChanged = sourceChanged;
            this.cssChanged = cssChanged;
        }

        public boolean sourceChanged() {
            return sourceChanged;
        }

        public boolean cssChanged() {
            return cssChanged;
        }

        public PageDocument finish(ThemeStyleContext theme) {
            if (cssChanged) {
                policy.validateThemeColorMode(candidate.css(), theme);
            }
            return candidate;
        }
    }
}
