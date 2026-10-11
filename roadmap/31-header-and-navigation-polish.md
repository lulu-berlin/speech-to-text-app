# 31 — Header and navigation polish

Status: in-review
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

## Open questions (answered 2026-10-11)

- While a placeholder shows the mascot, what does the header show? → **A small static logo** (the artwork's still frame) that stays the home link; the animated mascot appears only once, in the placeholder.
- Header height for a sticky header? → **Shrink on scroll**: full size (7.5rem) at the top of the page, compact once the page scrolls, and **the shrinking is animated**. Reduced motion is respected as everywhere: the shrink transition runs under `prefers-reduced-motion: no-preference` and the size switches instantly otherwise. Being compact is not a reason to stop the mascot's own animation — the small header mascot types and rests exactly like the large one (user clarification, 2026-10-11).
- Navigation style? → **Background pill**: a soft surface pill behind a link on hover, filled for the active link, with a token transition.
- Order with item 21? → **21 first**; this item depends on 21's `e2e/` suite (ADR 0046) and captures the new shell in its own e2e test.

## Remaining open questions

None.

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-11 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.

- [x] The header stays at the top while the page scrolls (sticky, token background and bottom border) on Queue, History and the transcript
- [x] At the top of a page the header shows the full-size mascot; once the page scrolls it shrinks to the compact size with an animated transition (instant under reduced motion), and grows back when scrolled to the top; the compact mascot keeps its typing/resting animation
- [x] Navigation links show a background pill on hover and a filled pill on the active link, from tokens, with a token transition; the keyboard focus ring stays visible
- [x] The header mascot (or the static logo) is a link to `/` with an accessible name; clicking it or pressing Enter lands on `/queue`
- [x] While an empty, loading or connecting placeholder shows the mascot, the header shows the small static logo instead; otherwise the header shows the animated mascot — never two animated mascots on screen
- [x] The transcript's follow mode keeps the playing segment visible below the sticky header
- [x] (e2e, ADR 0046) Against the real backend: scrolling a long transcript keeps the header in view and shrinks it; the nav pill follows the route; the mascot link returns to the queue; on the empty queue the header shows the static logo and the placeholder the animated mascot — with screenshots (light/dark, phone/desktop) of the new shell
- [x] (must not break) Existing view tests, the design-value convention check and the reduced-motion scan stay green

## Acceptance criteria

- The four requested behaviors work in the browser, light and dark, at phone and desktop widths.
- No literal colors or sizes; reduced motion respected.
- The e2e test passes on the user's machine (ADR 0046).
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-11: drafted by the root agent from the user's request (sticky header, nicer navigation with a hover transition, mascot links to `/`, mascot never in two places at once).
- 2026-10-11: consultation answers (static logo while a placeholder shows the mascot; shrink on scroll, animated; background-pill navigation; item 21 first) and the proposed test list incl. its e2e test (ADR 0046). Remaining: test list approval.
- 2026-10-11: reduced motion clarified by the user: respected as everywhere (the shrink animates only under `no-preference`); shrinking is not a reason to reduce the mascot's own motion.
- 2026-10-11: test list (8 items, incl. the e2e test) approved by the user; status `spec-approved`. Starts when item 21 delivers the `e2e/` suite.
