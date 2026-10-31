---
name: AI Page Builder
description: A theme-aware AI page authoring workbench inside Halo Console
colors:
  graphite-ink: "#111827"
  graphite-hover: "#374151"
  tool-gray: "#4b5563"
  muted-gray: "#6b7280"
  quiet-gray: "#9ca3af"
  hairline: "#e5e7eb"
  divider: "#f3f4f6"
  paper: "#ffffff"
  canvas-ground: "#f9fafb"
  console-navy: "#0e1731"
  halo-mint: "#4ccba0"
  selection-indigo: "#6366f1"
  published-emerald: "#047857"
  published-surface: "#ecfdf5"
  attention-amber: "#b45309"
  attention-surface: "#fffbeb"
  failure-red: "#b91c1c"
  failure-surface: "#fef2f2"
  info-blue: "#1d4ed8"
  info-surface: "#eff6ff"
  script-violet: "#7e22ce"
  script-surface: "#faf5ff"
typography:
  title:
    fontFamily: "ui-sans-serif, system-ui, sans-serif"
    fontSize: "20px"
    fontWeight: 700
    lineHeight: "30px"
  heading:
    fontFamily: "ui-sans-serif, system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: "20px"
  body:
    fontFamily: "ui-sans-serif, system-ui, sans-serif"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: "20px"
  message:
    fontFamily: "ui-sans-serif, system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: "24px"
  label:
    fontFamily: "ui-sans-serif, system-ui, sans-serif"
    fontSize: "13px"
    fontWeight: 500
    lineHeight: "20px"
  caption:
    fontFamily: "ui-sans-serif, system-ui, sans-serif"
    fontSize: "12px"
    fontWeight: 400
    lineHeight: "16px"
  badge:
    fontFamily: "ui-sans-serif, system-ui, sans-serif"
    fontSize: "10px"
    fontWeight: 400
    lineHeight: "16px"
  code:
    fontFamily: "monospace"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: "18.2px"
rounded:
  xs: "4px"
  sm: "6px"
  md: "8px"
  lg: "12px"
  xl: "16px"
  full: "9999px"
spacing:
  xxs: "2px"
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "24px"
  xxl: "32px"
components:
  button-primary:
    backgroundColor: "{colors.graphite-ink}"
    textColor: "{colors.paper}"
    typography: "{typography.label}"
    rounded: "{rounded.md}"
    padding: "0 12px"
    height: "32px"
  button-primary-hover:
    backgroundColor: "{colors.graphite-hover}"
  button-outline:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.graphite-hover}"
    typography: "{typography.label}"
    rounded: "{rounded.md}"
    padding: "0 12px"
    height: "32px"
  button-ghost:
    backgroundColor: "transparent"
    textColor: "{colors.tool-gray}"
    typography: "{typography.label}"
    rounded: "{rounded.md}"
    padding: "0 12px"
    height: "32px"
  button-danger:
    backgroundColor: "transparent"
    textColor: "{colors.failure-red}"
    typography: "{typography.label}"
    rounded: "{rounded.md}"
    padding: "0 12px"
    height: "32px"
  button-danger-hover:
    backgroundColor: "{colors.failure-surface}"
  icon-button:
    backgroundColor: "transparent"
    textColor: "{colors.tool-gray}"
    rounded: "{rounded.md}"
    size: "32px"
    height: "32px"
    width: "32px"
  chip-published:
    backgroundColor: "{colors.published-surface}"
    textColor: "{colors.published-emerald}"
    typography: "{typography.caption}"
    rounded: "{rounded.full}"
    padding: "2px 8px"
    height: "24px"
  chip-attention:
    backgroundColor: "{colors.attention-surface}"
    textColor: "{colors.attention-amber}"
    typography: "{typography.caption}"
    rounded: "{rounded.full}"
    padding: "2px 8px"
    height: "24px"
  input:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.graphite-ink}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "6px 10px"
    height: "34px"
  segmented:
    backgroundColor: "{colors.divider}"
    rounded: "{rounded.md}"
    padding: "2px"
    height: "32px"
  segmented-option-active:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.graphite-ink}"
    typography: "{typography.label}"
    rounded: "{rounded.sm}"
    height: "28px"
  card:
    backgroundColor: "{colors.paper}"
    rounded: "{rounded.md}"
    padding: "16px"
  chat-bubble:
    backgroundColor: "{colors.divider}"
    textColor: "{colors.graphite-ink}"
    typography: "{typography.message}"
    rounded: "{rounded.xl}"
    padding: "10px 14px"
  chat-composer:
    backgroundColor: "{colors.paper}"
    rounded: "{rounded.xl}"
  inspector:
    backgroundColor: "{colors.paper}"
    rounded: "{rounded.lg}"
    padding: "12px"
    width: "288px"
---

