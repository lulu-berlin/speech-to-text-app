# 25 — Visual polish pass

Status: in-progress
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
- [x] Queue rows show the status badge, both progress bars and the phase label, and stack on a narrow screen (cycle 2, 2026-10-10; full column stack below `breakpoints.sm`)
- [ ] History renders one card per finished task, and a FAILED card shows its error
- [ ] The transcript is a centred column with a bounded measure, its header block, the player and downloads, and the turns with speaker labels and timestamps
- [ ] (added 2026-10-10) The transcript's header block shows the task's name, its finish date and its duration (item 15 only titled it "Transcript")
- [ ] (added 2026-10-10) The transcript player is the themed one: play/pause via an `IconButton`, the seek bar is item 24's `Slider`, and current/total time labels; the playing-segment highlight from item 15 still follows the player without native controls
- [ ] Every view has a distinct empty, loading and error state; loading shows the stenographer — split by state (user, 2026-10-10), one cycle each:
  - [x] (a) Queue: once live with no tasks, the drop zone becomes the empty state (mascot + "No tasks yet — drop audio files here, or click to choose them", the zone itself is the action); before the snapshot the shell's connecting region is the loading state; a task returns the zone to its compact form (cycle 3, 2026-10-10)
  - [x] (b) History: an empty state when no task has finished, and a distinct "no matches" state when search/filter exclude every task (cycle 4, 2026-10-10)
  - [ ] (c) Transcript: loading the task and loading its turns use the mascot + label pattern
  - [ ] (d) Transcript: a failed turns fetch shows the error callout
  - [ ] (e) The not-found view and "task not found" use the same error callout
