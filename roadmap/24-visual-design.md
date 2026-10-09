# 24 — Visual design: a deliberate look

Status: draft
Depends on: [13](13-frontend-foundation.md), [14](14-frontend-queue-ui.md), [15](15-frontend-history-ui.md)

## Goal

Give the app a deliberate visual design instead of the default browser look: a real design system (tokens plus shared primitives) applied to the shell and to the Queue, History and transcript views, in light and dark. Today the theme has four colors, one spacing unit, two radii and one font size, and every view is a placeholder.

## Already decided

- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): styled-components.
- [Frontend conventions](../docs/conventions/frontend.md): all design values live in `src/shared/theme/theme.ts` as tokens; no literal colors or sizes in components; shared components in `src/shared/`; named exports; colocated tests.
- Item 13 decided the direction: **calm, document-like**; light and dark following the OS. It built the shell, the router, the connection indicator and a native-`<dialog>` confirmation dialog, but no visual design.
- Item 17 (branding: the stenographer animation) supplies the logo/mascot and must fit this design.
- Item 13's acceptance note: running against the real backend is item 21; this item is the visual pass on top of the real views.

## Proposal (pending approval)

- **Design tokens** (`src/shared/theme/theme.ts`), one set for light and one for dark:
  - a color palette with semantic roles: `background`, `surface`, `surfaceRaised`, `text`, `textMuted`, `border`, `accent`, `accentText`, `focusRing`, and a color per task status (QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELED);
  - a spacing scale, a type scale (sizes, weights, line heights), radii, elevation (shadows), and motion durations/easings;
  - a small set of breakpoints.
- **Shared primitives** (`src/shared/ui/`), hand-rolled with styled-components (no new runtime dependency unless approved): `Button` (primary / secondary / danger; hover, focus-visible, disabled, loading), `IconButton`, `Card`/`Surface`, `StatusBadge` (one style per task status), `ProgressBar` (transcription, and the two-stage diarization bar), `EmptyState`, `Spinner`, `List`/`ListRow`, `Tooltip` (if needed). The existing `ConfirmDialog` is restyled to match.
- **Apply the design** to:
  - the app shell: header, navigation, connection indicator;
  - the Queue view (item 14): upload area, task rows, controls, progress bars, empty state;
  - the History view (item 15): task list, transcript turns, downloads, audio player, playback highlight;
  - error and not-found states.
- **Accessibility**: text and interactive elements meet WCAG AA contrast in both themes; visible focus rings; full keyboard operation; `prefers-reduced-motion` respected; semantic landmarks and labels.
- **Responsive**: usable from a narrow phone width to a wide desktop (breakpoints from the tokens).
- **Verification**: the look is checked in a real browser (Chromium, as item 13 did with CDP), light and dark, at phone and desktop widths; the primitives get RTL tests for behavior and for using tokens only (no literal colors or sizes); the existing view tests stay green.

## Open questions for the user

- **Placement.** Items 14 and 15 build the Queue and History views functionally and are still `draft`. Should this item come **before** them, so the design system exists and they are built with it from the start, or **after** them, as a retrofit polish pass? And should item 16 (the Nix package) wait for this item so the deliverable ships polished?
- **Scope.** One item (design system plus the pass over all views), or two: the design system and primitives, then applying them?
- **Palette and brand.** Keep the current accent (`#0057a8` light, `#7fb2e5` dark), or a new palette? Are there apps or sites whose look you like, to aim at?
- **Component library.** Hand-rolled primitives with styled-components (no dependency, more code to own), or a headless accessibility library (e.g. Radix) on top of the tokens?
- **Icons.** A small hand-drawn SVG set in the repo (no dependency), or an icon library?
- **Motion.** Minimal (hover/focus transitions only), or richer transitions (view changes, progress)?
- **Responsive targets.** Phone and desktop, or desktop only?
- **Item 17 coordination.** Does the stenographer animation's design follow this item, or does this item wait for the animation so they match?

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written:

- [ ] Each primitive renders its documented variants using token values only (no literal colors or sizes)
- [ ] A `StatusBadge` shows a distinct, token-based style per task status
- [ ] The progress bars render the transcription value and both diarization stages
- [ ] Interactive elements show a visible focus ring when focused by keyboard
- [ ] Text and background contrast meets AA in both the light and the dark token set (measured)
- [ ] The layout is usable at a phone width and a desktop width (no horizontal overflow, controls reachable)
- [ ] With reduced motion requested, no non-essential animation runs
- [ ] The existing view and machine tests stay green

## Acceptance criteria

- The user approves the look in a real browser, light and dark, at phone and desktop widths.
- No component contains a literal color or size; every value comes from a token.
- Contrast meets AA in both themes; keyboard focus is visible everywhere.
- `pnpm test` and `pnpm build` are green; the user's `nix build .` succeeds if dependencies changed.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-09: item drafted at the user's request: the app has no item for its visual design and currently looks like the default browser (placeholder views, a four-color theme).