# Design System: AI Page Builder

## Overview

**Creative North Star: "The Theme Atelier"**

AI Page Builder is a theme-aware workbench rather than a generic page builder. The current theme is the material; the page is the workpiece; the interface is a set of precise instruments arranged around the canvas. The system is restrained, exact, and trustworthy: white paper surfaces on a quiet gray ground, hairline borders, graphite type, and chroma reserved for state. Nothing in the chrome competes with the page being edited.

The product lives inside Halo Console, so it inherits Console primitives for standard navigation, forms, dialogs, and list buttons. The plugin-owned surfaces are deliberately more authored but still Console-compatible: 8px controls, 12px floating panels, 16px conversational surfaces, 13px default UI text, and compact 32px controls. The canvas receives the largest and calmest area; hints and validation overlays appear only when needed and never resize it.

Density is compact without feeling cramped. The workbench is read and scanned for long stretches, so supporting text never falls below gray-500, metadata never falls below 12px, and semantic color is always paired with a label, not used alone. The one overt visual gesture is the indigo canvas selection language: it marks exactly where the user is working, then gets out of the way.

**Key Characteristics:**

- Graphite on paper: ink and gray neutrals carry structure; white and gray-50 carry surfaces.
- Rare mint: Halo mint appears only at focus, active, or creation-choice moments, never as decoration.
- Semantic tint: published, attention, failure, info, and script states each use one hue for text, surface, and border.
- Flat at rest, lifted on intent: borders and tonal layering first, shadows only for floating layers and hover lift.
- Precise type: 13px default UI, 14px conversation, 12px metadata floor, 10–11px badges only.

## Colors

A neutral-first palette: grays build the desk, and every chromatic color is a signal with a job.

### Primary

