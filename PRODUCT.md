# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Primary users (explicitly excluding agencies and managed-service operators):

- **Individual site owners**: maintain their own Halo site and create pages for their own campaigns, brands, or topic collections.
- **Enterprise content/marketing teams**: a super administrator represents the team to ship campaign pages, product launch pages, and brand introduction pages.
- **Independent developers/technical users**: prefer source editing, MCP, and external agents to build and integrate on their own.

Currently every entry point is limited to super administrators; the plan is to relax this to multiple roles, but the specific role and permission model is undecided.

## Product Purpose

Create pages for Halo in natural language: the AI combines the current theme's style and the site's existing content to generate a page, and the user keeps refining it through conversation, a visual canvas, or source code, then publishes once the preview looks right. External agents can run the same create, edit, preview, and publish flow through MCP.

Signs of product success:

- The first generation is usable, and the user can publish with little rework;
- Published pages do not feel disconnected from the current theme; visitors cannot tell the plugin generated them;
- Usage expands from single pages to theme templates and eventually a complete theme.

## Positioning

Four capabilities together form the differentiation, and future product decisions should prioritize them:

1. **Theme integration**: reuse the current theme's layout, header/footer, and base styles instead of outputting standalone pages detached from the site.
2. **AI conversational authoring**: natural-language generation with continuous refinement through conversation and canvas annotations, rather than a traditional drag-and-drop builder.
3. **Site-content awareness**: read the site's existing posts, pages, and theme materials to generate content.
4. **External agent access**: let Claude Code, Codex, and similar clients create, edit, and publish pages directly through MCP.

All of this happens inside the Halo site; adjacent generic site builders cannot reproduce the same result without access to the site's theme and content.

## Operating Context

- Entry points: Halo Console "Content → Page Workshop" and the site's `/mcp` endpoint for external agents.
- Requirements: Halo 2.26+; AI features depend on AI Foundation 1.1.1+ with a language model that supports tool calling; MCP access depends on MCP Server 1.3.0+.
- Page lifecycle: AI creation / manual blank page / ZIP import → editor (Edit, Source, Run views) → save draft → preview → publish or update publication → unpublish; historical revisions can be previewed, compared, and restored.
- Page settings include title, description, access path, whether to reuse the theme layout, and whether to block public access.
- Published scripts execute in the site environment; previews use an isolated sandbox.
- Page content and progress use the terms defined in `GLOSSARY.md`: Working Copy, Checkpoint, Run Budget, Draft, Source Edit, Page Preview, Token Preview, Conversation Turn, Import Review.
- Deployment form: single instance.

## Capabilities and Constraints

Confirmed capabilities: AI creation and conversational editing; visual and source editing; theme layout reuse; desktop, tablet, mobile, and light/dark previews; drafts, publishing, updating a publication, and unpublishing; historical revisions and code diffs; cloning; ZIP import and export; pre-import AI security review; MCP tools covering listing, creation, updates, exact patches, previews, publishing, unpublishing, and history restoration.

Constraints and confirmed boundaries:

- Currently limited to super administrators; the plan is to relax this to multiple roles, with the permission model still to be designed.
- Single-instance deployment only, and this is a long-term constraint.
- ZIP uploads and extracted contents must not exceed 4 MiB in total and 64 entries at most; external images and fonts keep their original links and are not packaged.
- Import review results are bound to the file contents and kept for 30 minutes; imports are blocked when AI is unavailable or the review fails.
- Preview links are valid for 15 minutes and can be viewed by anyone holding them without signing in; treat them as temporary credentials.
- Currently only creates new pages and adapts them to the existing theme; it cannot directly modify theme templates or styles.
- Updating an existing page requires the version number read earlier; on conflict, re-read rather than overwrite.

## Brand Commitments

- Official Halo plugin; author/maintainer is Halo; repository lives under `halo-dev`.
- Product name: 「AI 页面工坊」 (English: AI Page Builder).
- The Console UI is primarily Chinese; documentation and user-facing copy use concise, direct Chinese.
- License GPL-3.0; use the official logo and app-market listing.

## Evidence on Hand

- `README.md`: features, use cases, installation, and usage requirements.
- `docs/usage.md`: creation, editing, preview and publishing, history, import/export.
- `docs/mcp.md`: external agent access and prompt examples.
- `docs/development.md`: local development, testing, and building.
- `docs/adr/0001-page-run-owns-transitions.md`: architecture decision for the page-run state machine.
- Product screenshots under `images/`: `editor.png`, `list.png`, `new.png`, `history.png`.
- `GLOSSARY.md`: domain terms used during page generation.

There are currently no customer cases, testimonials, quantitative benchmarks, or third-party reviews; future work must not fabricate them.

## Product Principles

1. Theme first: every generated result must fit the current theme; a page that feels disconnected from the theme is a failure.
2. First-generation quality first: less rework matters more than stacking features.
3. One capability, multiple entry points: Console conversation, visual/source editing, and external agents/MCP share the same page facts and operation semantics.
4. Explicit confirmation for risky actions: publishing scripts, importing external ZIPs, and public access must be reviewed or confirmed.
5. Gradual boundaries: make the page layer solid first, then expand to theme templates and complete themes as planned, without promising ahead.
