# 25 — Visual polish pass

Status: spec-approved
Depends on: [24](../docs/specs/design-system.md) (done), [14](../docs/specs/frontend-queue-ui.md) (done), [15](../docs/specs/frontend-history-ui.md) (done)

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
  - upload area and drag-over state (the functional drop exists since item 14; the drag-over
    visual and the drag-reorder lift — dnd-kit without a DragOverlay leaves the dragged row in
    place — are this item's);
  - **the transcript is a centred reading column** with a maximum measure (~70 characters): a header block with the task's name, date and duration, then the audio player and the download buttons, then the turns; comfortable line height and turn spacing, clear speaker labels and timestamps, and the playing-segment highlight;
    - The header block (task name, finish date, duration) is this item's work — item 15's heading is only "Transcript" (decided 2026-10-10).
    - **The player is the themed one, not the native controls** (decided 2026-10-10): item 15's native `<audio controls>` is replaced by a play/pause `IconButton`, item 24's `Slider` as the seek bar and current/total time labels, styled from the tokens — the Slider finally gets its intended consumer (it was dead since item 15 accepted native controls). The playback wiring item 15 built (`playbackMachine`, the audio element ref, the highlight) stays.
    - The history rows already show the date and duration (added by item 15, merged 2026-10-10); this item restyles them, it does not add them.
  - **History is a list of cards** (filename, status, date, duration; FAILED cards show the error), which are touch-friendly and read well on a phone; the date and duration land with item 15 (2026-10-10), this item makes the cards look finished;
  - **empty, loading and error states for every view**: an empty state is one line of guidance plus the primary action (e.g. "No tasks yet — drop audio files here, or click to choose them"); loading shows the stenographer (item 17, which replaces the spinners and skeletons), including the transcript's turns-fetch "Loading…" text and the History/Queue empty lists, which today render nothing (decided 2026-10-10); an error is informative rather than raw, with one consistent error style across the views.
- **Responsive**: usable from a narrow phone width to a wide desktop; no horizontal overflow; controls reachable; the queue's rows and the history's cards stack on a narrow screen.
- **Motion**: restrained transitions (hover/focus, view change), nothing that fights `prefers-reduced-motion`.
- **Verification**: a real-browser check (Chromium, as item 13 did with CDP) in light and dark at phone and desktop widths, plus the existing view tests staying green.
- No new dependencies here; anything missing belongs to item 24.

## Open questions (answered 2026-10-09, seek bar 2026-10-10)

- Reference? → **Document/editor-like** (Notion, Bear, iA Writer).
- Empty states? → **One line of guidance plus the primary action.**
- List density? → **Comfortable** (~48–56 px rows).
- Phase display? → **A text label** beside the progress bars (already built by item 14).
- Transcript layout? → **A centred reading column** with a maximum measure.
- History list? → **Cards.**
- Progress bars? → **Bar plus a percentage / stage label.**
- Seek bar? → **The themed player (decided 2026-10-10)**: play/pause `IconButton` + item 24's `Slider` + time labels replace the native `<audio controls>` from item 15; the Slider is consumed again.

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-09 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.

- [ ] The shell and each view render on the token background/text and use the primitives, with no literal colors or sizes
- [ ] The layout is usable at a phone width and a desktop width (no horizontal overflow, controls reachable)
- [ ] Queue rows show the status badge, both progress bars and the phase label, and stack on a narrow screen
- [ ] History renders one card per finished task, and a FAILED card shows its error
- [ ] The transcript is a centred column with a bounded measure, its header block, the player and downloads, and the turns with speaker labels and timestamps
- [ ] (added 2026-10-10) The transcript's header block shows the task's name, its finish date and its duration (item 15 only titled it "Transcript")
- [ ] (added 2026-10-10) The transcript player is the themed one: play/pause via an `IconButton`, the seek bar is item 24's `Slider`, and current/total time labels; the playing-segment highlight from item 15 still follows the player without native controls
- [ ] Every view has a distinct empty, loading and error state; loading shows the stenographer
- [ ] Screenshots at phone and desktop widths, light and dark, are captured for item 21's approval; the Queue's empty state offers the upload action
- [ ] The transcription bar shows a percentage and the diarization bar its stage label
- [ ] With reduced motion requested, no non-essential animation runs
- [ ] Keyboard focus is visible in every view
- [ ] The existing view and machine tests stay green

## Acceptance criteria

- The look is approved from the screenshots item 21 captures (light and dark, phone and desktop); the visual acceptance itself belongs to item 21 (2026-10-09).
- Every view is consistent: the same spacing rhythm, type hierarchy and component usage throughout.
- Contrast meets AA in both themes; keyboard focus visible everywhere.
- `pnpm test` and `pnpm build` are green.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-09: item drafted from the user's answers: the visual pass is separate from the design system (item 24), and comes after items 14 and 15 have built the views.
- 2026-10-09: consultation answers approved: reference is document/editor-like; empty states carry one line of guidance plus the primary action; comfortable queue rows; the phase is a text label; the transcript is a centred reading column; History is cards; progress bars show a percentage / stage label. Test list not yet approved.
- 2026-10-09: test list (10 items) approved by the user; status `spec-approved`.
- 2026-10-09: item 17 consultation: loading states show the stenographer (item 17 replaces item 24's `Spinner` and `Skeleton`); progress bars are unchanged. Recorded here so the pass does not build skeleton placeholders.
- 2026-10-09: item 21 consultation: the browser acceptance (the user approving the look) moves to item 21, which runs the automated end-to-end suite and captures the screenshots; this item supplies the tests it can run without a browser.
- 2026-10-10: re-consultation after items 14, 15 and 17 merged: the delta was reviewed with the user (the phase label is already built by 14; History cards and date/duration exist; Queue/History empty states, the transcripts turns-fetch loading, the bar labels and the centred column are still this item's). Decisions: **(1)** the transcript player is the themed one — play/pause `IconButton`, item 24's `Slider` seek bar and time labels replace the native `<audio controls>` from item 15 (the Slider regains its consumer; item 15's playback wiring stays); **(2)** the transcript header block (task name, finish date, duration) is this item's work; loading states use the stenographer pattern everywhere (including the turns-fetch "Loading…" and the empty Queue/History lists), error states get one consistent style. Test list gains the header-block item and the themed-player item; status stays `spec-approved`.
