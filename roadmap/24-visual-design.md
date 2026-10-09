# 24 — Design system: tokens and primitives

Status: draft
Depends on: [13](13-frontend-foundation.md)

## Goal

Establish the app's visual language as code before the Queue and History views are built: a full token set (light and dark), a self-hosted typeface, and a small library of accessible shared primitives, so items 14 and 15 build their views with it rather than being restyled afterwards.

## Already decided

- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): styled-components.
- [Frontend conventions](../docs/conventions/frontend.md): all design values live in `src/shared/theme/theme.ts` as tokens; **no literal colors or sizes in components**; shared components in `src/shared/`; named exports; colocated tests.
- Item 13 decided the direction: **calm, document-like**; light and dark. It built the shell, the router, the connection indicator and a native-`<dialog>` `ConfirmDialog`, but the theme has only four colors, one spacing unit, two radii and one font size, and the views are placeholders.
- [ADR 0026 — No rule for where test fixtures live](../docs/decisions/0026-no-rule-for-test-fixture-location.md): the self-hosted font is committed like the other assets, with its licence notice.
- User decisions (2026-10-09): this item comes **before** items 14 and 15; it is the **design system only** (the visual pass is item 25); the palette **keeps the current accent**; icons come from **`lucide-react`**; interactive primitives are built on **Radix** (Dialog, Tooltip, DropdownMenu, Switch, Slider); the typeface is **IBM Plex Sans**, self-hosted; tokens are a **TypeScript object only**; dark mode **follows the OS with a manual override**; **layout primitives** (`Page`, `Stack`, `Row`) are part of the system.

## Proposal (pending approval)

- **Dependencies** (each changes `pnpm-lock.yaml` and the flake's `fetchPnpmDeps` hash, verified by the user, ADR 0012):
  - `@radix-ui/react-dialog`, `-tooltip`, `-dropdown-menu`, `-switch`, `-slider` (behavior and accessibility only; all styling is ours, from tokens);
  - `lucide-react` (icons).
- **Tokens** (`src/shared/theme/theme.ts`), one set for light and one for dark:
  - colors by semantic role: `background`, `surface`, `surfaceRaised`, `text`, `textMuted`, `border`, `accent`, `accentText`, `focusRing`, `danger`, plus the five status groups below;
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
- **Decision to record**: the design system (tokens, Radix, `lucide-react`, IBM Plex Sans, the dark-mode override) is worth an ADR when this spec is approved.

## Open questions for the user

- Font weights and format: variable woff2 (one file, any weight) or three static files (400/500/600)? I propose the variable file if it is not larger; the workspace agent checks the sizes at the first checkpoint.
- Should the dark-mode control be a labelled button (`Theme: Auto`) or an icon-only `IconButton` with a tooltip?

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written:

- [ ] Every primitive renders its documented variants and states using token values only (no literal colors or sizes)
- [ ] `StatusBadge` renders the five status groups, each with its own token-based treatment, and shows the exact status text
- [ ] `ProgressBar` renders the transcription value, and both diarization stages (embedding, clustering)
- [ ] `Page`/`Stack`/`Row` apply the token spacing and max width
- [ ] Interactive primitives show a visible focus ring when focused by keyboard
- [ ] The dialog primitive traps focus and closes on Escape
- [ ] The dark-mode control cycles auto → light → dark, stores the choice, and overrides the OS preference
- [ ] The icon wrapper renders a token-sized, token-colored icon and is hidden from assistive technology when decorative
- [ ] Text and background contrast meets AA in both the light and the dark token set (measured)

## Acceptance criteria

- Tokens, the typeface and the primitives exist with the variants items 14 and 15 need; no component contains a literal color or size.
- Contrast meets AA in both themes; keyboard focus is visible on every interactive primitive.
- The font file's licence and origin are recorded next to the asset; `nix build .` ships it.
- `pnpm test` and `pnpm build` are green; the user's `nix build .` succeeds with the new dependencies.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-09: item drafted at the user's request (the app had no item for its visual design). Restructured by the user's answers: this item is the design system only and comes before items 14 and 15; Radix (Dialog, Tooltip, DropdownMenu, Switch, Slider) plus `lucide-react`; palette keeps the current accent; IBM Plex Sans self-hosted; tokens as a TypeScript object only; dark mode follows the OS with a manual override; layout primitives included; the visual pass over the views is item 25.
