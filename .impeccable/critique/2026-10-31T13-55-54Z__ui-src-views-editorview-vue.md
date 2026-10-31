---
target: the page workbench (EditorView)
total_score: 28
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 2
target_identity: "file:ui/src/views/EditorView.vue"
target_fingerprint: "sha256:86e96acccca40f530acb17a2c8631eb4cb9e3262c1e468589ae474115d5b8271"
target_path: ui/src/views/EditorView.vue
timestamp: 2026-10-31T13-55-54Z
slug: ui-src-views-editorview-vue
closed: true
---
# Critique — AI Page Builder · Page Workbench (EditorView)

Method: dual-agent (A: independent design review · B: detector + browser evidence)

## Design Health Score

| # | Heuristic | Score | Key Issue |
|---|-----------|-------|-----------|
| 1 | Visibility of System Status | 3 | Save, validation, generation, and publish states are well covered; but the theme and layout state is completely invisible, and "save" and "publish" are two unlabeled status axes |
| 2 | Match System / Real World | 3 | Domain copy is excellent; but English strings leak into the Chinese UI ("Network Error", "5 days ago", "10/26/2026, 4:34:59 PM"), and "Prohibit public access" is a double negative |
| 3 | User Control and Freedom | 3 | Undo/redo, stop/steer, confirm, and deselect are complete; but the inspector does not move focus when opened, and Escape does not close it |
| 4 | Consistency and Standards | 3 | Console components and internal patterns are consistent; role=tab is misused, title and tooltip are mixed, and two greens carry different meanings |
| 5 | Error Prevention | 3 | Validation, script warnings, 409s, and the leave guard are strong; canvas delete has no confirmation, and the annotation limit and missing model are silently disabled |
| 6 | Recognition Rather Than Recall | 2 | Seven header icons rely on tooltips, canvas editability is invisible, and the model gate has no hint |
| 7 | Flexibility and Efficiency | 3 | Shortcuts, source/runtime views, device preview, presets, and steering; no session rename, no model provenance, no arbitrary two-revision comparison |
| 8 | Aesthetic and Minimalist Design | 3 | Restrained composition and consistent semantic color; 10–13px low-contrast metadata and a very long gray process stream hurt scanning |
| 9 | Error Recovery | 2 | The in-chat recovery card is good; the top-level load error is a blank page plus English, with no way to diagnose it |
| 10 | Help and Documentation | 3 | Settings helper, script notes, history notes, toasts, and tooltips are all present; there is no in-workbench help entry, and touch users cannot reach tooltips |
| **Total** | | **28/40** | **Good (70%, lower end of the 28–35 band)** |

## Design Specificity Verdict

**Verdict: authored for this product — but "product specificity" lives mainly in the workflow and copy; the visual shell still carries obvious generic traits of the AI-editor category.**

Evidence for "authored for this product":

- Theme context is a first-class citizen: the canvas renders with the current theme, offers "theme default mode / simulate light / simulate dark", shows a dedicated amber banner when the draft theme and site theme differ, and records "Theme: Ethereal" in revision history.
- Working-copy and checkpoint semantics reach the UI: tool rows are named after domain actions such as "Read theme files", "Set page title", "Set page path", and "Update page content", and distinguish "change passed validation and is staged" from "this round's draft was saved"; the recovery card states that "progress is preserved and not yet written to the draft" and offers "continue generating / view progress / regenerate".
- Publish and history safety constraints come from real constraints: the publish popper previews the final public URL; pages with scripts show an amber warning and require a second confirmation; previews run in an isolated iframe; the history restore copy states that it "copies into a new draft, and the published content and page properties are unchanged".
- Empty-state presets are not a generic "write a landing page" but industry page types such as company introduction, pricing and quotes, and locations and branches; the prompt repeatedly requires not inventing prices, customers, qualifications, or team members.

Still category-generic:

- The split pane (chat + canvas), sparkles empty state, segmented toolbar, gray chips, and 13px text are all common grammar in AI builders. If the page inside the iframe were replaced with a document, this shell could pass for any AI editor.
- "Theme-first" is the product's first principle, yet the workbench chrome never shows the current theme name or whether it is "reusing the theme layout" or "rendering standalone". The target page is precisely a standalone page with `themeLayout=false`, and nothing outside the canvas says so; the theme name only appears in revision metadata.

