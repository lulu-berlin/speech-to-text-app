# 24 — Design system: tokens and primitives

Status: in-progress
Depends on: [13](../docs/specs/frontend-foundation.md) (done)

## Goal

Establish the app's visual language as code before the Queue and History views are built: a full token set (light and dark), a self-hosted typeface, and a small library of accessible shared primitives, so items 14 and 15 build their views with it rather than being restyled afterwards.

## Already decided

- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): styled-components.
- [Frontend conventions](../docs/conventions/frontend.md): all design values live in `src/shared/theme/theme.ts` as tokens; **no literal colors or sizes in components**; shared components in `src/shared/`; named exports; colocated tests.
- Item 13 decided the direction: **calm, document-like**; light and dark. It built the shell, the router, the connection indicator and a native-`<dialog>` `ConfirmDialog`, but the theme has only four colors, one spacing unit, two radii and one font size, and the views are placeholders.
- [ADR 0026 — No rule for where test fixtures live](../docs/decisions/0026-no-rule-for-test-fixture-location.md): the self-hosted font is committed like the other assets, with its licence notice.
- [ADR 0033 — Design system: tokens, Radix primitives, IBM Plex Sans](../docs/decisions/0033-design-system-tokens-radix-plex.md): the decisions below, recorded when this spec was approved.
- User decisions (2026-10-09): this item comes **before** items 14 and 15; it is the **design system only** (the visual pass is item 25); the palette **keeps the current accent**; icons come from **`lucide-react`**; interactive primitives are built on **Radix** (Dialog, Tooltip, DropdownMenu, Switch, Slider); the typeface is **IBM Plex Sans**, self-hosted; tokens are a **TypeScript object only**; dark mode **follows the OS with a manual override**; **layout primitives** (`Page`, `Stack`, `Row`) are part of the system.

## Proposal (approved 2026-10-09)