- [ ] Screenshots at phone and desktop widths, light and dark, are captured for item 21's approval; the Queue's empty state offers the upload action
- [x] The transcription bar shows a percentage and the diarization bar its stage label (cycle 1, 2026-10-10; refactor approved: the diarization aria text now matches the visible within-stage label)
- [ ] With reduced motion requested, no non-essential animation runs
- [ ] Keyboard focus is visible in every view
- [ ] The existing view and machine tests stay green
- [ ] (discovered, confirmed 2026-10-10) The upload row's progress bar has a visible width at desktop and phone widths (it sits beside the filename as a flex item with no width of its own, so it collapses to its border)
- [ ] (discovered, confirmed 2026-10-10) The drop zone shows a distinct drag-over state while files are dragged over it (in the Proposal, missing from the list)
- [ ] (discovered, confirmed 2026-10-10) A queue row being dragged to reorder looks lifted (dnd-kit `DragOverlay` or an equivalent; today the dragged row stays in place)

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
- 2026-10-10 (workspace, cycle 1): the user confirmed the cycle order (bar labels first) and chose the bar-label looks: the transcription bar shows its percentage as small muted text right of the track; the diarization bar shows its active stage label with the within-stage percentage ("Embedding 43%"). `Test: shows the transcription percentage and the diarization stage label as visible values` (new `it` in `ProgressBar.test.tsx`; the labels are a primitives addition in item 24's scope, no new tokens or packages). Red: `Unable to find an accessible element with the text: 67%` — the bars rendered no visible value. Note: the worktree's `frontend/node_modules` was absent and the main tree's copy incomplete (`eventsource` missing), so a frozen `pnpm install` ran inside the worktree (gitignored).
- 2026-10-10 (workspace, cycle 1, refactor): approved by the user — the diarization bar's stage lookup is computed once, and its `aria-valuetext` now matches the visible within-stage label ("Clustering 40%" instead of the merged "Clustering 70%"); the item-24 assertion in `ProgressBar.test.tsx` and the item-14 assertion in `QueueView.test.tsx` were updated with it.
- 2026-10-10 (workspace, cycle 2): the user chose the narrow-row look: below `breakpoints.sm` (40rem) the row's top line becomes a column — the filename on its own line, the phase, status and controls on the line below, left-aligned (over wrapping the controls block or shrinking the icon buttons). `Test: shows the status badge, the phase label and both progress bars on a row, and stacks the row on a narrow screen` (new `it` in `QueueView.test.tsx`; jsdom cannot evaluate media queries, so the emitted `@media (max-width: 40rem)` rule matching the row's top line is asserted). Red: `AssertionError: expected [] to include 'column'` — badge, phase label, both bars and the visible "42%" already passed; no narrow-screen rule existed. Green: `RowContent` (`rowParts.ts`, shared by the upload and task rows) gains the media query with `flex-direction: column; align-items: flex-start`. Full suite 31 files / 122 tests, prettier and `tsc --noEmit` clean.
- 2026-10-10 (workspace, cycle 3 = 8a): the user split item 8 into five sub-items (a–e, one cycle each) and chose two looks: the Queue's empty state is the drop zone itself (mascot + one line of guidance; the zone stays the action), and every error state is an inline callout (a `Surface` with a danger left border, icon, message and action). `Test: turns the drop zone into the empty state once live with no tasks, and back to its compact form when a task arrives` (new `it` in `QueueView.test.tsx`; `controllableEventStream` takes an optional snapshot so the stream can open empty). Red: `Unable to find role="button" and name "No tasks yet — drop audio files here, or click to choose them"`. Green: `QueueView` reads whether the connection is live; empty = live, no pending task, no upload row; the zone then renders the mascot (`StenographerAnimation size="empty"`) above the guidance in a centred column (`EmptyZoneContent`), otherwise the compact text. Before the snapshot the view does not claim emptiness (the shell's connecting region is the loading state). Full suite 31 files / 123 tests, prettier and `tsc --noEmit` clean. This also covers item 9's "the Queue's empty state offers the upload action"; item 9 stays open for the screenshot part (item 21's acceptance).
- 2026-10-10 (workspace, cycle 3, checkpoint): no refactoring (user); the drag-over and drag-lift scenarios were added to the test list. Cycle 4 = 8b: the user chose the History empty state as `EmptyState` (mascot, "No transcripts yet", one guidance line with an inline "go to the queue" link) with the search and filter hidden, and the no-matches state as a compact muted line plus a "Clear filters" text button (no mascot). `Test: shows the empty state when nothing has finished, and a distinct no-matches line when search and filter exclude every task` (new `it` in `HistoryView.test.tsx`). Red: `Unable to find an element with the text: No transcripts yet`. Green: `HistoryView` splits the finished tasks from the filtered ones; with none finished it renders only the heading plus, once live, the `EmptyState` (a `QueueLink` in accent with the shared focus ring); with finished tasks but no match, `NoMatches` (muted line, `ClearFilters` resets search and status). Full suite 31 files / 124 tests, prettier and `tsc --noEmit` clean.
- 2026-10-10 (workspace, cycle 4, refactors): approved by the user, each its own commit — (1) a shared `TextButton` (`src/shared/ui/TextButton.tsx`: accent text, no fill, shared focus ring, `type="button"` by default) replaces the Queue's `AdvancedToggle` and History's `ClearFilters`; (2) `HistoryView` has one render path (heading, then the list with its controls, the empty state once live, or nothing). Deferred to the keyboard-focus cycle: one link style across the views (`QueueLink`, the transcript's `DownloadLink`, the queue notices' plain `Link`s). Full suite 31 files / 124 tests after each.
- 2026-10-10 (workspace, cycle 5 = 8c): the user chose that, while a completed task's turns load, the known parts (title, downloads, player, Delete) render at once and the mascot + "Loading transcript…" stand in place of the turns. `Test: renders nothing of its own before the snapshot, then the known parts with the mascot and a label in place of the turns until they arrive` (new `it` in `TaskTranscriptView.test.tsx`; the turns answer is held by the test until released). Red: `expected 'TranscriptLoading…' to be ''` — before the snapshot the view rendered its own "Transcript" heading and a bare "Loading…" under the shell's connecting region. Green: a shared `LoadingState` (`src/shared/ui/LoadingState.tsx`: mascot at the empty-state size above a muted label, centred — the `ConnectingRegion` look); `TaskTranscriptView` returns nothing while the task is unknown and the connection is not live, and renders `LoadingState` until the turns arrive. Must-not-break: item 13's routing test in `App.test.tsx` identified the transcript route by that pre-snapshot "Transcript" heading; it now waits for the snapshot and asserts the page of task 7 (`memo.wav`, still queued), an equally strong route check. Full suite 31 files / 125 tests, prettier and `tsc --noEmit` clean.