**Deterministic scan (Assessment B):**

- CLI: 3 findings across 2 files. `EditorView.vue` itself: 0.
  - `ui/src/components/chat/ChatDisclosureRow.vue:120` — `gradient-text` (warning, slop), `background-clip: text` plus a gradient.
  - `ui/src/components/AiPanel.vue:277/280` — `bounce-easing` ×2 (advisory, slop), `animate-bounce`; line 282 has a third occurrence that was not counted (under-count).
- Browser overlay: 7 anti-patterns.
  - `low-contrast` ×6: 2 at 4.4:1 (the "Unpublished" chip and the active "Source" tab), 4 at 2.5:1 (the "Thinking" disclosure, "This round's draft was saved" ×2, and the composer placeholder).
  - `em-dash-overuse` ×1: 8 em dashes, all inside AI-generated chat content.
- False positives and context:
  - `gradient-text` is a functional shimmer for the running-state disclosure, not a decorative gradient headline; by intent it leans toward a false positive.
  - `bounce-easing` is really a three-dot loading indicator, so the rule's semantics are off; it is also only advisory.
  - `em-dash-overuse` comes entirely from AI-generated content, not the editor UI chrome; the Chinese "——" is standard punctuation, counted as two characters.
  - The two 4.4:1 findings are marginal violations; the four 2.5:1 findings are real AA failures, not false positives.

**Visual overlays:** injection into the evaluation browser succeeded; the overlay screenshot is saved at `/tmp/impeccable-critique-b/overlay.png`. The evaluation session is closed and no live overlay is currently visible.

## Overall Impression

This is a credible, restrained editing workbench with unusually complete state coverage; the AI operation log and publish safety design are especially mature. The biggest problem is not a lack of features but that **the most important differentiators are hidden**: the canvas looks like a read-only preview, and theme/layout state exists only in the settings modal. The second-biggest problem is missing Chinese copy and recovery paths in the entry and network-error segments. The single biggest opportunity: make the editable canvas and the theme signature visible in the chrome.

## What's Working

1. **Auditable AI operation log + recovery model**: tool rows use the page's domain vocabulary to show each step and distinguish staging from draft saves; the recovery card states that progress is preserved and not yet written to the draft and offers continue/view/regenerate. Users can see what the AI did and resume after a failure.
2. **Pre-publish safety design**: the publish popper shows the final public URL first; pages with scripts show an amber warning and require a second confirmation; the history restore copy explicitly says online content and page properties are unaffected; token previews and runtime previews both use isolated sandboxes.
3. **State and canvas protection**: saving / unsaved / saved, validating, streaming read-only preview, preserving the last valid canvas after a failure, and the theme-mismatch banner — every high-risk state has visible feedback.

## Priority Issues

### [P1] The canvas looks like a preview; core edit/annotate capabilities are hidden, and keyboard and mobile are nearly unusable

- **What**: The edit canvas has no hover outline, crosshair cursor, or first-run hint; the inspector appears only after an element is clicked. The keyboard cannot select canvas elements; the inspector (`role="dialog"`) leaves focus on the "Edit" tab when opened, and Escape does not close it (verified). On mobile it still defaults to "Desktop 1280px", scaling the whole page to 29%, and device switching is three 28px icons.
- **Why it matters**: Canvas annotation is the second pillar in PRODUCT.md and the differentiator from ordinary chat-based generators; if users cannot see it, they will not use it. Keyboard users are completely excluded from the "let AI modify" flow; phone users see an unreadable, untappable thumbnail of a desktop page.
- **Fix**: Add a hover outline and crosshair to the editable canvas; show a one-time "click any element to edit or add an annotation" hint on first entry; add keyboard-reachable element tree/parent/child navigation for element selection; move focus into the inspector when it opens and allow Escape to close it; default to the mobile device on small screens, turn device switching into a labeled control, and make touch targets ≥44px.
- **Suggested command**: `/impeccable onboard` (mobile follow-up `/impeccable adapt`)

