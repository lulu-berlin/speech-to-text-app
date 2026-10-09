# 25 — Visual polish pass

Status: spec-approved
Depends on: [24](24-visual-design.md), [14](14-frontend-queue-ui.md), [15](15-frontend-history-ui.md)

## Goal

Make the finished app look deliberate: apply item 24's tokens and primitives to the app shell and to the Queue, History and transcript views, and check the result in a real browser in light and dark, at phone and desktop widths.

## Already decided

- [Frontend conventions](../docs/conventions/frontend.md): design values only as tokens; shared components in `src/shared/`; colocated tests.
- [ADR 0033 — Design system](../docs/decisions/0033-design-system-tokens-radix-plex.md): the tokens, the typeface and the primitives this item applies.
- Item 24 delivers the tokens and primitives; items 14 and 15 build the views with them. This item is the pass that makes them look finished and consistent.
- Item 13 decided the direction: calm, document-like, light and dark.
- Item 17 (the stenographer animation) supplies the logo/mascot; it depends on item 24 so it uses the same palette.

## Proposal (approved 2026-10-09)

- **Reference (decided 2026-10-09): document/editor-like** (Notion, Bear, iA Writer): quiet surfaces, one accent, generous whitespace, text-first, minimal chrome.
- Go view by view (shell, Queue, History, transcript, error/not-found) and apply the design system:
  - consistent spacing rhythm and type hierarchy;
  - **queue rows, comfortable density** (~48–56 px): filename, status badge, the two progress bars, and the row controls; hover and focus-visible states;
  - **progress bars show a value**: the transcription bar a percentage, the diarization bar its stage label (embedding, clustering), since its percentage restarts per stage;
  - **the phase (CHUNKING / TRANSCRIBING / DIARIZING) is a text label** beside the progress bars, not a separate step indicator or icon;
  - upload area and drag-over state;
  - **the transcript is a centred reading column** with a maximum measure (~70 characters): a header block with the task's name, date and duration, then the audio player and the download buttons, then the turns; comfortable line height and turn spacing, clear speaker labels and timestamps, and the playing-segment highlight;
  - **History is a list of cards** (filename, status, date, duration; FAILED cards show the error), which are touch-friendly and read well on a phone;
  - **empty, loading and error states for every view**: an empty state is one line of guidance plus the primary action (e.g. "No tasks yet — drop audio files here, or click to choose them"); loading uses the skeleton primitives; an error is informative rather than raw.
- **Responsive**: usable from a narrow phone width to a wide desktop; no horizontal overflow; controls reachable; the queue's rows and the history's cards stack on a narrow screen.
- **Motion**: restrained transitions (hover/focus, view change), nothing that fights `prefers-reduced-motion`.
- **Verification**: a real-browser check (Chromium, as item 13 did with CDP) in light and dark at phone and desktop widths, plus the existing view tests staying green.
- No new dependencies here; anything missing belongs to item 24.

## Open questions (answered 2026-10-09)

- Reference? → **Document/editor-like** (Notion, Bear, iA Writer).
- Empty states? → **One line of guidance plus the primary action.**
- List density? → **Comfortable** (~48–56 px rows).
- Phase display? → **A text label** beside the progress bars.
- Transcript layout? → **A centred reading column** with a maximum measure.
- History list? → **Cards.**
- Progress bars? → **Bar plus a percentage / stage label.**

## Remaining open questions

None.

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-09 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.

- [ ] The shell and each view render on the token background/text and use the primitives, with no literal colors or sizes
- [ ] The layout is usable at a phone width and a desktop width (no horizontal overflow, controls reachable)
- [ ] Queue rows show the status badge, both progress bars and the phase label, and stack on a narrow screen
- [ ] History renders one card per finished task, and a FAILED card shows its error
- [ ] The transcript is a centred column with a bounded measure, its header block, the player and downloads, and the turns with speaker labels and timestamps
- [ ] Every view has a distinct empty, loading and error state; the Queue's empty state offers the upload action
- [ ] The transcription bar shows a percentage and the diarization bar its stage label
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
- 2026-10-09: consultation answers approved: reference is document/editor-like; empty states carry one line of guidance plus the primary action; comfortable queue rows; the phase is a text label; the transcript is a centred reading column; History is cards; progress bars show a percentage / stage label. Test list not yet approved.
- 2026-10-09: test list (10 items) approved by the user; status `spec-approved`.
