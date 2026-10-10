# 17 — Branding: stenographer animation

Status: in-review
Depends on: [13](../docs/specs/frontend-foundation.md) (done), [24](../docs/specs/design-system.md) (done)

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

- [x] The app shell header shows the stenographer next to the product name
- [x] An empty state shows the larger instance, and a loading region shows it in place of a spinner or skeleton
- [x] The animation is hidden from assistive technology everywhere it appears
- [x] With reduced motion requested, the static frame is shown and nothing animates
- [x] Without a reduced-motion preference the animated parts carry an animation
- [x] While a task is running the stenographer types; with nothing running it rests
- [x] The artwork's colors come from theme tokens (changing an illustration token changes the rendered color)
- [x] Two instances on one page do not collide: each renders its own gradient and clip-path ids
- [x] The component's styles do not leak: an element outside it with a class like `word` or `head` is unaffected
- [x] The favicon is the static stenographer artwork

## Acceptance criteria

- The user approves the look and motion in a real browser, light and dark, with and without reduced motion.
- The asset adds at most 15 KB gzipped to the bundle, and no new runtime dependency.
- No rule of the artwork's `<style>` reaches outside the component; no literal color or size remains in it.
- Smooth playback: no layout work per frame (`transform`, `opacity`, `fill` only), checked in the browser performance panel.
- The artwork's origin and licence are recorded next to the asset (`PROVENANCE.md`); the favicon and still are committed.
- `pnpm test` and `pnpm build` are green; if frontend dependencies changed, the user's `nix build .` succeeds.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-10 (workspace, cycle 10): `Test: the favicon is the static stenographer artwork`. Red: `AssertionError: expected undefined to be defined` — `index.html` had no favicon link. Green: `frontend/src/shared/brand/favicon.svg` — the artwork's static frame (same drawing, `<style>` block and `role`/`aria-label` stripped, 10,301 bytes) — linked from `index.html` as `/src/shared/brand/favicon.svg`; `vite build` bundles it: `dist/assets/favicon-Cm6Q6mva.svg` (2.36 kB gzip) with the rewritten href, so the packaged app carries the favicon (the `publicDir: 'mock'` setup does not copy a public dir). The test reads both files with `import.meta.glob` `?raw` (the repo's convention-check pattern — the frontend tsconfig has no node types, so `node:fs` is out). Suite 75/75, prettier and `tsc --noEmit` clean; `vite build` clean. All 10 test-list items green.
- 2026-10-10 (workspace, wrap-up): all 10 test-list items green; `Status: in-review`. The static derivatives are committed: `favicon.svg` (linked from `index.html`, bundled by Vite — verified in `dist/`) and `stenographer-still.svg` (keeps `role="img"` for docs), both derived from the artwork and recorded in `PROVENANCE.md`. New lasting decision documented as [ADR 0038](../docs/decisions/0038-stenographer-timing-in-jsx.md) (per-element `animation-delay`/`animation-duration` in JSX — narrow exception to the no-inline-style convention). Verified in-sandbox: 75/75 vitest, `tsc --noEmit` clean, prettier clean, `node node_modules/vite/bin/vite.js build` clean (dist 133.9 kB gzip JS total; the inlined component ≈ 4.7 kB gzipped, inside item 17's 15 kB budget). Remaining for the acceptance criteria (user, real browser): approve the look and motion in light and dark, with and without reduced motion, and check the performance panel for transform/opacity/fill only. Reconcile with items 14/15 which queue/history loading regions switch from skeletons to the stenographer (their specs predate this item; the App-shell connecting region is the demo pattern).
- 2026-10-10 (workspace, cycle 9): `Test: an empty state shows the larger instance, and a loading region shows it in place of a spinner or skeleton`. Red: `Error: The element did not render` — the empty state had no svg. Green: `EmptyState` now leads with `<StenographerAnimation size="empty" />` (icons stay optional); the App shell gained the demo loading region — a `ConnectingGate` inside the connection provider renders the mascot at the empty size plus a "Connecting…" label until the first snapshot lands (the pattern items 14/15 copy when they switch their loading regions). The existing EmptyState test now targets the icon svg (the second one). One structural fix along the way: the `live` selector had to live in a gate component inside the provider, because App's own render sits outside the provider it creates. Suite 74/74, prettier and `tsc --noEmit` clean.
- 2026-10-10 (workspace, cycle 9 refactor, approved): `Refactor: move the connecting region into its own file` — `src/app/ConnectingRegion.tsx` (base + gate + label), `App.tsx` back to shell composition; suite 74/74 green.
- 2026-10-10 (workspace, cycle 8): `Test: the animation is hidden from assistive technology everywhere it appears`. Red: `AssertionError: expected null to be 'true'` — the svg had no `aria-hidden`. Green: the svg gained `aria-hidden="true"` (the artwork's `role="img"`/`aria-label` are simply not rendered), and the component comment now states the decorative contract. Since every placement renders this component — the header today, the empty states next — the component-level assertion is the "everywhere" guarantee. Suite 73/73, prettier and `tsc --noEmit` clean.
- 2026-10-10 (workspace, cycle 7): `Test: the app shell header shows the stenographer next to the product name`. Red: `AssertionError: expected null to be <h1 …>` — the first svg in the header was the theme icon's glyph, not the mascot. Green: `AppHeader` renders `<StenographerAnimation typing={running} />` directly before the title, with `running` read from item 13's connection machine (`any task.status === 'RUNNING'`), and its stale "item 17 adds this here" comment updated. The test asserts the mascot is the immediate sibling before the product-name heading and computes the `stenoHeader` size (2.25rem → 36 px). Suite 72/72, prettier and `tsc --noEmit` clean.
- 2026-10-10 (workspace, cycle 6): `Test: while a task is running the stenographer types; with nothing running it rests`. Red: `AssertionError: expected undefined to be defined` — the component had no `typing` prop and no resting gate. Green: `StenographerAnimation` gained `typing?: boolean` (default false) and the wrapper gained a conditional resting block — when not typing, `.keys rect, .f, .thumb, .word` freeze (`animation: none`, after the animation rules so the cascade is right) and the words stay visible, while the idle parts keep their slow motion. Test-infrastructure discovery: styled-components keeps a rendered component's (dynamic) rules in the sheet even after unmount, so a per-render absence assertion needs a fresh document — the typing test lives in its own colocated file `StenographerAnimation.typing.test.tsx`, and the item-5 test's "animated rules" filter now excludes the `animation: none` resting rules. Suite 71/71, prettier and `tsc --noEmit` clean.
- 2026-10-10 (workspace, cycle 5): `Test: two instances on one page do not collide`. Red: `AssertionError: expected true to be false` — both instances rendered the artwork's static def ids (`bg`, `machineFront`, `desk`, `glow`, `circle`), so the id sets were not disjoint. Green: the defs gained a per-instance prefix from React `useId` (`defId` names the five suffixes), and every `url(#…)` fill/clip-path reference resolves through it; the test renders two components and asserts the id sets are disjoint and each instance's references resolve within itself. Two tests needed no behavior change; the test file's indexed access (`instances[0]`) tripped the project's `noUncheckedIndexedAccess` — a length assertion plus non-null assertion reads it. Suite 70/70, prettier and `tsc --noEmit` clean.
- 2026-10-10 (workspace, cycle 4): `Test: with reduced motion requested, the static frame is shown and nothing animates`. Red: `AssertionError: expected 0 to be greater than 0` — no `prefers-reduced-motion` media block existed. Green: the wrapper gained the artwork's fallback — `@media (prefers-reduced-motion: reduce) { & * { animation: none !important; } & .word, & .wave { opacity: 1; } }` (the star rule scoped to the component class, words and waves visible). The test derives the component class from an emitted rule and asserts every rule inside the media block is scoped to it, including the `*`, with `animation: none !important` and the visible words/waves. Suite 69/69, prettier and `tsc --noEmit` clean.
- 2026-10-10 (workspace, cycle 3): `Test: without a reduced-motion preference the animated parts carry an animation`. Red: `AssertionError: expected 0 to be greater than or equal to 9` — no scoped rule declared an animation yet. Green: the wrapper now embeds the artwork's nine keyframes (`stt-wave`, `stt-bob`, `stt-blink`, `stt-drift`, `stt-press`, `stt-flash`, `stt-rise`, `stt-hum`, `stt-twinkle`, namespaced to avoid collisions; the px translates became % of the viewBox/fill-box so the token files hold no px), each animated rule references them, and the artwork's `nth-child` delays / per-class timings moved onto the elements as JSX `style` (waves, words, the 16 keys as data with their flash delays, the right arm, the eight fingers and thumbs) — the key flash animates `fill` (paint only) under item 17's relaxed motion budget. Test asserts every animated rule resolves to an emitted keyframes rule and samples the per-element delays; jsdom normalizes the `-0.10s` trailing zero to `-0.1s` when serializing, so that assertion reads the serialized form. Suite 68/68, prettier and `tsc --noEmit` clean.
- 2026-10-10 (workspace, cycle 2): `Test: the artwork's colors come from theme tokens`. Red: `AssertionError: expected 0 to be greater than 0` — the test renders the component under a theme with `illustration.accentTeal = '#123456'` and `backdropFrom = '#abcdef'` and expects shapes carrying those values, but the SVG body had no color yet. Green: `theme.ts` gained the approved `illustration` group (30 entries, light and dark) and `sizes.stenoHeader` (2.25rem) / `stenoEmpty` (8.75rem); the component gained the full 138-element artwork body as JSX — every fill, gradient stop and clip-path from `theme.illustration.*`, plus the `size?: 'header' | 'empty'` prop and width/height from the size tokens. Suite 67/67, prettier and `tsc --noEmit` clean.
- 2026-10-10 (workspace, cycle 1): `Test: the component's styles do not leak`. First checkpoint 2026-10-10: token grouping proposed (`theme.illustration.*`, 30 entries, light/dark table approved with a later visual sign-off; backdrop is a surface-derived gradient) plus mechanisms (header reads the connection machine and passes a `typing` prop; per-element delays via JSX `style` — a narrow exception to the no-inline-style convention, ADR candidate; new `sizes` tokens `stenoHeader` 2.25rem / `stenoEmpty` 8.75rem). Red: `Error: Failed to resolve import "./StenographerAnimation"` (vitest, `src/shared/brand/StenographerAnimation.test.tsx`; the file did not exist). Green: `src/shared/brand/StenographerAnimation.tsx` — a `styled.svg` wrapper nesting the artwork's rules under `&` (`.wave`, `.head` with `transform-origin: 50% 42%`, `.word`), so the generated selectors are scoped to the component class and no bare `.word`/`.head` rule exists; the test renders the component beside two outside `<div class="word|head">` elements and asserts the outside elements carry no style. Full suite 66/66, prettier and `tsc --noEmit` clean.
- 2026-10-09: consultation answers approved: the user supplied the artwork (`stenographer.svg`, now committed with `PROVENANCE.md`); it is inlined as a component via a styled wrapper with nested rules and `keyframes`, with its palette converted to tokens and unique `useId` ids per instance; it appears in the header, on the empty states and as the loading indicator, replacing the spinners and skeletons but not the progress bars; it types only while a task is running; the favicon and a still are derived from it; item 16 gains a dependency on this item. Test list not yet approved.
- 2026-10-09: test list (10 items) approved by the user; status `spec-approved`.
