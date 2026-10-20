# Repository Guidelines

## Project Structure & Module Organization

AI Page Builder is a Halo 2.26+ plugin with a Java backend and Vue 3/TypeScript Console UI.

- `src/main/java/run/halo/aipagebuilder/`: `model` resources, `page` APIs/rendering, `content` validation, `ai` orchestration, and `theme` integration.
- `src/main/resources/`: plugin metadata, templates, and logo.
- `src/test/java/`: backend tests mirroring production packages.
- `ui/src/`: views, components, composables, editor utilities, APIs, and assets; frontend tests live in adjacent `__tests__/` directories.

## Build, Test, and Development Commands

Use Java 21, Node.js >=22.12.0, and pnpm 12. Docker is required for the local Halo instance and OpenAPI generation. Run these commands from the repository root:

- `pnpm --dir ui install --frozen-lockfile`: install frontend dependencies.
- `bash gradlew haloServer`: start Halo at `http://localhost:8090`.
- `pnpm --dir ui dev`: watch and rebuild UI assets in another terminal.
- `bash gradlew test -x processUiResources`: run Java tests without rebuilding UI resources.
- `pnpm --dir ui test:unit --run`: run frontend tests once.
- `pnpm --dir ui type-check`: check Vue/TypeScript types.
- `pnpm --dir ui build-only`: build production UI assets.
- `CI=true bash gradlew build`: run the full build and checks; plugin JARs appear in `build/libs/`.

## Coding Style & Naming Conventions

Follow `.editorconfig`: four-space Java indentation, two-space frontend indentation, and 100-column Java/frontend lines. Prettier uses single quotes and no semicolons. Use PascalCase Java classes and Vue components, camelCase methods, and `useXxx.ts` composables. Follow existing `<script setup lang="ts">` components. `pnpm --dir ui lint` runs Oxlint/ESLint autofixes; `pnpm --dir ui format` applies Prettier. Keep formatting changes scoped.

## Testing Guidelines

Backend tests use JUnit Jupiter, AssertJ, Mockito, and Reactor Test; name classes `*Test.java`. Frontend tests use Vitest, Vue Test Utils, and jsdom; name files `*.spec.ts`. No numeric coverage threshold is configured. Add regression tests for changed behavior. For editor changes, also verify desktop/mobile interactions in Halo; unit tests do not establish real-model or packaged-plugin behavior.

## Commit & Pull Request Guidelines

History commonly uses `feat(scope):`, `fix(scope):`, and `refactor(scope):`, with Chinese or English summaries. Prefer that pattern, for example `fix(editor): preserve selection after preview refresh`. Target `main`; describe behavior changes, link relevant issues, list validation performed, and attach screenshots for UI changes.

## Generated Files & Local Data

Never hand-edit `ui/pnpm-lock.yaml`, `build/openapi/`, or `ui/src/api/generated/`. Regenerate clients with `bash gradlew generateApiClient -x processUiResources -x test -x :ui:check`. Preserve `workplace/`, which stores local Halo data and model configuration; keep credentials out of commits.
