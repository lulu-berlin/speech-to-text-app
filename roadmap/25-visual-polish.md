# 25 — Visual polish pass

Status: draft
Depends on: [24](24-visual-design.md), [14](14-frontend-queue-ui.md), [15](15-frontend-history-ui.md)

## Goal

Make the finished app look deliberate: apply item 24's tokens and primitives to the app shell and to the Queue, History and transcript views, and check the result in a real browser in light and dark, at phone and desktop widths.

## Already decided

- [Frontend conventions](../docs/conventions/frontend.md): design values only as tokens; shared components in `src/shared/`; colocated tests.
- Item 24 delivers the tokens and primitives; items 14 and 15 build the views with them. This item is the pass that makes them look finished and consistent.
- Item 13 decided the direction: calm, document-like, light and dark following the OS.
- Item 17 (the stenographer animation) supplies the logo/mascot; it depends on item 24 so it uses the same palette.

## Proposal (pending approval)

- Go view by view (shell, Queue, History, transcript, error/not-found) and apply the design system:
  - consistent spacing rhythm and type hierarchy;
  - task rows and status badges that read at a glance, with hover and focus-visible states;
  - the two progress bars (transcription, diarization) with clear stage labels;
  - upload area and drag-over state;
  - the transcript **reading surface**: a comfortable measure (max line length) and line height, turn spacing, clear speaker labels and timestamps, and the playing-segment highlight;
  - the audio player, download buttons and controls;
  - **empty, loading and error states for every view** (nothing to show yet; data on the way; a load or command failed) — informative rather than raw or blank.
- **Responsive**: usable from a narrow phone width to a wide desktop; no horizontal overflow; controls reachable.
- **Motion**: restrained transitions (hover/focus, view change), nothing that fights `prefers-reduced-motion`.
- **Verification**: a real-browser check (Chromium, as item 13 did with CDP) in light and dark at phone and desktop widths, plus the existing view tests staying green.
- No new dependencies here; anything missing belongs to item 24.

## Open questions for the user

- Are there apps or sites whose look you want to aim at? (A reference helps more than adjectives.)
- Should the empty states (no tasks yet, no history yet) carry any guidance text, or stay minimal?
- Any preference on list density (comfortable rows vs compact, more tasks visible)?
- Should the app show a per-task "phase" as text, an icon, or a labeled step indicator?

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written:

- [ ] The shell and each view render on the token background/text and use the primitives, with no literal colors or sizes
- [ ] The layout is usable at a phone width and a desktop width (no horizontal overflow, controls reachable)
- [ ] Status badges and progress bars read correctly for every task status and both diarization stages
- [ ] Every view has a distinct empty, loading and error state
- [ ] The transcript reading surface keeps a comfortable line length and turn spacing at desktop width
- [ ] With reduced motion requested, no non-essential animation runs
- [ ] Keyboard focus is visible in every view
- [ ] The existing view and machine tests stay green

## Acceptance criteria

- The user approves the look in a real browser, light and dark, at phone and desktop widths.
- Every view is consistent: the same spacing rhythm, type hierarchy and component usage throughout.
- Contrast meets AA in both themes; keyboard focus visible everywhere.
- `pnpm test` and `pnpm build` are green.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-09: item drafted from the user's answers: the visual pass is separate from the design system (item 24), and comes after items 14 and 15 have built the views.