### [P1] Top-level load errors are a blank page plus raw English, with no diagnosis or recovery path

- **What**: When the page-detail request is intercepted, the workbench is left with only the "Page Workbench" title, a red "Network Error" bar, an English toast ("Network error, please check your connection"), and "Load latest version" / close; there is no page identity, no Chinese explanation, and no "Back to list".
- **Why it matters**: A Chinese product exposes framework English at its highest-risk entry point; a blank page makes users think their data is lost. Offline and flaky-network conditions are real scenarios, and error recovery scores only 2/4.
- **Fix**: Map network and HTTP errors to actionable Chinese copy ("Network connection failed; check your connection and retry", "Page not found / no permission"); keep the page title and a back-to-list path in the error state; provide both "Retry" and "Back to list"; remove the duplicate English toast or unify it to Chinese.
- **Suggested command**: `/impeccable harden`

### [P2] "Select a model" is a silent gate, and the model does not belong to the session or page

- **What**: When an existing session is open, the model selector shows "Select model" and the send button is disabled, but nothing explains that a model must be selected. The model lives in browser localStorage and does not survive session recovery; neither the history nor the recovery card shows the current or original model.
- **Why it matters**: It directly blocks the core task; for power users, there is no way to know which model produced a given generation or revision, and the recovery card says "use the original model" while the interface shows a different or empty model, which destroys trust.
- **Fix**: Remember the model per page or session; show a "Select a model before sending" line above the composer when none is set; write the model used by this run into the run status and history metadata; when recovering, explicitly show "will continue with model X".
- **Suggested command**: `/impeccable clarify`

### [P2] Theme/layout state is invisible, and the two status axes interfere with each other

- **What**: The target page has "Reuse theme layout" unchecked (standalone render), but the header only says "Unpublished" and the toolbar only says "Saved"; the theme name appears only in history metadata. The header's "Unpublished / Unpublished changes / Published" and the toolbar's "Saving / Unsaved / Saved" are two unlabeled status axes; when a published page has unsaved changes, both turn amber.
- **Why it matters**: Theme-first is the product's first principle; users cannot confirm in the chrome whether the page actually uses the theme, and standalone rendering is a major state hidden inside settings. The two status axes make users misread "saved = published" or wonder what amber refers to.
- **Fix**: Add a clickable status cluster to the canvas toolbar or header: "Draft: Saved · Publish: Unpublished · Theme: Ethereal (reuse layout / standalone render)"; add explicit prefixes to both axes; link theme/layout directly to page settings.
- **Suggested command**: `/impeccable shape`

### [P2] The AI operation log's type and contrast are unfit for reading and fail AA

- **What**: Thinking/tool rows use 13px `#9ca3af` (about 2.54:1 on white); history topic names are 10px and times/sources 11px; multiple places use text-gray-400 (15 occurrences measured) for key supporting information such as "this round's draft was saved", "5 days ago", and "auto-validated after editing". The "Unpublished" chip and the active tab are at 4.4:1, slightly below AA.
- **Why it matters**: The operation log is this product's audit trail; 2.54:1 for normal 13px text is clearly non-compliant and becomes hard to scan after long generations. 10px metadata is too small at both 1440 and 390.
- **Fix**: Raise supporting text to gray-500/600 (≥4.5:1); metadata at least 12px and chat body up to 14px; establish a 10/12/13/14 three-tier type scale and reserve 10px for badges only; keep the compact feel of 24px line height, but never trade contrast for density.
- **Suggested command**: `/impeccable typeset`

## Persona Red Flags

**Alex (Power User)**

- Model data lives in localStorage rather than the session/page; switching sessions does not restore the model; "Session actions" only offers delete, not rename; the session dropdown shows no time, message count, or model.
- History/recovery does not show model provenance; it cannot answer "which model generated this revision".
- The canvas can only be selected with a mouse, the inspector cannot be closed with Escape, and elements cannot be traversed quickly with the keyboard; every time "Validating source…" appears in the source view, the overlay covers the HTML/CSS/JS switcher.
- No command palette; shortcuts are limited to undo/redo/save; for non-script pages, "Update publish" takes effect on a single click with no confirmation.

