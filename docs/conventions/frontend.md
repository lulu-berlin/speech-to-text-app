# Frontend conventions

Approved 2026-10-05 in roadmap item 02 (spec: [docs/specs/repo-skeleton-and-conventions.md](../specs/repo-skeleton-and-conventions.md)). Every frontend item follows these rules; deviations need a new ADR.

## Layout

```
src/
  app/              App root, providers (ThemeProvider, later API provider)
  features/<feature>/   page-level components per domain (queue, history)
  shared/           reusable components, hooks, theme/, api client
```

- Tests are colocated: `<name>.test.tsx` next to the component it covers.
- One feature one directory; no component reaches into another feature's directory — cross-feature code lives in `shared/`.

## Naming and typing

- Components PascalCase; file named after the component; named exports only (no default exports).
- Props type named `<ComponentName>Props`.
- TypeScript strict mode on; `import type` for type-only imports.

## Theming (styled-components)

- All design values live in `src/shared/theme/theme.ts` as tokens: colors, spacing, typography, radii.
- `ThemeProvider` at the app root; components consume tokens via themed `styled` components or `useTheme`.
- No inline `style={}` props, no literal colors or sizes in components. New tokens go into `theme.ts`, not into components.
- Exception: `StenographerAnimation.tsx` sets per-element `animation-delay`/`animation-duration` via JSX `style` ([ADR 0040](../decisions/0040-stenographer-timing-in-jsx.md)).
- Lengths in the tokens are `rem`, never `px` (not even in `theme.ts`), so sizes follow the reader's font size; color roles are plain `#rrggbb` so their contrast can be measured (only the overlay and shadow values carry alpha).
- `src/conventions.test.ts` (part of `pnpm test`) enforces this: it fails on any literal color (hex, `rgb()`, `hsl()`) or length (`px`, `rem`, `em`, `ex`, `ch`, `vh`, `vw`, `vmin`, `vmax`) in `src/` outside the token modules in `src/shared/theme/` and the tests, and on raw `px` everywhere, the token modules included. A new value goes into the tokens.

## API access

- All backend access goes through `/api` (same-origin in production, where the backend serves `dist/`; Vite dev server proxies `/api` to the backend).
- Typed API client in `src/shared/api/` (added with the first API item); components never fetch directly.

## Formatting (ADR 0015)

- Frontend files are formatted with prettier (`.prettierrc` at repo root: semi, single quotes, print width 100, trailing commas); `prettier --check` runs inside `pnpm test` and fails the command on violations. Format before committing.

## Errors

- Views render a shared error state when a load fails; action failures show inline errors; never swallow errors silently.

## Tests

- Vitest + React Testing Library; one render test per view, assertions on visible behavior (queries by role/text, not markup).
- MSW for API mocks from the first API-consuming item on.
