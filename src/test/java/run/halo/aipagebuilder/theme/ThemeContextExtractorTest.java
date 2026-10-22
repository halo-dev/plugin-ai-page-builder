package run.halo.aipagebuilder.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import run.halo.aipagebuilder.content.ContentPolicy;

class ThemeContextExtractorTest {
    private final ContentPolicy policy = new ContentPolicy();
    private final ThemeContextExtractor extractor = new ThemeContextExtractor(policy);

    @Test
    void extractsStaticHeadFragmentAndLocalStylesWithoutBodyTextOrScripts() {
        var reads = new ArrayList<String>();
        var files = Map.of("templates/layout.html", """
            <html><head th:replace="~{modules/head :: head(${title})}"></head>
            <body><script>secretScript()</script><!-- secretComment -->
            <main class="page-shell" style="--space: 2rem"><h1>secretTitle</h1>
            <div th:replace="${content}">secretBody</div></main></body></html>
            """, "templates/modules/head.html", """
            <head th:fragment="head(title)">
              <link rel="stylesheet" th:href="@{/assets/main.css}">
            </head><div th:fragment="unused"><link href="/assets/unused.css"></div>
            """, "templates/assets/main.css", """
            :root { --ink: #123456; } body { color: var(--ink); font-family: system-ui; }
            .page-shell { max-width: 70rem; color: #456; } h1 { font-size: 2rem; }
            """);
        var result = extractor.extract(path -> {
            reads.add(path);
            return files.get(path);
        });
        assertThat(reads).containsExactly("templates/layout.html", "templates/modules/head.html",
            "templates/assets/main.css");
        assertThat(result.sources()).extracting(ThemeContextExtractor.Source::kind)
            .containsExactly("layout", "fragment", "css");
        assertThat(result.sources().getFirst().content()).contains("page-shell", "--space: 2rem", "${content}")
            .doesNotContain("secretScript", "secretComment", "secretTitle", "secretBody");
        assertThat(result.previewCss()).contains("--ink", "var(--ink)", "font-family", ".page-shell")
            .doesNotContain("max-width");
        assertThat(result.coverage().gaps()).isEmpty();
    }

    @Test
    void rejectsExternalDynamicAndEscapingPathsAndDeduplicatesStaticLinks() {
        var reads = new ArrayList<String>();
        var layout = """
            <link href="./assets/main.css?v=1#base"><link th:href="@{/assets/main.css}">
            <link href="https://evil.test/a.css"><link href="//evil.test/a.css">
            <link href="/assets/../private.css"><link href="assets/%2e%2e/private.css">
            <link th:href="@{${theme.css}}"><link href="/assets/a.css?x=${secret}">
            <link href="file:/private.css"><link href="/etc/private.css">
            <div th:replace="~{../private :: x}"></div>
            <div th:insert="~{https://evil.test/x :: x}"></div>
            """;
        var result = extractor.extract(path -> {
            reads.add(path);
            return path.equals("templates/layout.html") ? layout : "body { color: red; }";
        });
        assertThat(reads).containsExactly("templates/layout.html", "templates/assets/main.css",
            "templates/assets/a.css");
        assertThat(result.previewCss()).contains("color:red");
        assertThat(result.notes()).anyMatch(note -> note.contains("未展开"));
    }

    @Test
    void reportsMissingFragmentsCyclesAndDepthLimit() {
        var files = Map.of("templates/layout.html", """
            <div th:insert="~{modules/a :: a}"></div>
            <div th:insert="~{modules/a :: missing}"></div>
            <div th:insert="~{modules/missing :: x}"></div>
            """, "templates/modules/a.html", """
            <div th:fragment="a"><div th:insert="~{modules/a :: a}"></div>
            <div th:insert="~{modules/b :: b}"></div></div>
            """, "templates/modules/b.html", """
            <div th:fragment="b"><div th:insert="~{modules/c :: c}"></div></div>
            """);
        var result = extractor.extract(files::get);
        assertThat(result.notes()).anyMatch(note -> note.contains("重复或循环"))
            .anyMatch(note -> note.contains("深度上限"))
            .anyMatch(note -> note.contains("未找到静态片段"))
                .anyMatch(note -> note.contains("文件不存在"));
    }

    @Test
    void extractsSafeSubsetOfUnsupportedCssAndRetainsColorModeConditions() {
        var result = extractCss("""
            @import url('https://evil.test/import.css');
            @font-face { font-family: remote; src: url('/font.woff2'); }
            @keyframes spin { from { opacity: 0; } to { opacity: 1; } }
            :root { --ink: #123; --image: url('/track'); --indirect: var(--image); }
            body { color: var(--ink); font-family: system-ui; background: var(--indirect); }
            h1 { color: red; background-image: image-set('https://evil.test/a' 1x); }
            p { color: var(--missing); font-size: 1rem; }
            a { color: expression(alert(1)); font-weight: 600; }
            .dark body { color: white; }
            @media (prefers-color-scheme: dark) { body { color: #eee; background-color: #111; } }
            """);
        assertThat(result.previewCss()).contains("--ink", "var(--ink)", "system-ui", "font-size",
                "font-weight", "@media", "prefers-color-scheme:dark", "#eee", "#111")
            .doesNotContain("url(", "@import", "@font-face", "@keyframes", "image-set", "expression",
                "--image", "--indirect", "--missing", ".dark", "white");
        assertThatCode(() -> policy.validateCss(result.previewCss())).doesNotThrowAnyException();
    }

