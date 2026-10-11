# 31 — Header and navigation polish

Status: draft
Depends on: [17](../docs/specs/branding-stenographer-animation.md) (done), [25](../docs/specs/visual-polish.md) (done), [21](21-frontend-backend-end-to-end.md) (the `e2e/` suite, ADR 0046)

## Goal

Make the app shell feel finished: the header stays in view, the navigation looks deliberate, and the mascot appears exactly once on screen.

## Already decided

- [ADR 0046 — Every app feature has an end-to-end test](../docs/decisions/0046-every-feature-has-an-e2e-test.md): this item's test list includes at least one Playwright e2e test in `e2e/`, run by the user against the real backend.
- [ADR 0033 — Design system](../docs/decisions/0033-design-system-tokens-radix-plex.md) and the [frontend conventions](../docs/conventions/frontend.md): tokens only, no literal colors or sizes; `prefers-reduced-motion` respected.
- [ADR 0038 — UI state transitions are machines](../docs/decisions/0038-ui-state-transitions-are-machines.md).
- Item 17 placed the mascot in the header (`AppHeader`, 7.5rem, typing while a task runs) and as the loading/empty indicator (`EmptyState`, `LoadingState`, the Queue's empty drop zone, `ConnectingRegion`). Today both can be on screen at once — for example the header mascot above the Queue's empty-state mascot.
- User requests (2026-10-11):
  - the header is sticky: it does not scroll away;
  - the navigation bar looks nicer, e.g. with a hover transition;
  - clicking the mascot goes to `/` (which redirects to `/queue`);
  - the mascot shows either as a placeholder (empty/loading state) or in the header, never both at the same time.

## Proposal

To be settled in the consultation. Starting points:

- **Sticky header**: `position: sticky; top: 0` on the header, a background and a subtle bottom border/shadow token so content scrolls under it; the Queue/History/transcript layouts keep their reading column. The transcript's follow mode (item 15) must keep the active segment visible below the header (scroll offset).
- **Navigation**: Queue / History as styled links with a token hover/focus transition (background or underline), a clear active state; keyboard focus ring as everywhere (item 24).
- **Mascot as a home link**: the header mascot is wrapped in a link to `/` with an accessible name (e.g. "Speech-to-Text home"); the mascot itself stays decorative (`aria-hidden`), the link carries the name.
- **One mascot at a time**: when a page shows a placeholder mascot (empty, loading, connecting), the header shows no mascot (or a static/small brand mark — open question); otherwise the header shows it. The decision lives in one place (a shell-level state, ADR 0038), not in each view.

## Open questions

- With a placeholder on screen, does the header hide the mascot entirely, or show a small static mark in its place (keeping the home link)?
- Header height: the header mascot is 7.5rem today (item 17's legibility decision); is that too tall for a sticky header — shrink on scroll, or a smaller fixed size?
- Navigation transition style: underline slide, background pill, or color fade?
- Order with item 21: item 21 captures the visual-acceptance screenshots. Should item 21 depend on this item (screenshots of the final shell), or does this item re-capture after 21?

## Test list (written with the user; Canon TDD step 1)

To be written with the user. Expected scope:

- The header stays at the top while the page scrolls (sticky positioning and a token background), on Queue, History and the transcript.
- Navigation links show hover/focus/active states from tokens; the transition is skipped with reduced motion.
- Clicking the header mascot (or pressing Enter on its link) navigates to `/` → `/queue`; the link has an accessible name.
- When an empty, loading or connecting placeholder shows the mascot, the header does not show it (per the decided rule); otherwise the header does.
- The transcript's follow mode keeps the active segment visible below the sticky header.
- Existing view tests stay green; the design-value convention check stays green.

## Acceptance criteria

- The four requested behaviors work in the browser, light and dark, at phone and desktop widths.
- No literal colors or sizes; reduced motion respected.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-11: drafted by the root agent from the user's request (sticky header, nicer navigation with a hover transition, mascot links to `/`, mascot never in two places at once).