**Jordan (First-Timer)**

- First screen: chat auto-scrolls to the end of a long message, filling the screen with gray "Thinking" rows; the model selector says "Select model" and send is disabled, with no explanation.
- The canvas looks like a static preview; there is no indication that elements can be clicked, or that "Let AI modify" is hidden behind a click.
- The seven header icons have no visible labels; "Unpublished" and "Saved" sit side by side, making it easy to think the page is not saved.
- The page settings "Prohibit public access" is a double negative; "Reuse theme layout" is unchecked, but nothing anywhere says "this page renders standalone".
- "Theme default mode" is an icon cycling button, and nothing changes visually on this standalone page, so it looks broken.
- English dates/relative times in history are mixed into the Chinese interface; a failed load is the English "Network Error".

**Sam (Accessibility / Keyboard / Screen Reader)**

- Canvas elements can only be selected with a mouse; clicks on links inside the iframe are intercepted, and keyboard users cannot enter the iframe and complete selection/annotation; there is no element-list alternative.
- After the inspector `role="dialog"` opens, focus does not enter it, there is no focus trap, and Escape does not close it.
- ApbSegmented uses `role=tablist/tab`, but arrow keys do not switch, and there is no `aria-controls`/`role=tabpanel`; the three device options are really toggles but are read as tabs; two of the three tablists have no aria-label.
- The 13px `#9ca3af` process rows are 2.54:1; 10px history metadata; the placeholder is also gray-400; none meet AA.
- Touch targets: header icons are 32×32, session buttons/device tabs are 28×28, and thinking rows are 24px high, below the recommended 44px.
- Each "Thinking" button's accessible name is the full summary, so tabbing through them makes the screen reader read extremely long strings.

## Minor Observations

- The source-view validation overlay is absolutely positioned and covers the HTML/CSS/JS segmented control; in the invalid state it keeps covering it, blocking file switching.
- The title/path exist in both "Page settings" and the publish popper with different semantics (immediate effect vs. value at publish time), so users can easily change only one.
- History dates/relative times use the browser locale, producing English dates in a Chinese plugin; fix to zh-CN or follow the Console language.
- Long titles are truncated by `max-w-56 truncate` in the header with no title/tooltip, so a 200-character title cannot be read in full in the chrome.
- When the annotation limit of 5 is reached, "Let AI modify" is disabled with no explanation that "the maximum is 5; remove one first".
- The header's "Preview" and the public-link chip on a published page both use external-link icons, which look similar but mean different things.
- "Theme default mode / simulate light / simulate dark" is an icon cycle with no menu or visible state.
- History topic names are 10px and metadata 11px; the final URL in the publish popper is 12px low-contrast gray text, even though it is one of the most important pieces of information before publishing.
- The session dropdown shows only titles; no time, message count, or model, so there is no way to judge which one to choose.
- "Save" silently disables when there are no changes, with no "No changes" tooltip; first-time users may click it repeatedly.
- ChatDisclosureRow handles `prefers-reduced-motion`, but the chat "three dots" `animate-bounce` and the loading spinner do not.
- The mobile virtual keyboard may cover the bottom composer (not verified on a real device; flagged for testing).

## Questions to Consider

1. If canvas annotation is the core differentiator, why does it look visually identical to a read-only preview? Which affordance would make a first-time user actively click into the canvas within 5 seconds?
2. Since "theme-first" is the first principle, why do "reuse theme layout / standalone render" and the current theme name exist only in the settings modal and history metadata?
3. Is the operation log meant to be read or scanned? If read, are 2.54:1 and 13px reasonable? If scanned, why does it take the largest vertical area in the panel?
4. Is the model a property of the page, the session, or the browser? If two sessions used different models, how can users know which result is better?
5. On a phone, is this scene "previewing a page" or "editing a page"? If previewing, why default to 1280px/29%? If editing, why is device switching three 28px icons?
6. Should updating a published page always take a single click? When "dirty/invalid" already disables the button, is a confirmation still needed at the moment of publishing to show "this will overwrite the live version"?