    @Test
    void removesEscapedValuesAndUnresolvedVariableChains() {
        var result = extractCss("""
            :root { --bad: u\\72l('/asset'); --a: var(--bad); --b: var(--a); --good: #123; }
            body { color: var(--b); background-color: var(--good); font-family: sans-serif; }
            p { color: red; width: 100vw; position: fixed; }
            """);
        assertThat(result.previewCss()).contains("--good", "background-color", "sans-serif", "color:red")
            .doesNotContain("--bad", "--a", "--b", "\\", "width", "position");
    }

    @Test
    void limitsFileReadsAndTotalContextAndKeepsCompleteCssRules() {
        var files = new LinkedHashMap<String, String>();
        var layout = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            layout.append("<link href='/assets/").append(i).append(".css'>");
            files.put("templates/assets/" + i + ".css",
                "body {color: red;}".repeat(3_000));
        }
        layout.append("<div class='container'>").append("<p class='x'></p>".repeat(2_000)).append("</div>");
        files.put("templates/layout.html", layout.toString());
        var reads = new ArrayList<String>();
        var result = extractor.extract(path -> {
            reads.add(path);
            return files.get(path);
        });
        assertThat(reads).hasSize(9);
        int size = result.previewCss().length() + result.notes().stream().mapToInt(String::length).sum()
            + result.sources().stream().mapToInt(source -> source.path().length() + source.kind().length()
                + source.content().length()).sum();
        assertThat(size).isLessThanOrEqualTo(32_768);
        assertThat(result.notes()).anyMatch(note -> note.contains("截断"));
        for (var source : result.sources()) {
            if (source.kind().equals("css")) {
                assertThatCode(() -> policy.validateCss(source.content())).doesNotThrowAnyException();
            }
        }
        assertThatCode(() -> policy.validateCss(result.previewCss())).doesNotThrowAnyException();
    }

    @Test
    void findsBaseRulesAfterLargeUtilityStylesheetAndSkipsOversizedDeclarations() {
        var css = new StringBuilder(".utility { display: block; }".repeat(5_000));
        css.append(":root { --huge: '").append("x".repeat(8_000)).append("'; --ink: #234; }")
            .append("body { color: var(--ink); } @media (max-width: 600px) { p {font-size: 14px;} }");
        var result = extractCss(css.toString());
        assertThat(result.previewCss()).contains("--ink", "var(--ink)", "@media", "14px")
            .doesNotContain("--huge", ".utility");
        assertThat(result.sources().getLast().content()).contains("--ink").doesNotContain(".utility");
    }

    @Test
    void returnsNotesForMissingLayoutOrMalformedCssAndHandlesInlineStyle() {
        assertThat(extractor.extract(path -> null).sources()).isEmpty();
        assertThat(extractCss("body { color: red; /*").notes())
            .anyMatch(note -> note.contains("解析失败"));
        var result = extractor.extract(path -> "<style>body {color: #123;}</style><p>private text</p>");
        assertThat(result.previewCss()).contains("#123");
        assertThat(result.sources().getFirst().content()).doesNotContain("private text", "<style>");
    }

    @Test
    void preservesEncounterOrderAndSkipsConditionalHtmlStylesheets() {
        var reads = new ArrayList<String>();
        var files = Map.of("templates/layout.html", """
            <link href="/assets/print.css" media="print">
            <style media="(prefers-color-scheme: dark)">body {color: black;}</style>
            <div th:insert="~{modules/colors :: colors}"></div>
            <style>body {color: blue;}</style><link href="/assets/last.css">
            """, "templates/modules/colors.html", """
            <div th:fragment="colors"><style>body {color: red;}</style></div>
            """, "templates/assets/last.css", "body {color: green;}");
        var result = extractor.extract(path -> {
            reads.add(path);
            return files.get(path);
        });
        assertThat(reads).doesNotContain("templates/assets/print.css");
        assertThat(result.previewCss()).containsSubsequence("color:red", "color:blue", "color:green")
            .doesNotContain("black");
        assertThat(result.notes()).anyMatch(note -> note.contains("media 条件"));
    }

    @Test
    void boundsRepeatedFragmentParsingWithinOneFile() {
        var layout = new StringBuilder();
        var fragments = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            layout.append("<div th:insert='~{modules/all :: f").append(i).append("}'></div>");
            fragments.append("<div th:fragment='f").append(i).append("'></div>");
        }
        var result = extractor.extract(Map.of("templates/layout.html", layout.toString(),
            "templates/modules/all.html", fragments.toString())::get);
        assertThat(result.sources()).hasSizeLessThanOrEqualTo(16);
        assertThat(result.notes()).anyMatch(note -> note.contains("模板片段数量上限"));
    }

    @Test
    void mapsOnlyCurrentThemeAssetsAndIgnoresNonStylesheetLinks() {
        var reads = new ArrayList<String>();
        var files = Map.of("templates/layout.html", """
            <link rel="stylesheet" href="/themes/theme-earth/assets/vendor~main-BfovF9ct.css">
            <link rel="stylesheet" th:href="@{/themes/theme-earth/assets/vendor~main-BfovF9ct.css?v=1}">
            <link rel="stylesheet" href="/themes/theme-other/assets/other.css">
            <link rel="stylesheet" href="/themes/theme-earth-more/assets/other.css">
            <link rel="stylesheet" href="/themes/theme-earth/assets/../secret.css">
            <link rel="icon" href="/favicon.ico"><link rel="modulepreload" href="/assets/a.js">
            """, "templates/assets/vendor~main-BfovF9ct.css", "body { color: #123; }");
        var result = extractor.extract(path -> {
            reads.add(path);
            return files.get(path);
        }, "theme-earth");
        assertThat(reads).containsExactly("templates/layout.html", "templates/assets/vendor~main-BfovF9ct.css");
        assertThat(result.previewCss()).contains("#123");
        assertThat(result.notes()).noneMatch(note -> note.contains("favicon") || note.contains("a.js"));
        assertThat(extractor.extract(files::get).previewCss()).isEmpty();
    }

    @Test
    void removesLargeNavigationStructureButKeepsSlotsAndEmbeddedStyles() {
        var layout = "<header><style>body {color: #123;}</style><nav>"
            + "<div class='menu-item'><a>Navigation label</a></div>".repeat(500)
            + "<section th:insert='${headerContent}'></section></nav></header>"
            + "<main class='content-wrapper'><div th:replace='${content}'></div></main>"
            + "<footer><p>Footer label</p></footer>";
        var result = extractor.extract(path -> layout);
        var source = result.sources().getFirst().content();
        assertThat(source).contains("${headerContent}", "content-wrapper", "${content}")
            .doesNotContain("menu-item", "<nav", "<header", "<footer", "Navigation label", "Footer label");
        assertThat(result.previewCss()).contains("#123");
        assertThat(result.notes()).noneMatch(note -> note.contains("上下文已截断"));
    }

    @Test
    void recoversBaseRulesAroundEmptyTailwindVariablesWithoutRelaxingPreviewPolicy() {
        var result = extractCss("""
            *, :before, :after { --tw-pan-x: ; --tw-pan-y: ; --tw-scale-x: 1; }
            :root { --empty: ; --ink: #123; --remote: url('/track'); }
            body { color: var(--ink); font-family: system-ui; background-image: var(--remote); }
            @font-face { font-family: remote; src: url('/font.woff2'); }
            @media (prefers-color-scheme: dark) { body { color: #eee; } }
            """);
        assertThat(result.notes()).anyMatch(note -> note.contains("容错解析"));
        assertThat(result.previewCss()).contains("--ink", "var(--ink)", "system-ui", "@media", "#eee")
            .doesNotContain("--empty", "--remote", "url", "@font-face", "background-image");
        assertThatCode(() -> policy.validateCss(result.previewCss())).doesNotThrowAnyException();
    }

    @Test
    void preservesExplicitRootConditionsSupportsAndVariableUsage() {
        var result = extractCss("""
            :root { --ink: 25% 0.03 250; }
            html.light { --ink: 20% 0.02 250; }
            html.dark { --ink: 90% 0.01 250; }
            @supports (color: oklch(0% 0 0)) {
              :root[data-theme="dark"] body { color: oklch(var(--ink)); }
              @media (min-width: 600px) { html.dark h1 { color: oklch(var(--ink)); } }
              html.light p { color: var(--missing); font-size: 1rem; }
            }
            """);
        assertThat(result.colorMode().strategy()).isEqualTo("explicit");
        assertThat(result.colorMode().rootStates()).extracting(ThemeStyleContext.RootState::mode)
            .contains("light", "dark");
        assertThat(result.colorMode().rootStates()).anyMatch(state -> state.classes().contains("dark"))
            .anyMatch(state -> "dark".equals(state.attributes().get("data-theme")));
        assertThat(result.colorMode().evidence()).anyMatch(value -> value.contains("html.dark"));
        assertThat(result.previewCss()).contains("html.dark", "html.light", "@supports", "@media",
            "data-theme", "oklch(var(--ink))", "font-size:1rem").doesNotContain("--missing");
        assertThat(result.sources().getLast().content()).contains("@supports", "html.dark", "data-theme");
        assertThat(result.notes()).anyMatch(note -> note.contains("oklch(var(--ink))") && note.contains("分量"))
            .anyMatch(note -> note.contains("不能确定访客当前模式"));
        assertThatCode(() -> policy.validateCss(result.previewCss())).doesNotThrowAnyException();
    }

    @Test
    void doesNotInferRootControlFromArbitraryClassesOrThemeAttributes() {
        var result = extractCss("""
            .dark body { color: white; }
            [data-theme="dark"] { --ink: white; }
            html[data-theme="ocean"] { --ink: blue; }
            @media (prefers-color-scheme: dark) { body {color:white} }
            """);
        assertThat(result.colorMode().strategy()).isEqualTo("unknown");
        assertThat(result.colorMode().rootStates()).isEmpty();
        assertThat(result.notes()).anyMatch(note -> note.contains("unknown"));
    }

    @Test
    void adaptsOnlyVerifiedEarthUtilityRootEvidenceWithoutApplyingUtilitiesToBody() {
        var files = Map.of("templates/layout.html", "<link href='/assets/main.css'>",
            "templates/assets/main.css", ".dark\\:bg-slate-900:is(.dark *) {background-color:#123}");
        var earth = extractor.extract(files::get, "theme-earth");
        assertThat(earth.colorMode().strategy()).isEqualTo("explicit");
        assertThat(earth.colorMode().rootStates()).extracting(ThemeStyleContext.RootState::selector)
            .containsExactly("html.light", "html.dark");
        assertThat(earth.previewCss()).isEmpty();
        assertThat(earth.notes()).anyMatch(note -> note.contains("不保证完整主题外观"));
        assertThat(extractor.extract(files::get, "theme-other").colorMode().strategy()).isEqualTo("unknown");
        assertThat(extractor.extract(path -> null, "theme-earth").colorMode().strategy()).isEqualTo("unknown");
    }

    @Test
    void compatibilityConstructorDefaultsToUnknown() {
        var context = new ThemeStyleContext("theme", "Theme", "SUPPORTED", java.util.List.of(),
            java.util.List.of(), "");
        assertThat(context.colorMode().strategy()).isEqualTo("unknown");
        assertThat(ThemeStyleContext.unavailable().colorMode().rootStates()).isEmpty();
    }

    @Test
    void resolvesThymeleafVersionQueryAndFallsBackToStaticHref() {
        var reads = new ArrayList<String>();
        var files = Map.of(
            "templates/layout.html", """
                <link rel="stylesheet" th:href="@{/assets/dist/style.css?v={version}(version=${theme.spec.version})}">
                <link rel="stylesheet" th:href="@{${theme.dynamic}}" href="/assets/fallback.css">
                """,
            "templates/assets/dist/style.css", ":root { --ink: #123; } body { color: var(--ink); }",
            "templates/assets/fallback.css", "body { font-family: system-ui; }");
        var result = extractor.extract(path -> {
            reads.add(path);
            return files.get(path);
        });
        assertThat(reads).containsExactly("templates/layout.html",
            "templates/assets/dist/style.css", "templates/assets/fallback.css");
        assertThat(result.previewCss()).contains("--ink", "system-ui");
    }

    @Test
    void extractsPageTemplateContentWrapperAndTypographyCss() {
        var files = Map.of(
            "templates/layout.html",
            "<body><main class='layout-shell'><div th:replace='${content}'></div></main></body>",
            "templates/page.html", """
                <html><body>
                <main class="page-shell"><div class="container mx-auto px-4"><article
                  class="prose lg:prose-lg"
                  th:classappend="${singlePage.spec.featured ? 'featured' : ''}"
                  th:utext="${singlePage.content.content}"></article></div></main>
                <link rel="stylesheet" th:href="@{/assets/prose.css?v={version}(version=${theme.spec.version})}">
                </body></html>
                """,
            "templates/assets/prose.css", """
                .prose { max-width: 65ch; } .prose h2 { font-size: 1.5rem; }
                .container { padding-inline: 1rem; }
                """);
        var result = extractor.extract(files::get, null, "templates/page.html");
        var slot = result.contentSlot();
        assertThat(slot.template()).isEqualTo("templates/page.html");
        assertThat(slot.slot()).extracting(ThemeStyleContext.ContentSlot.Wrapper::selector)
            .containsExactly("main.layout-shell");
        assertThat(slot.wrapper()).extracting(ThemeStyleContext.ContentSlot.Wrapper::selector)
            .containsExactly("main.page-shell", "div.container.mx-auto.px-4");
        assertThat(slot.contentClasses()).contains("prose", "lg:prose-lg", "featured");
        var css = result.sources().stream().filter(source -> source.kind().equals("css"))
            .map(ThemeContextExtractor.Source::content).collect(Collectors.joining("\n"));
        assertThat(css).contains(".prose", "font-size:1.5rem", "padding-inline");
    }

    @Test
    void omitsWrapperUtilitiesAlreadyProvidedByTheLayoutSlot() {
        var files = Map.of(
            "templates/layout.html", """
                <body><main class="mx-auto mt-6 w-full max-w-7xl px-4 lg:px-6">
                <div th:replace="${content}"></div></main></body>
                """,
            "templates/page.html", """
                <section class="mx-auto mt-6 grid max-w-7xl grid-cols-1 gap-6 px-4
                  md:grid-cols-[1fr_18rem] lg:px-6">
                  <div class="z-0 min-w-0"><div class="rounded-xl bg-white p-4">
                    <article class="prose" th:utext="${singlePage.content.content}"></article>
                  </div></div>
                </section>
                """);
        var result = extractor.extract(files::get, null, "templates/page.html");
        assertThat(result.contentSlot().slot())
            .extracting(ThemeStyleContext.ContentSlot.Wrapper::selector)
            .containsExactly("main.mx-auto.mt-6.w-full.max-w-7xl.px-4.lg:px-6");
        assertThat(result.contentSlot().wrapper())
            .extracting(ThemeStyleContext.ContentSlot.Wrapper::selector)
            .containsExactly("section.grid.grid-cols-1.gap-6.md:grid-cols-[1fr_18rem]",
                "div.z-0.min-w-0", "div.rounded-xl.bg-white.p-4");
        assertThat(result.contentSlot().contentClasses()).containsExactly("prose");
    }

    @Test
    void keepsInnerWrapperUtilitiesThatCenterOrNarrowTheContent() {
        var files = Map.of(
            "templates/layout.html", """
                <body><div class="flex min-h-screen flex-col"><main
                  class="mx-auto min-h-0 w-full max-w-7xl flex-1 px-4 sm:px-6 lg:px-8">
                <div th:replace="${content}"></div></main></div></body>
                """,
            "templates/page.html", """
                <body><div class="flex min-h-screen flex-col">
                  <main class="bg-base-100 min-h-0 flex-1">
                    <article class="mx-auto max-w-3xl px-4 py-10 sm:px-6 lg:px-8">
                      <div class="prose prose-neutral max-w-none"
                        th:utext="${singlePage.content.content}"></div>
                    </article>
                  </main>
                </div></body>
                """);
        var result = extractor.extract(files::get, null, "templates/page.html");
        assertThat(result.contentSlot().slot())
            .extracting(ThemeStyleContext.ContentSlot.Wrapper::selector)
            .containsExactly("div.flex.min-h-screen.flex-col",
                "main.mx-auto.min-h-0.w-full.max-w-7xl.flex-1.px-4.sm:px-6.lg:px-8");
        assertThat(result.contentSlot().wrapper())
            .extracting(ThemeStyleContext.ContentSlot.Wrapper::selector)
            .containsExactly("main.bg-base-100",
                "article.mx-auto.max-w-3xl.px-4.py-10.sm:px-6.lg:px-8");
        assertThat(result.contentSlot().contentClasses())
            .containsExactly("prose", "prose-neutral", "max-w-none");
    }

    @Test
    void keepsUtilityRulesForPageContentClassesWithVariants() {
        var files = Map.of(
            "templates/layout.html", "<div th:replace='${content}'></div>",
            "templates/page.html", """
                <link rel="stylesheet" href="/assets/page.css">
                <div class="md:grid-cols-[1fr_18rem] dark:bg-slate-900"
                  th:utext="${singlePage.content.content}"></div>
                """,
            "templates/assets/page.css", """
                .md\\:grid-cols-\\[1fr_18rem\\] { display: grid; }
                .dark\\:bg-slate-900 { background-color: #111; }
                """);
        var result = extractor.extract(files::get, null, "templates/page.html");
        assertThat(result.contentSlot().contentClasses())
            .containsExactly("md:grid-cols-[1fr_18rem]", "dark:bg-slate-900");
        var css = result.sources().stream().filter(source -> source.kind().equals("css"))
            .map(ThemeContextExtractor.Source::content).collect(Collectors.joining("\n"));
        assertThat(css).contains("display:grid", "background-color:#111");
    }

    @Test
    void flattensCssLayersAndDetectsUiStack() {
        var result = extractCss("""
            @layer base { :root { --ink: #123; } body { color: var(--ink); } }
            @layer components { .btn { padding: 0.5rem 1rem; } .card { border-radius: 0.75rem; } }
            :root { --tw-ring-color: #abc; --b1: 100% 0 0; }
            """);
        assertThat(result.previewCss()).contains("--ink", "color:var(--ink)")
            .doesNotContain("@layer");
        assertThat(result.uiStack().frameworks()).contains("Tailwind CSS", "daisyUI");
        assertThat(result.uiStack().componentClasses()).contains("btn", "card");
    }

    @Test
    void briefSummarizesContentSlotAndUiStack() {
        var context = new ThemeStyleContext("theme-earth", "Earth", "SUPPORTED",
            java.util.List.of(new ThemeContextExtractor.Source("layout", "layout", "x")),
            java.util.List.of(), "", ThemeStyleContext.ColorMode.unknown(),
            new ThemeStyleContext.ContentSlot("templates/page.html",
                java.util.List.of(new ThemeStyleContext.ContentSlot.Wrapper("main",
                    java.util.List.of("layout-shell"), null)),
                java.util.List.of(new ThemeStyleContext.ContentSlot.Wrapper("main",
                    java.util.List.of("shell"), null)),
                java.util.List.of("prose")),
            new ThemeStyleContext.UiStack(java.util.List.of("Tailwind CSS"),
                java.util.List.of("btn")));
        assertThat(context.brief()).contains("theme-earth", "main.shell", ".prose",
            "layout content slot", "main.layout-shell", "Main landmark",
            "not a landing-page container", "Tailwind CSS", "btn");
        assertThat(ThemeStyleContext.unavailable().brief()).isEmpty();
        assertThat(ThemeStyleContext.standalone().brief()).contains("STANDALONE");
    }

    @Test
    void reservesStylesheetReadsAndContextForThemeOverridesAfterVendorFiles() {
        var files = new LinkedHashMap<String, String>();
        var layout = new StringBuilder("<html><head>");
        for (int i = 0; i < 7; i++) {
            layout.append("<link href='/assets/").append(i).append(".css'>");
            files.put("templates/assets/" + i + ".css", i == 6
                ? "body{color:var(--brand)}:root{--brand:#abcdef}"
                : ":root{--vendor:" + i + "}body{font-size:16px}".repeat(1_000));
        }
        layout.append("</head><body><main><div th:replace='${content}'></div></main></body></html>");
        files.put("templates/layout.html", layout.toString());
        files.put("templates/page.html", "<article th:utext='${singlePage.content.content}'></article>");
        var result = extractor.extract(files::get, "sample", "templates/page.html");
        assertThat(result.sources()).anySatisfy(source -> {
            assertThat(source.path()).isEqualTo("templates/assets/6.css");
            assertThat(source.content()).contains("--brand:#abcdef", "color:var(--brand)");
        });
        assertThat(result.coverage().gaps()).noneMatch(gap -> gap.path().equals("templates/assets/6.css"));
        assertThat(result.sources().stream().mapToInt(source -> source.path().length()
            + source.kind().length() + source.content().length()).sum()).isLessThanOrEqualTo(22_000);
    }

    @Test
    void selectsFoundationAndLastOverridesInCascadeOrderBeforeArticleStyles() {
        var files = new LinkedHashMap<String, String>();
        var layout = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            layout.append("<link href='/assets/").append(i).append(".css'>");
            files.put("templates/assets/" + i + ".css", "body{font-size:" + (10 + i) + "px}");
        }
        layout.append("<main th:insert='${content}'></main>");
        files.put("templates/layout.html", layout.toString());
        files.put("templates/page.html", "<link href='/assets/article.css'>"
            + "<article th:utext='${singlePage.content.content}'></article>");
        files.put("templates/assets/article.css", "body{color:red}");
        var reads = new ArrayList<String>();
        var result = extractor.extract(path -> { reads.add(path); return files.get(path); },
            "sample", "templates/page.html");
        assertThat(result.sources().stream().filter(source -> source.kind().equals("css"))
            .map(ThemeContextExtractor.Source::path)).containsExactly("templates/assets/0.css",
                "templates/assets/3.css", "templates/assets/4.css", "templates/assets/5.css",
                "templates/assets/6.css", "templates/assets/7.css", "templates/assets/8.css",
                "templates/assets/9.css");
        assertThat(reads).hasSize(10).doesNotContain("templates/assets/article.css");
        assertThat(result.previewCss()).containsSubsequence("font-size:10px", "font-size:19px");
        assertThat(result.coverage().gaps()).anyMatch(gap -> gap.path().equals("templates/assets/article.css")
            && gap.reason().equals("file_limit"));
    }

    @Test
    void preservesInlineStylesFromDifferentFragmentsOfTheSameFile() {
        var files = Map.of(
            "templates/layout.html", """
                <html><body>
                <div th:replace="~{page :: first}"></div>
                <div th:replace="~{page :: second}"></div>
                <main th:insert="${content}"></main>
                </body></html>
                """,
            "templates/page.html", """
                <section th:fragment="first"><style>body{color:red}</style></section>
                <style media="print">body{color:black}</style>
                <section th:fragment="second"><style>body{color:blue}</style></section>
                <article th:utext="${singlePage.content.content}"></article>
                """);
        for (var pageTemplate : new String[] {null, "templates/page.html"}) {
            var result = extractor.extract(files::get, "sample", pageTemplate);
            assertThat(result.sources().stream().filter(source -> source.kind().equals("css"))
                .map(ThemeContextExtractor.Source::content))
                .containsExactly("body{color:red}", "body{color:blue}");
            assertThat(result.previewCss()).isEqualTo("body{color:red}body{color:blue}");
        }
    }

    @Test
    void reportsActualMainLandmarkWithoutAssumingHeaderAlignment() {
        for (var main : java.util.List.of("<main>", "<div role='main'>")) {
            var closing = main.startsWith("<main") ? "</main>" : "</div>";
            var result = extractor.extract(path -> "<body>" + main
                + "<aside></aside><div class='content-column'><div th:replace='${content}'></div>"
                + "</div>" + closing + "</body>");
            assertThat(result.contentSlot().mainLandmark()).isEqualTo("present");
            assertThat(result.contentSlot().slot()).hasSize(2);
        }
        assertThat(extractor.extract(path -> "<div class='flex'><div th:replace='${content}'></div></div>")
            .contentSlot().mainLandmark()).isEqualTo("absent");
        assertThat(extractor.extract(path -> "<main th:if='${enabled}'><div th:replace='${content}'></div></main>")
            .contentSlot().mainLandmark()).isEqualTo("unknown");
        assertThat(extractor.extract(path -> "<main th:replace='${content}'></main>")
            .contentSlot().mainLandmark()).isEqualTo("absent");
        assertThat(extractor.extract(path -> "<main></main>").contentSlot().mainLandmark()).isEqualTo("unknown");
    }

    @Test
    void doesNotInferMountAncestryFromForwardedOrConditionalContent() {
        for (var expression : java.util.List.of("~{modules/shell :: shell(${content})}",
            "${enabled} ? ${content} : ~{other :: body}",
            "~{modules/shell :: shell(${ content })}")) {
            for (var attribute : java.util.List.of("th:replace", "th:insert")) {
                var result = extractor.extract(Map.of("templates/layout.html",
                    "<html th:fragment='html(head, content)'><body><main class='outer'><div "
                        + attribute + "=\"" + expression + "\"></div></main></body></html>")::get);
                assertThat(result.contentSlot().mainLandmark()).isEqualTo("unknown");
                assertThat(result.contentSlot().slot()).isEmpty();
                assertThat(result.coverage().gaps()).anyMatch(gap ->
                    gap.reason().equals("unresolved_slot"));
            }
        }
    }

    @Test
    void acceptsWhitespaceInDirectContentExpressions() {
        for (var expression : java.util.List.of("${content}", " ${ content } ", "~{${content}}",
            " ~{ ${ content } } ")) {
            var result = extractor.extract(path ->
                "<main th:insert='" + expression + "'></main>");
            assertThat(result.contentSlot().mainLandmark()).isEqualTo("present");
            assertThat(result.coverage().gaps())
                .noneMatch(gap -> gap.reason().equals("unresolved_fragment"));
        }
    }

    @Test
    void acceptsDirectHeadAndFootParametersWithoutMissingFragmentGaps() {
        for (var parameter : java.util.List.of("head", "foot")) {
            for (var expression : java.util.List.of("${ " + parameter + " }",
                "~{ ${ " + parameter + " } }")) {
                var result = extractor.extract(Map.of("templates/layout.html",
                    "<html><head><th:block th:replace='" + expression + "'/></head>"
                        + "<body><main th:insert='${content}'></main></body></html>")::get);
                assertThat(result.coverage().gaps())
                    .noneMatch(gap -> gap.reason().equals("unresolved_fragment"));
            }
        }
    }

    @Test
    void doesNotInferSlotConstraintsThroughRemovalInstructions() {
        for (var removal : java.util.List.of("tag", "all", "body", "all-but-first", "${removal}")) {
            for (var slot : java.util.List.of("<th:block th:replace='${content}'/>", "")) {
                var result = extractor.extract(Map.of("templates/layout.html",
                    "<html><body><main class='shell' style='max-width:600px;padding:24px'"
                        + " th:remove='" + removal + "'"
                        + (slot.isEmpty() ? " th:insert='${content}'" : "")
                        + ">" + slot + "</main></body></html>")::get);
                assertThat(result.contentSlot().mainLandmark()).isEqualTo("unknown");
                assertThat(result.contentSlot().slot()).isEmpty();
                assertThat(result.coverage().gaps())
                    .anyMatch(gap -> gap.reason().equals("unresolved_slot"));
            }
        }
    }

    @Test
    void excludesVirtualBlocksButKeepsTheirConditionsAndRealAncestors() {
        for (var host : java.util.List.of("<th:block th:replace='${content}'/>", "")) {
            var block = "<th:block role='main' class='virtual' style='max-width:600px;padding:24px'"
                + (host.isEmpty() ? " th:insert='${content}'" : "") + ">" + host + "</th:block>";
            var bare = extractor.extract(Map.of("templates/layout.html", block)::get);
            assertThat(bare.contentSlot().mainLandmark()).isEqualTo("absent");
            assertThat(bare.contentSlot().slot()).isEmpty();
            var wrapped = extractor.extract(Map.of("templates/layout.html",
                "<main class='real' th:remove='none'>" + block + "</main>")::get);
            assertThat(wrapped.contentSlot().mainLandmark()).isEqualTo("present");
            assertThat(wrapped.contentSlot().slot())
                .extracting(ThemeStyleContext.ContentSlot.Wrapper::selector)
                .containsExactly("main.real");
            var conditional = extractor.extract(Map.of("templates/layout.html",
                "<th:block th:if='${enabled}'><main>" + block + "</main></th:block>")::get);
            assertThat(conditional.contentSlot().mainLandmark()).isEqualTo("unknown");
        }
    }

    @Test
    void doesNotClaimAncestorsWhenHtmlImplicitlyClosesThem() {
        for (var layout : java.util.List.of(
            "<p class='intro' style='max-width:10px'>Intro<div class='shell'>"
                + "<th:block th:replace='${content}'></th:block></div>",
            "<ul><li class='intro' style='max-width:10px'>Intro<li class='shell'>"
                + "<th:block th:replace='${content}'></th:block></li></ul>")) {
            var result = extractor.extract(path ->
                "<html th:fragment='html(head, content)'><body>" + layout + "</body></html>");
            assertThat(result.contentSlot().mainLandmark()).isEqualTo("unknown");
            assertThat(result.contentSlot().slot()).isEmpty();
            assertThat(result.coverage().gaps()).anyMatch(gap ->
                gap.reason().equals("ambiguous_slot"));
        }
    }

    @Test
    void keepsHtmlVoidElementsOutsideTheContentSlotAncestry() {
        for (var tag : java.util.List.of("img", "input", "br", "hr", "meta", "link")) {
            var result = extractor.extract(path -> """
                <html><head><meta charset="UTF-8">
                <th:block th:if="${head != null}"><th:block th:replace="${head}" /></th:block>
                </head><body><input class="drawer-toggle" th:if="${toggle}">
                <main class="shell"><%s class="decoration" th:if="${show}">
                <div class="content-column"><th:block th:replace="${content}" /></div>
                </main></body></html>
                """.formatted(tag));
            assertThat(result.contentSlot().mainLandmark()).as(tag).isEqualTo("present");
            assertThat(result.contentSlot().slot())
                .extracting(ThemeStyleContext.ContentSlot.Wrapper::selector)
                .as(tag).containsExactly("main.shell", "div.content-column");
        }
    }

    @Test
    void keepsSelfClosingTemplateSiblingsOutsideTheContentSlotAncestry() {
        for (var tag : java.util.List.of("th:block", "div", "halo:header")) {
            var result = extractor.extract(path -> """
                <html><head><th:block th:if="${head != null}">
                <th:block th:replace="${head}" /></th:block></head><body>
                <%s th:replace="~{modules/header}" />
                <main class="shell"><th:block th:replace="${content}" /></main>
                <th:block th:replace="~{modules/footer}" /></body></html>
                """.formatted(tag));
            assertThat(result.contentSlot().mainLandmark()).as(tag).isEqualTo("present");
            assertThat(result.contentSlot().slot())
                .extracting(ThemeStyleContext.ContentSlot.Wrapper::selector)
                .containsExactly("main.shell");
            assertThat(result.coverage().gaps()).noneMatch(gap -> gap.reason().contains("slot"));
        }
    }

    @Test
    void keepsConditionalHeadBlocksOutsideTheContentSlotAncestry() {
        var result = extractor.extract(path -> """
            <html><head><meta charset="UTF-8">
            <th:block th:if="${head != null}"><th:block th:replace="${head}" /></th:block>
            <link rel="stylesheet" href="https://example.com/style.css"></head>
            <body><header><img th:if="${logo}" src="logo.png"></header>
            <main><th:block th:replace="${content}" /></main></body></html>
            """);
        assertThat(result.contentSlot().mainLandmark()).isEqualTo("present");
        assertThat(result.contentSlot().slot()).extracting(ThemeStyleContext.ContentSlot.Wrapper::tag)
            .containsExactly("main");
    }

    @Test
    void reportsBoundedDynamicGapsWithoutTreatingSlotsAsMissingDependencies() {
        var result = extractor.extract(path -> """
            <html><head><th:block th:replace='${head}'/></head>
            <body th:style='${theme.config.colors}'><main><div th:replace='${content}'/></main>
            <footer th:replace='~{blocks/footer :: ${theme.config.footer.style}}'/></body></html>
            """);
        assertThat(result.coverage().gaps()).anyMatch(gap -> gap.reason().equals("dynamic_expression")
            && gap.detail().contains("body@th:style"))
            .anyMatch(gap -> gap.reason().equals("unresolved_fragment"))
            .noneMatch(gap -> gap.detail().contains("${head}") || gap.detail().contains("${content}"));
        var many = new StringBuilder("<main th:insert='${content}'>");
        for (int i = 0; i < 50; i++) many.append("<div th:replace='${fragment").append(i).append("}'/>");
        var bounded = extractor.extract(path -> many + "</main>");
        assertThat(bounded.coverage().gaps()).hasSize(32);
        assertThat(bounded.coverage().hasMore()).isTrue();
    }

    @Test
    void keepsLayoutSlotWhenThePageTemplateHasNoContentNode() {
        var files = Map.of(
            "templates/layout.html",
            "<body><main class='layout-shell'><div th:replace='${content}'></div></main></body>",
            "templates/page.html", "<html><body><p>no content node</p></body></html>");
        var result = extractor.extract(files::get, null, "templates/page.html");
        assertThat(result.contentSlot().template()).isEqualTo("templates/page.html");
        assertThat(result.contentSlot().slot())
            .extracting(ThemeStyleContext.ContentSlot.Wrapper::selector)
            .containsExactly("main.layout-shell");
        assertThat(result.contentSlot().wrapper()).isEmpty();
    }

    @Test
    void keepsLayoutSlotWhenThePageTemplateCannotBeRead() {
        var files = Map.of("templates/layout.html",
            "<body><main class='layout-shell'><div th:replace='${content}'></div></main></body>");
        var result = extractor.extract(files::get, null, "templates/page.html");
        assertThat(result.contentSlot().template()).isEqualTo("templates/page.html");
        assertThat(result.contentSlot().slot())
            .extracting(ThemeStyleContext.ContentSlot.Wrapper::selector)
            .containsExactly("main.layout-shell");
    }

    @Test
    void excludesAClassedReplaceHostFromTheSlot() {
        var files = Map.of(
            "templates/layout.html", """
                <body><main class="layout-shell"><div class="slot-host"
                  th:replace="${content}"></div></main></body>
                """,
            "templates/page.html", """
                <html><body><article class="prose"
                  th:utext="${singlePage.content.content}"></article></body></html>
                """);
        var result = extractor.extract(files::get, null, "templates/page.html");
        assertThat(result.contentSlot().slot())
            .extracting(ThemeStyleContext.ContentSlot.Wrapper::selector)
            .containsExactly("main.layout-shell");
    }

    @Test
    void keepsLayoutSlotWithoutAPageTemplate() {
        var files = Map.of("templates/layout.html",
            "<body><main class='layout-shell'><div th:replace='${content}'></div></main></body>");
        var result = extractor.extract(files::get, null, null);
        assertThat(result.contentSlot().slot())
            .extracting(ThemeStyleContext.ContentSlot.Wrapper::selector)
            .containsExactly("main.layout-shell");
    }

    @Test
    void includesTheInsertHostElementAsTheSlotContainer() {
        var files = Map.of(
            "templates/layout.html",
            "<body><main class='mx-auto max-w-7xl px-4' th:insert='${content}'></main></body>",
            "templates/page.html", """
                <html><body><article class="prose"
                  th:utext="${singlePage.content.content}"></article></body></html>
                """);
        var result = extractor.extract(files::get, null, "templates/page.html");
        assertThat(result.contentSlot().slot())
            .extracting(ThemeStyleContext.ContentSlot.Wrapper::selector)
            .containsExactly("main.mx-auto.max-w-7xl.px-4");
    }

    @Test
    void doesNotOfferArticleTypographyAsComponentClasses() {
        var result = extractCss(
            ".prose{max-width:65ch}.markdown-body{margin:0}.rich-text{line-height:1.6}.btn{padding:1rem}");
        assertThat(result.uiStack().componentClasses()).contains("btn")
            .doesNotContain("prose", "markdown-body", "rich-text");
    }

    @Test
    void briefWarnsWhenTheLayoutIsNotSupported() {
        var context = new ThemeStyleContext("theme-x", "X", "INVALID",
            java.util.List.of(), java.util.List.of(), "");
        assertThat(context.brief()).contains("INVALID", "do not rely");
    }

    private ThemeContextExtractor.Result extractCss(String css) {
        return extractor.extract(Map.of("templates/layout.html", "<link href='/assets/main.css'>",
            "templates/assets/main.css", css)::get);
    }
}