- **Dependencies** (each changes `pnpm-lock.yaml` and the flake's `fetchPnpmDeps` hash, verified by the user, ADR 0012):
  - `@radix-ui/react-dialog`, `-tooltip`, `-dropdown-menu`, `-switch`, `-slider` (behavior and accessibility only; all styling is ours, from tokens);
  - `lucide-react` (icons).
- **Tokens** (`src/shared/theme/theme.ts`), one set for light and one for dark:
  - colors by semantic role: `background`, `surface`, `surfaceRaised`, `text`, `textMuted`, `border`, `accent`, `accentText`, `focusRing`, `danger`, plus the five status groups below;
  - a **speaker palette** (added 2026-10-09, item 15): an ordered list of per-speaker tints, one light set and one dark set, each tint meeting AA for text in both themes; speaker N uses entry N mod the palette size (speakers go up to 20, item 12). Settled in cycle 3: **20 entries per theme** (one per allowed speaker), used as the **speaker's name text color**, chosen by farthest-point selection in OKLab (min pairwise ΔE 7.8, consecutive 16.5, against 2.2 for a hue gradient) with every tint at the AA boundary of its theme;
  - **a real type scale and spacing rhythm** (`fontSizes`, `fontWeights`, `lineHeights`, `spacing`) — today there is a single `fontSizes.title` and a single `spacing.unit`, which cannot express hierarchy;
  - `radii`, `shadows` (elevation), `motion` (durations and easings), `breakpoints`.
  - Plain TypeScript objects (no CSS custom properties). The native `<dialog>` backdrop and focus rings are styled inside the styled components (`::backdrop`, `box-shadow`), so no literal values are needed anywhere.
  - `themeFor(prefersDark)` stays the entry point; `usePrefersDark` (item 13) is extended by the manual override below.
- **Typeface**: IBM Plex Sans (OFL), self-hosted as a woff2 in the repo with its licence notice, latin subset, weights 400/500/600 (variable if it is no larger). `body` uses it with the system stack as fallback.
- **Status groups** (five, not six colors — the exact status stays visible as the badge text):
  | group | statuses | treatment |
  | ----- | -------- | --------- |
  | pending | QUEUED, PAUSED | neutral |
  | active | RUNNING | accent |
  | done | COMPLETED | success |
  | problem | FAILED | danger |
  | gone | CANCELED | muted |
- **Layout primitives** (`src/shared/ui/`): `Page` (max width, centered, page padding), `Stack` (vertical rhythm by spacing token), `Row` (horizontal, gap by token). Views compose layout from these instead of writing padding by hand.
- **Other primitives** (`src/shared/ui/`), each with variants and states, built on tokens only:
  - `Button` (primary / secondary / danger; hover, focus-visible, active, disabled, loading), `IconButton`, `Icon` (wraps `lucide-react` with the token size and color);
  - `Card`/`Surface`, `StatusBadge` (the five groups above), `ProgressBar` (transcription, and the two-stage diarization bar from item 11's contract), `Spinner`, `Skeleton`, `EmptyState`, `List`/`ListRow` (row and card styling for the Queue and History lists, with hover and focus-visible states), `Tooltip` (Radix), `DropdownMenu` (Radix, for per-task row actions), `Switch` (Radix, for the queue pause toggle), `Slider` (Radix, for the audio seek bar in item 15);
  - `ConfirmDialog` is reworked onto Radix's dialog (focus trapping) while keeping the item 13 behavior (only a confirm runs the action; Escape cancels).
- **Dark mode**: follows the OS by default; a small control in the header cycles `auto → light → dark` and the choice is stored (localStorage) and overrides the OS.
- **Accessibility, part of the primitives, not a later pass**: AA contrast in both themes, visible focus rings on every interactive element, full keyboard operation, `prefers-reduced-motion` respected, correct roles and labels.
- **This item does not restyle the views.** It delivers tokens, the typeface, layout and primitives with their tests; items 14 and 15 consume them and item 25 does the final visual pass.
- **Recorded**: [ADR 0033](../docs/decisions/0033-design-system-tokens-radix-plex.md) holds these decisions.

## Open questions (answered 2026-10-09)

- Icons? → **`lucide-react`.**
- Radix primitives? → **Dialog, Tooltip, DropdownMenu, Switch, Slider** (all five).
- `StatusBadge` states? → **Five semantic groups** (pending, active, done, problem, gone); the exact status stays as the badge text.
- Tokens? → **A TypeScript object only.** `::backdrop` and focus rings are styled inside the styled components, so no literal values are needed.
- Dark mode? → **OS-follow with a manual override**, cycled `auto → light → dark` and stored.
- Layout primitives? → **`Page`, `Stack`, `Row` included.**
- Typeface? → **IBM Plex Sans, self-hosted.**
- Font weights and format? → **The variable woff2 if it is not larger than three static files (400/500/600);** the workspace agent compares the sizes at its first checkpoint and reports which it used.
- Dark-mode control? → **An icon-only `IconButton` with a tooltip**, so the header stays compact.

## Remaining open questions

None.

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-09 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.

- [ ] Every primitive renders its documented variants and states using token values only (no literal colors or sizes); split by component (user, 2026-10-09), each sub-item asserts the computed styles against the token values:
  - [ ] `Button`: primary, secondary and danger; disabled; loading
  - [ ] `IconButton`
  - [ ] `Card`/`Surface`
  - [ ] `Spinner` and `Skeleton`, which stop animating under `prefers-reduced-motion`
  - [ ] `EmptyState`
  - [ ] `List`/`ListRow`
  - [ ] `Tooltip`
  - [ ] `DropdownMenu`
  - [ ] `Switch`
  - [ ] `Slider`
- [ ] `StatusBadge` renders the five status groups, each with its own token-based treatment, and shows the exact status text
- [ ] `ProgressBar` renders the transcription value, and both diarization stages (embedding, clustering)
- [ ] `Page`/`Stack`/`Row` apply the token spacing and max width
- [ ] Interactive primitives show a visible focus ring when focused by keyboard
- [ ] The dialog primitive traps focus and closes on Escape
- [ ] The dark-mode control cycles auto → light → dark, stores the choice, and overrides the OS preference
- [ ] The icon wrapper renders a token-sized, token-colored icon and is hidden from assistive technology when decorative
- [x] Text and background contrast meets AA in both the light and the dark token set (measured)
- [x] (discovered, confirmed 2026-10-09) A convention check fails on any literal color (hex, `rgb()`, `hsl()`) or length (`px`, `rem`, `em`, `vh`) in `src/` outside the tokens (`src/shared/theme/`) and the tests, and on raw `px` everywhere, the token modules included (user, 2026-10-09)
- [ ] (discovered, confirmed 2026-10-09) The body text uses IBM Plex Sans with the system stack as fallback (the font's shipping and loading are verified as acceptance work: the woff2 in `dist/assets`, `document.fonts` in Chromium)
- [x] (added 2026-10-09, item 15) The speaker palette provides one tint per speaker index, wraps around, and meets AA in both themes

## Acceptance criteria

- Tokens, the typeface and the primitives exist with the variants items 14 and 15 need; no component contains a literal color or size.
- Contrast meets AA in both themes; keyboard focus is visible on every interactive primitive.
- The font file's licence and origin are recorded next to the asset; `nix build .` ships it.
- `pnpm test` and `pnpm build` are green; the user's `nix build .` succeeds with the new dependencies.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-09: item drafted at the user's request (the app had no item for its visual design). Restructured by the user's answers: this item is the design system only and comes before items 14 and 15; Radix (Dialog, Tooltip, DropdownMenu, Switch, Slider) plus `lucide-react`; palette keeps the current accent; IBM Plex Sans self-hosted; tokens as a TypeScript object only; dark mode follows the OS with a manual override; layout primitives included; the visual pass over the views is item 25.
- 2026-10-09: test list (9 items) approved by the user; [ADR 0033](../docs/decisions/0033-design-system-tokens-radix-plex.md) written; status `spec-approved`.
- 2026-10-09 (workspace, start): answers from the user. Item 1 is split by component (ten sub-items). A convention check for literal colors and lengths is added (ADR 0033's "convention check" did not exist yet). The font gets a test-list item (body text uses IBM Plex Sans with the system fallback) and acceptance verification (woff2 in `dist/assets`, `document.fonts` in Chromium). Cycle order confirmed: contrast, font, layout primitives, icon wrapper, simple primitives, StatusBadge, ProgressBar, dialog, Radix primitives, focus ring, dark-mode control. Typeface: the variable woff2 (`@fontsource-variable/ibm-plex-sans@5.3.0`, latin, weight axis 100–700, 45,712 bytes), smaller than the three static files 400/500/600 (22,588 + 24,184 + 24,252 = 71,024 bytes). Dependencies pinned at releases at least 10 days old: Radix `react-dialog@1.1.23`, `react-tooltip@1.2.16`, `react-dropdown-menu@2.1.24`, `react-switch@1.3.7`, `react-slider@1.4.7`; `lucide-react@1.48.0`. The sandbox has no `/bin/sh`, so `pnpm test`/`pnpm build` are run as their underlying commands through `node`.
- 2026-10-09: the user placed the convention check as cycle 2, so the font moves to cycle 3.
- 2026-10-09 (workspace, cycle 1): `Test: text and background contrast meets AA in both token sets`. In `theme.test.ts`, the WCAG 2.x relative luminance and contrast ratio are computed from the tokens; the helper reproduces the known values 21.00 (black/white) and 4.48 (`#777777`/white). Red: 24 failures (`TypeError: Cannot read properties of undefined (reading 'slice')` / `(reading 'pending')`), because the tokens had no `surface`, `surfaceRaised`, `textMuted`, `accentText`, `focusRing`, `danger` or `status`. Green: `ThemeTokens.colors` gains those roles plus `status: Record<StatusGroup, { background, text }>` (new `StatusGroup` type). `background`, `text`, `accent` and `border` are unchanged, so the accent is kept. Measured minimums: text pairs light 5.63 (`status.gone`), dark 5.96 (`status.gone`), against the 4.5:1 threshold; focus ring light 6.69, dark 6.71, against 3:1. 6 files / 41 tests; `tsc --noEmit`, `vite build`, prettier `--check` clean.
- 2026-10-09: spec addition approved by the user in the item 15 consultation. Item 15 shows each speaker's name with a subtle per-speaker tint, so the tokens gain a speaker palette: an ordered list of tints, one light and one dark set, each meeting AA for text in both themes, and speaker N uses entry N mod the palette size. Speakers go up to 20 (item 12), so about 8 entries. The workspace agent proposes the size and colors at the next checkpoint. Added to the Proposal's token list and as a test-list item; the palette gets its own red/green cycle.
- 2026-10-09 (workspace, cycle 2): `Test: the design-value convention fails on literal colors and lengths`. New `src/conventions.test.ts` reads every `src/**/*.{ts,tsx}` through `import.meta.glob('/src/**/*.{ts,tsx}', { query: '?raw', eager: true })` (so it needs no Node types; `tsconfig.json` gains `vite/client` — a config change, no lockfile change). It fails on color literals (hex, `rgb(`, `hsl(`) and length literals (a number or `${…}` interpolation followed by `px|rem|em|ex|ch|vh|vw|vmin|vmax`) outside `src/shared/theme/theme.ts` and the test files, and on raw `px` everywhere, `theme.ts` included (user decision). Red: 15 violations — `100vh` in App, `${…spacing.unit * n}px` in App, AppHeader and ConfirmDialog, the `1px` borders, and the `8px`/`4px` radii in `theme.ts`. Green: `spacing` becomes the rem scale `xs|sm|md|lg|xl|xxl` (0.25–3rem; components cannot compute `${unit * n}px` any more, so the real scale the spec asks for lands here), plus `borderWidths.thin` (0.0625rem = 1px), `sizes.viewportMinHeight` (100vh) and `radii` in rem; App, AppHeader and ConfirmDialog consume the named tokens. Positive controls: a throwaway file with `#fff`, `2rem` and `1px` was reported three times, a raw `1px` in `theme.ts` was reported as `raw px`, and a probe confirmed the glob sees 32 files, so the pass is not vacuous. No visual change at the default root font size (0.5rem = 8px). 8 files / 42 tests; `tsc --noEmit`, `vite build`, prettier `--check` clean.
- 2026-10-09: cycle 2 refactors approved by the user, each its own commit: (1) violations report the offending value (`#fff`, `2rem`, `1px`) instead of the whole line — positive control re-run; (2) the WCAG `luminance`/`contrast` helper moved to `src/test/wcag.ts` and imported by `contrast.test.ts`. 8 files / 42 tests green after each.
- 2026-10-09 (workspace, cycle 3): `Test: the speaker palette covers every speaker index, wraps, and meets AA`. The user asked for contrasting rather than gradient tints and to see the colors, so two variants were generated in an untracked `palette-preview.html` at the worktree root, using farthest-point selection in OKLab (ΔE = Euclidean distance in OKLab, averaged over the two themes). Rejected: a hue ramp at 18° steps (min pairwise ΔE 2.2) and a widest-spread variant (pairwise 10.6, but near-black light tints such as `#3f3802` at AA 11). Chosen: variant 2 — AA tiers 4.6/7.0, saturation ≤ 0.85, pairwise ΔE 7.8, consecutive 16.5, tint ratios 4.60–7.07 — with 20 entries (the user chose one per allowed speaker over 8). Treatment: the tint is the **speaker name's text color**; a chip reads as a label but is visually heavier, and a 12% faint chip is only 1.15 against the page. Red: `AssertionError: light palette size: Target cannot be null or undefined.` Green: `colors.speakerTints: readonly string[]` in both themes, and `speakerTint(tints, index)` = `tints[index % tints.length]` (index is item 12's 0-based speakerId; throws on an empty palette). Positive control: a dark tint forced to `#20242a` failed with `dark tint #20242a: expected 1.1619… to be >= 4.5`. 9 files / 43 tests; `tsc --noEmit`, `vite build`, prettier `--check` clean. The preview file is untracked and will be deleted before review.