- **Graphite Ink** (#111827): primary action fill, primary text, workbench headings, and the darkest structural ink. It is the default answer for "make this important".
- **Graphite Hover** (#374151): primary-button hover, outline-button labels, and strong secondary text. It is the step between ink and tool gray.
- **Console Navy** (#0e1731): the primary action inside Halo Console-owned surfaces, such as list headers and modal footers. Keep it out of the workbench toolbar and other plugin-owned chrome.

### Secondary

- **Halo Mint** (#4ccba0): the single brand accent. Use it for focus rings, selected creation choices, and active indicators. It is a signal of "you are here", not a surface color.

### Tertiary

- **Selection Indigo** (#6366f1): the canvas editing language. A 2px indigo-500 border marks the selected element; indigo-400 at 1px plus an indigo-500/5 fill marks hover; indigo-700 is reserved for annotation labels and target markers.
- **Published Emerald** (#047857) on **Published Surface** (#ecfdf5): published state and successful completion.
- **Attention Amber** (#b45309) on **Attention Surface** (#fffbeb): pending, unvalidated, unsaved, and configuration warnings.
- **Failure Red** (#b91c1c) on **Failure Surface** (#fef2f2): failed runs, destructive actions, and blocking errors.
- **Info Blue** (#1d4ed8) on **Info Surface** (#eff6ff): in-progress generation, saving, and informational links.
- **Script Violet** (#7e22ce) on **Script Surface** (#faf5ff): pages that contain executable scripts.

### Neutral

- **Paper** (#ffffff): work surfaces, cards, panels, and the canvas page itself.
- **Canvas Ground** (#f9fafb): the application background and the canvas stage around the page.
- **Divider** (#f3f4f6): quiet fills, chat bubbles, segmented-control tracks, and selected list rows.
- **Hairline** (#e5e7eb): 1px borders; the structural line of the whole system.
- **Tool Gray** (#4b5563): icons, segmented idle labels, outline-button labels, and strong supporting copy.
- **Muted Gray** (#6b7280): the minimum for metadata and supporting text. It reaches 4.83:1 on paper.
- **Quiet Gray** (#9ca3af): disabled glyphs, focus borders, and non-essential decoration only. It is not a text color for metadata or help copy.

### Named Rules

**The Rare Mint Rule.** Halo mint covers at most about 10% of any screen. It marks focus, active, or a selected creation choice; if it is filling a surface, it has become decoration and should be removed.

**The Same-Hue Tint Rule.** A status surface, its text, and its border come from the same hue. Use the 700 text on the 50 surface — never gray text on a tinted status surface.

**The No Chroma in Structure Rule.** Grays carry layout, hierarchy, and borders. Chroma only encodes state, canvas selection, or the single focus accent.

## Typography

**Display Font:** none; Halo Console page headers use the system UI stack at 20px/700.
**Body Font:** the system UI stack (`ui-sans-serif, system-ui, sans-serif`)
**Label/Mono Font:** monospace for source code only.

**Character:** one system sans keeps the interface native to Halo Console and quiet against the page being edited. Hierarchy comes from size and weight, never from a second face. Source code is the only place where a different rhythm appears.

### Hierarchy

- **Page title** (700, 20px/30px): Console-owned page headers on the list and create screens.
- **Heading** (600, 14px/20px): workbench title, page-card titles, modal titles, and section headers.
- **Body** (400, 13px/20px): default UI copy, tool rows, inputs, and compact controls.
- **Message** (400, 14px/24px): conversation content, user bubbles, and assistant prose.
- **Label** (500, 13px/20px): buttons, segmented items, and compact field labels. Console forms use 14px/500 labels.
- **Caption** (400, 12px/16px): metadata, helper text, and chips. This is the metadata floor.
- **Badge** (400, 10–11px/16px): revision and run badges only.
- **Code** (400, 13px/18.2px): source editor and code blocks; monospace, white surface, #f5f5f5 gutters.

### Named Rules

**The 12px Floor Rule.** Supporting text never goes below 12px. 10–11px exists only for badges that sit beside a title or status.

**The One Face Rule.** Do not introduce a display face. Use weight, size, and spacing to create hierarchy; the Console-native sans is the brand.

**The 13px Default Rule.** The workbench's default UI text is 13px. Reserve 14px for conversation and page titles, not for dense chrome.

## Layout

The workbench is a fixed viewport, not a scrolling document. A 48px header holds page identity, publish state, and global actions. At 768px and wider, a 380px conversation rail sits beside a flexible canvas; below 768px, the interface becomes a single panel with a 对话/画布 switch. The canvas toolbar wraps as needed and is 44px tall when it fits on one line.

The page list uses Halo Console's main content column: a page header, a toolbar, then a card grid of `repeat(auto-fill, minmax(280px, 1fr))` with 20px gaps. The create flow is centered at `max-width: 672px`. The canvas receives the largest calm area; hints, validation notices, and zoom controls float over it without changing its size.

Spacing follows a 4px base. Common gaps are 8, 12, 16, and 24px; common component heights are 24, 28, 32, 36, 44, and 48px. Breakpoints follow the Console: sm 640, md 768, lg 1024, xl 1280. The md breakpoint is the structural one: the split workbench becomes single-panel below it.

## Elevation & Depth

The system is flat at rest. Depth comes from tonal surfaces (canvas ground against paper), 1px hairline borders, and a deliberately small shadow vocabulary that appears only when something floats or is being hovered. Resting cards and panels do not carry ambient shadows.

### Shadow Vocabulary

- **Composer lift** (`0 1px 2px 0 rgb(0 0 0 / 0.05)`): the chat composer and the active segmented thumb.
- **Card hover** (`0 4px 6px -1px rgb(0 0 0 / 0.1), 0 2px 4px -2px rgb(0 0 0 / 0.1)`): page cards on hover only.
- **Floating panel** (`0 10px 15px -3px rgb(0 0 0 / 0.1), 0 4px 6px -4px rgb(0 0 0 / 0.1)`): the element inspector, dropdowns, and popovers.
- **Console modal** (`0 20px 25px -5px rgb(0 0 0 / 0.1), 0 8px 10px -6px rgb(0 0 0 / 0.1)`): Halo-owned modals only.

### Named Rules

**The Intent Rule.** A surface is flat until it floats or is hovered. If a resting panel needs a shadow to be understood, fix the border or the tonal separation instead.

## Shapes

Two form languages coexist deliberately. Plugin-owned surfaces use 8px controls, 12px floating panels, 16px conversational surfaces, and full pills for chips. Halo Console-owned primitives keep their 4px radius — VButton, FormKit fields, and modals. Borders are 1px hairlines; there are no double borders, bevels, or decorative outlines.

Badges are small 4px-radius rectangles, not pills. The canvas selection is the only 2px border in the system: indigo-500 for selection, indigo-400 at 1px plus a 5% indigo fill for hover. The selected element gets the heavier weight; everything else stays hairline-thin.

### Named Rules

**The Two-Radius-System Rule.** Do not mix radii within one surface. Plugin panels use 8/12/16; Console chrome keeps 4. A 4px control inside a 12px plugin panel reads as a different product.

## Components

### Buttons

- **Shape:** 8px radius, 32px height, 13px/500 label.
- **Primary:** graphite ink fill with paper text; hover to graphite hover; disabled at 50% opacity with no pointer events.
- **Outline:** paper fill, 1px hairline border, graphite-hover text; hover to gray-50 fill.
- **Ghost:** transparent, tool-gray text; hover to divider fill and ink text.
- **Danger:** transparent, failure-red text; hover to failure surface.
- **Console boundary:** VButton in lists and dialogs uses console navy, 4px radius, 36px height, 14px text, 16px horizontal padding. Do not use it inside the workbench toolbar.
- **Focus:** keep a visible 2px focus outline; the Console accent is mint, the default browser ring is acceptable only where the Console already owns it.

### Icon Buttons

- **Shape:** 32×32, 8px radius, tool-gray icon at 14–16px.
- **Hover:** divider fill and ink icon. Disabled at 40%.
- **Touch:** critical mobile controls (view switch, device switch, panel switch) grow to 44px; desktop icon buttons remain 32px.

### Chips

- **Style:** full pill, 12px/20 caption, 2×8 padding, 24px height, tinted semantic surface, no border by default.
- **Status:** prefix the axis so the meaning is explicit, for example `草稿：已保存`, `发布：已发布`, and `主题：Ethereal · 复用布局`. The theme/layout chip is a button that opens page settings.
- **Rule:** chips state, they do not decorate. A chip without a status or action should become text.

### Inputs / Fields

- **Plugin fields:** 8px radius, 1px hairline, 13px/20 text, 6×10 padding, white fill; focus shifts the border to quiet gray.
- **Console FormKit fields:** 4px radius, 1px gray-300 border, 36px height, 14px text; labels are 14px/500 and help is 12px/16 in muted gray.
- **Help text:** never quiet gray; use muted gray or the matching status hue. Help is part of the interface, not a placeholder.

### Segmented Control

- **Track:** 8px radius, divider fill, 2px padding; 32px tall on desktop, 44px in touch mode.
- **Active option:** paper fill, 6px radius, shadow-sm, ink text. Idle options use tool gray and darken on hover.
- **Semantics:** these are toggle groups (`aria-pressed`), not tabs. Label each group for assistive technology.

### Page Card

- **Shape:** 8px radius, 1px hairline, paper fill, 16px internal padding; no shadow at rest.
- **Hover:** border shifts to gray-300 and shadow-md appears. The card lifts, the content does not move.
- **Copy:** title at 14px/600 gray-800; slug and time at 12px muted gray; status dot or badge, then actions.
- **Actions:** Console VButton sm for preview/visit, a ghost icon button for the overflow menu.

### Chat Composer and Messages

- **Composer:** 16px radius, 1px hairline, paper fill, shadow-sm; focus-within shifts the border to quiet gray. The textarea is 13px/24 with a muted placeholder; the model selector sits in the footer row; send is a 32px ink circle.
- **User bubble:** divider fill, 16/16/6 radius, 14px/24 message type, 10×14 padding.
- **Assistant text:** plain prose, no bubble; the operation log is the record of work.
- **Process rows:** 13px tool gray at 24px line height. A running row pulses only its leading glyph; the text itself never drops below AA contrast.

### Element Inspector (Signature)

- **Shape:** 12px radius, 288px wide, paper fill, 1px hairline, floating-panel shadow, 12px padding.
- **Behavior:** opens at the selected element, focuses itself, closes on Escape, and returns focus to the canvas. Selection is an indigo 2px outline; hover is a 1px indigo-400 outline with a 5% indigo fill.
- **Type:** 12px labels and compact 28px buttons. This is the most literal expression of the atelier: the tool appears exactly where the work is.

### Source Editor

- **Canvas:** white surface, monospace 13px/18.2, 4px vertical content padding.
- **Gutter:** #f5f5f5 background with #6c6c6c line numbers.
- **Chrome:** the HTML/CSS/JS segmented control sits above the editor; validation notices float below it so the language switch remains reachable.

## Do's and Don'ts

### Do:

- **Do** use graphite ink for primary actions and primary text; let paper and canvas ground carry structure.
- **Do** keep mint at or below roughly 10% of a screen, reserved for focus, active, and creation choices.
- **Do** pair status text, surface, and border from one hue: 700 text on the 50 surface.
- **Do** use 13px as the default UI size, 12px as the metadata floor, and 10–11px only for badges.
- **Do** keep shadows for floating layers and hover lift; resting surfaces stay flat with a 1px hairline.
- **Do** keep the canvas the largest calm area; validation and hints float over it without resizing it.
- **Do** preserve Console-owned primitives in dialogs, forms, and lists, and the plugin's 8/12/16 radii in workbench surfaces.

### Don't:

- **Don't** use gradients, glass blur, or stacks of colorful large-radius cards; generic SaaS dashboards are the confirmed anti-reference.
- **Don't** use gradient text; emphasis comes from weight, size, or the semantic hue.
- **Don't** use quiet gray (#9ca3af) for metadata, help text, or placeholders; it fails AA on paper.
- **Don't** put gray supporting text on a tinted status surface; tint from the same hue or use the foreground ink.
- **Don't** mix 4px Console radii into plugin panels, or 12px plugin radii into Console modals.
- **Don't** add a second display face; the system sans is the voice.
- **Don't** use 10px outside badges, or let supporting text fall below 12px.
