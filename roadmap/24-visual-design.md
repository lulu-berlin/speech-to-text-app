# 24 — Design system: tokens and primitives

Status: draft
Depends on: [13](13-frontend-foundation.md) (done after merge)

## Goal

Establish the app's visual language as code before the Queue and History views are built: a full token set (light and dark) and a small library of accessible shared primitives, so items 14 and 15 build their views with it rather than being restyled afterwards.

## Already decided

- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): styled-components.
- [Frontend conventions](../docs/conventions/frontend.md): all design values live in `src/shared/theme/theme.ts` as tokens; **no literal colors or sizes in components**; shared components in `src/shared/`; named exports; colocated tests.
- Item 13 decided the direction: **calm, document-like**; light and dark following the OS. It built the shell, the router, the connection indicator and a native-`<dialog>` `ConfirmDialog`, but the theme has only four colors, one spacing unit, two radii and one font size.
- User decisions (2026-10-09): the design system comes **before** items 14 and 15; it is its **own item**, separate from the visual pass (item 25); primitives are built on a **headless accessibility library** (Radix) plus an **icon set**; the current accent (`#0057a8` light, `#7fb2e5` dark) is kept and the palette extended around it.

## Proposal (pending approval)

- **Dependencies** (each changes `pnpm-lock.yaml` and the flake's `fetchPnpmDeps` hash, verified by the user, ADR 0012):
  - `@radix-ui/react-*` for the primitives that need real accessibility work (dialog, tooltip, dropdown/menu as needed);
  - an icon set: **`lucide-react`** proposed (tree-shakeable, ISC licence, no runtime beyond React). Change it if you prefer another.
- **Tokens** (`src/shared/theme/theme.ts`), one set for light and one for dark:
  - colors by semantic role: `background`, `surface`, `surfaceRaised`, `text`, `textMuted`, `border`, `accent`, `accentText`, `focusRing`, `danger`, and one color per task status (`queued`, `running`, `paused`, `completed`, `failed`, `canceled`);
  - **a real type scale and spacing rhythm** (`fontSizes`, `fontWeights`, `lineHeights`, and a `spacing` scale) — today there is a single `fontSizes.title` and a single `spacing.unit`, which cannot express hierarchy;
  - `radii`, `shadows` (elevation), `motion` (durations and easings), and `breakpoints`.
  - `themeFor(prefersDark)` stays the entry point; `usePrefersDark` (item 13) is unchanged.
- **Primitives** (`src/shared/ui/`), each with variants and states, built on tokens only:
  - `Button` (primary / secondary / danger; **hover, focus-visible, active, disabled, loading**), `IconButton`, `Icon` (wraps the icon set with the token size/color);
  - `Card`/`Surface`, `StatusBadge` (one token-based style per `TaskStatus`), `ProgressBar` (transcription; and the two-stage diarization bar from item 11's contract), `Spinner`, `Skeleton` (loading placeholders), `EmptyState`, `List`/`ListRow` (the row/card styling the Queue and History lists are built from, with their hover and focus-visible states), `Tooltip`;
  - `ConfirmDialog` is reworked onto Radix's dialog for focus trapping, while keeping the item 13 behavior (only a confirm runs the action; Escape cancels).
- **Accessibility, part of the primitives, not a later pass**: AA contrast in both themes, visible focus rings on every interactive element, full keyboard operation, `prefers-reduced-motion` respected, correct roles and labels.
- **This item does not restyle the views.** It delivers the tokens and primitives and their tests; items 14 and 15 consume them, and item 25 does the final visual pass.
- **Decision to record**: the design system (tokens, Radix, icon set) is worth an ADR when this spec is approved.

## Open questions for the user

- Icon set: `lucide-react` as proposed, or another?
- Which Radix primitives are in scope here (dialog, tooltip, dropdown/menu, …)? Anything else items 14/15 will need (e.g. a toggle/switch for the queue pause, a slider for the audio player) should be listed now so it is built once.
- `StatusBadge`: a color per status, or fewer groups (active / done / problem)? Six colors is a lot of visual noise in a list.
- Should the tokens be a TypeScript object only, or also emit CSS custom properties (needed if anything outside styled-components must read them, e.g. the `<dialog>` backdrop or a future chart)?
- Dark mode: OS-follow only, or also a manual toggle (an extra control and a stored preference)?

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written:

- [ ] Every primitive renders its documented variants and states using token values only (no literal colors or sizes)
- [ ] `StatusBadge` renders a distinct, token-based style per task status
- [ ] `ProgressBar` renders the transcription value, and both diarization stages (embedding, clustering)
- [ ] Interactive primitives show a visible focus ring when focused by keyboard
- [ ] The dialog primitive traps focus and closes on Escape
- [ ] The icon wrapper renders a token-sized, token-colored icon and is hidden from assistive technology when decorative
- [ ] Text and background contrast meets AA in both the light and the dark token set (measured)

## Acceptance criteria

- Tokens and primitives exist with the variants items 14 and 15 need; no component contains a literal color or size.
- Contrast meets AA in both themes; keyboard focus is visible on every interactive primitive.
- `pnpm test` and `pnpm build` are green; the user's `nix build .` succeeds with the new dependencies.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-09: item drafted at the user's request (the app had no item for its visual design). Then restructured by the user's answers: this item is the design system only, it comes before items 14 and 15, and the primitives use Radix plus an icon set; the palette keeps the current accent. The visual pass over the views is item 25.
