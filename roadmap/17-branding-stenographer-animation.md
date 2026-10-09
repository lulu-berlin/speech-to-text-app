# 17 — Branding: stenographer animation

Status: spec-approved
Depends on: [13](../docs/specs/frontend-foundation.md) (done), [24](24-visual-design.md)

## Goal

Give the frontend a recognizable brand: an animated stenographer, shown in the app shell, on the empty states and wherever the app is waiting — so the app looks like itself rather than like a default browser page.

## Already decided

- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): React + TypeScript + styled-components.
- [ADR 0033 — Design system](../docs/decisions/0033-design-system-tokens-radix-plex.md) and [frontend conventions](../docs/conventions/frontend.md): all design values are theme tokens; no literal colors or sizes in components; shared components in `src/shared/`; named exports; colocated tests.
- [ADR 0012 — pnpm](../docs/decisions/0012-frontend-dependency-management-pnpm.md): any new dependency changes the lockfile and the flake's `fetchPnpmDeps` hash.
- **The artwork exists** (user, 2026-10-09): `frontend/src/shared/brand/stenographer.svg`, committed with `PROVENANCE.md` beside it. It is a self-contained SVG (no scripts, no external references, no fonts) with nine CSS animations and a `prefers-reduced-motion` fallback; 13,786 bytes, 3,203 gzipped. It was created by the project author with Claude (Anthropic); the origin is recorded in `PROVENANCE.md`.
- **What the artwork shows**: a seated stenographer at a steno machine under a lamp, hands typing with the keys flashing, a paper tape with words rising, on a circular warm background (cream to pink) with sparkles.

## Proposal (approved 2026-10-09)

- **Inline as a React component**, `frontend/src/shared/brand/StenographerAnimation.tsx` (decided 2026-10-09):
  - a **styled wrapper** (`styled.svg`) whose template nests every one of the artwork's rules under `&`, with the nine animations declared with styled-components `keyframes`. Styled-components scopes the selectors automatically — including the `*` inside the reduced-motion block — so **no rule of the artwork's `<style>` can leak into the app**, which the file's generic class names (`.word`, `.head`, `.eye`, `.keys`, `.f`) would otherwise cause;
  - the SVG body becomes JSX; `<stop>`/`fill` values come from theme tokens;
  - **unique ids per instance** (React `useId`) for the `defs` (`bg`, `machineFront`, `desk`, `glow`, `circle`), because the component appears more than once on a page and duplicate ids would collide;
  - per-element `animation-delay` values are set in JSX rather than by `nth-child`, so the look does not depend on element order;
  - the key-press flash keeps animating `fill` (paint only, no layout); the item 17 rule is relaxed to allow `fill` alongside `transform` and `opacity`.
- **Palette as tokens**: the artwork's 32 colors become grouped token entries in `src/shared/theme/theme.ts` (`theme.illustration.*`), light and dark, with near-duplicates collapsed (the three skin tones, the browns). The circular backdrop uses `surface` so the piece sits correctly in dark mode. The grouping is proposed at the first checkpoint.
- **Placement** (decided 2026-10-09):
  - the **app shell header**, next to the product name, 32–40 px;
  - the **empty states** of the Queue (item 14) and History (item 15), ~120–160 px;
  - as the **loading indicator**: it **replaces** item 24's `Spinner` and `Skeleton` — a view or region waiting on data shows the stenographer with a short label ("Transcribing…"), while the **progress bars stay** as they are (they show real progress, not just "waiting").
- **Behaviour** (decided 2026-10-09): the stenographer **types only while a task is running**; otherwise it rests on a static frame (a slow idle is enough). The header instance reads the running state from item 13's connection machine.
- **Accessibility**:
  - decorative everywhere it appears (the product name or the empty state's text carries the meaning), so it is `aria-hidden="true"` — the file's own `role="img"`/`aria-label` are overridden;
  - `prefers-reduced-motion: reduce` shows the static frame, scoped to the component;
  - no flashing faster than 3 times per second (WCAG 2.3.1) — the key flash is 0.9 s, fine.
- **Static derivatives**: an SVG favicon and a still image for docs, both from the same artwork.
- **Budget**: at most 15 KB gzipped for the asset (currently 3.2 KB); no new runtime dependency.

## Open questions (answered 2026-10-09)

- Who creates the artwork? → **The user did**; it is committed with `PROVENANCE.md`.
- How does it go into the app? → **Inlined as a component**, styled wrapper with nested rules and `keyframes`; the palette converted to tokens.
- Placement and size? → **Header (32–40 px), empty states (~120–160 px), and loading**; the favicon and a still come from the artwork.
- Loading use? → **It replaces the spinners and skeletons**, but not the progress bars.
- Should the animation reflect app state? → **Yes: it types only while a task is running**, and rests otherwise.
- Does item 16 (the Nix package) depend on this item, so the deliverable ships with the branding? → **Yes.**

## Remaining open questions

None.

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-09 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.

- [ ] The app shell header shows the stenographer next to the product name
- [ ] An empty state shows the larger instance, and a loading region shows it in place of a spinner or skeleton
- [ ] The animation is hidden from assistive technology everywhere it appears
- [ ] With reduced motion requested, the static frame is shown and nothing animates
- [ ] Without a reduced-motion preference the animated parts carry an animation
- [ ] While a task is running the stenographer types; with nothing running it rests
- [ ] The artwork's colors come from theme tokens (changing an illustration token changes the rendered color)
- [ ] Two instances on one page do not collide: each renders its own gradient and clip-path ids
- [ ] The component's styles do not leak: an element outside it with a class like `word` or `head` is unaffected
- [ ] The favicon is the static stenographer artwork

## Acceptance criteria

- The user approves the look and motion in a real browser, light and dark, with and without reduced motion.
- The asset adds at most 15 KB gzipped to the bundle, and no new runtime dependency.
- No rule of the artwork's `<style>` reaches outside the component; no literal color or size remains in it.
- Smooth playback: no layout work per frame (`transform`, `opacity`, `fill` only), checked in the browser performance panel.
- The artwork's origin and licence are recorded next to the asset (`PROVENANCE.md`); the favicon and still are committed.
- `pnpm test` and `pnpm build` are green; if frontend dependencies changed, the user's `nix build .` succeeds.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-09: consultation answers approved: the user supplied the artwork (`stenographer.svg`, now committed with `PROVENANCE.md`); it is inlined as a component via a styled wrapper with nested rules and `keyframes`, with its palette converted to tokens and unique `useId` ids per instance; it appears in the header, on the empty states and as the loading indicator, replacing the spinners and skeletons but not the progress bars; it types only while a task is running; the favicon and a still are derived from it; item 16 gains a dependency on this item. Test list not yet approved.
- 2026-10-09: test list (10 items) approved by the user; status `spec-approved`.
