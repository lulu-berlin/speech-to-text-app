# 59 — UI/UX polish: compact header, view hierarchy, task-row progress, history affordances

Status: done (roadmap item 59, approved 2026-10-18)
Depends on: —
Blocks: —

## Goal

Fix the usability and visual-hierarchy problems found in the 2026-10-17 UI/UX analysis of the shipped frontend (screenshots in `docs/images/screenshots/`, components under `frontend/src/`): the mascot header dominating every view, upload options sitting above the upload action, status/progress presentation that reads as stuck, danger-weighted controls on read-oriented screens, unaffordant history links, an ambiguous connection label, and flat dark-mode elevation. The desktop app (item 50, shipped) shows the same frontend, so the fixes apply there too.

## Evidence (from the analysis, citations verified 2026-10-17)

- The header illustration "fills the whole band — no top/bottom/left padding, so it touches the page edge all around" (`frontend/src/app/AppHeader.tsx:15–19`). At 120 px (`stenoHeader: '7.5rem'`, `frontend/src/shared/theme/theme.ts:202`) it takes about a sixth of a laptop viewport on every page, and its pink pastel backdrop clashes with the otherwise restrained gray/blue system (queue light screenshot). It scrolls to a 64 px compact version that stays (`headerScrollMachine`; `stenoCompact: '4rem'`, `theme.ts:203`; the scroll margin in `frontend/src/features/history/TranscriptTurns.tsx:59–65` compensates for it), so the transcript loses reading space to it. The animated mascot types while a task runs (`AppHeader.tsx:135`, `:159`, item 17).
- The queue view renders the Language/Speakers fields and the "Advanced" toggle (max speakers) above the drop zone (`frontend/src/features/queue/QueueView.tsx:396–450`, the drop zone at `:452`); the options apply to the batch and default from stored settings (`:256–267`), yet nothing groups them with the upload.
- A running task row shows the phase text plus an all-caps enum badge ("Transcribing RUNNING", `frontend/src/features/queue/TaskRow.tsx:25–29, 194–195`), then two thin unlabeled progress bars (`TaskRow.tsx:237–243`; labels are screen-reader only) with no elapsed time and no indeterminate state while chunking — visually "stuck at 0%".
- The transcript view puts a large filled red Delete button beside quiet TXT/SRT/VTT text links (`frontend/src/features/history/TaskTranscriptView.tsx:368–379`), making the destructive action the loudest control on a reading screen; the reading column stretches full-width with no measure cap.
- History rows: the filename is the only way into a completed task and is styled as plain text (`frontend/src/features/history/HistoryRow.tsx:22–27`); Delete and Retry sit as same-weight text actions.
- Queue rows carry four same-size icon buttons (grip, pause, cancel, menu) with no visible tooltips; cancel of a queued task needs no confirmation (`TaskRow.tsx:127–128`), so a stray click discards it.
- The connection indicator is the literal text "Live" (`AppHeader.tsx:122–126`, labels `Connecting` / `Live` / `Reconnecting`) with no visual state beyond the word.
- Dark theme keeps identical layout with near-black surfaces; cards' borders nearly vanish (dark queue screenshot) — a single `shadows.card` token serves both resting and dragged rows (`TaskRow.tsx:60`).

## Decided (consultation, 2026-10-17)

- **One item, all eight findings** (the user): one worktree, one test list; each user-facing change carries e2e coverage (ADR 0046).
- **Status line**: a running task shows one human status line ("Transcribing… 43%") instead of the phase-text + `RUNNING` badge pair; QUEUED and PAUSED keep their badge, where nothing else says the state.
- **Header (the user, from the analysis's finding 1)**: the brand shrinks to an icon in the header corner, at most 40 px tall, padded inside the band, with no pink backdrop; the compact (scrolled) header is text-only, with no illustration; the full illustration stays only in the queue's empty drop zone, where it already works (`QueueView.tsx:95`). The icon plays item 17's typing animation while a task runs and rests when none does — the user reversed this consultation's earlier "the typing animation leaves the header" answer on 2026-10-18, once the favicon's tab-size animation could not be confirmed. The compact header still has no mascot; this supersedes the earlier "a small typing mascot kept in both header forms" answer.
- **Typing animation in three places (the user)**: the 40 px header icon types while a task runs and rests when none does (2026-10-18); the favicon switches to the artwork carrying the typing animation while a task runs and to the static icon when none does — item 17's running signal in the browser tab (the Electron window has no favicon, so the desktop app shows the running state through the header icon and the task rows); and the empty queue's drop-zone illustration types, for charm (decorative: nothing runs while the queue is empty). Whether a browser animates an SVG favicon is up to the browser and unverified at tab size.
- **Connection indicator stays informational**: a dot + label with distinct connecting / connected / reconnecting states, no reconnect click.
- **Out of scope**: a compact density mode for long queues (deferred; its own item if wanted).

## Proposal

In the analysis's priority order:

1. **Compact header**: the brand as a corner icon (≤ 40 px, padded, no pastel backdrop, typing while a task runs) instead of the 120 px band; a text-only compact header on scroll; the scroll-margin compensation (`TranscriptTurns.tsx:59–65`) adjusted to the new, smaller compact header; the favicon and the empty drop zone keep their animations as additional signals.
2. **Queue layout**: drop zone first at full width; language/speakers in an "Upload options" disclosure below it; "Advanced" (max speakers) inside that disclosure.
3. **Task rows**: the running status line; one visible progress bar with an integrated percent label, phase shown by color, indeterminate while chunking, elapsed time; the diarization bar only while diarizing.
4. **Transcript view**: exports promoted to a proper control row; Delete demoted to a danger-zone/overflow action (confirm dialog stays); reading column capped at a comfortable measure; speaker rename stays reachable.
5. **History**: visible open affordance (link-styled filename and/or whole-card click with hover/focus state); Delete visually quieter than Retry.
6. **Row controls**: visible tooltips; danger affordance on hover for cancel; fixed control slots so allowed-command changes don't shift neighbors.
7. **Connection indicator**: dot + label per state, informational.
8. **Dark mode**: an elevation step for raised surfaces (e.g. `shadows.raised` / darker surface tokens).

## Test list (approved 2026-10-17; behaviors only)

- [x] (e2e) On every view the header brand is an icon in the corner whose rendered height is at most 40 px (measured), padded inside the band, and the band is no taller than the icon plus its padding; once the page scrolls the compact header is text-only, with no illustration; while following a playing transcript, the playing segment is never hidden under the header
- [x] (e2e) The page's favicon shows the typing animation while a task runs and the static icon when none runs (it switches as the queue starts and finishes)
- [x] (e2e) The empty queue's drop-zone illustration shows the typing animation
- [x] (e2e) The queue view offers the drop zone before any upload option in reading order; the language, speakers and max-speakers options are reachable from the same view and still apply to the next upload
- [x] (e2e) A running task shows its phase and percentage in one visible line and no `RUNNING` badge; the percentage advances while it runs; a chunking task shows an indeterminate indicator, not 0 %; a QUEUED and a PAUSED task still show their badge
- [x] (e2e) From a completed task's transcript view, each export format downloads the same bytes as before, Delete is reachable as a secondary/danger action and still requires its confirmation, and speaker rename still works
- [x] (e2e) A completed history row opens by a visibly indicated, keyboard-focusable affordance; Retry and Delete still work
- [x] Every icon-only row control has an accessible name and a visible tooltip; the controls keep their positions when the allowed commands change; cancel still asks for confirmation exactly where it does today (`docs/api/task-transitions.json`)
- [x] The connection indicator shows connecting, connected and reconnecting distinctly (label and visual state), driven by the connection machine's existing states
- [x] In the dark theme, raised surfaces (cards, the dragged row) are distinguishable from the page by the theme contrast check
- [x] In both the light and the dark theme, the header brand icon shows no pastel backdrop block against the header (screenshots compared)
- [x] (must not break) The theme contrast test, WCAG helpers, existing frontend suites and the e2e journey stay green; light and dark screenshots re-shot for `docs/images/screenshots/`
- [x] (discovered) In the phone layout, the compact header's measured height is no taller than the transcript's phone scroll margin, so a playing segment stays clear of the header there too
- [x] (discovered) In a history row, Delete reads visually quieter than Retry — muted at rest and danger on hover — while both still work
- [x] (e2e) A running task shows exactly one visible progress bar while chunking and transcribing; the diarization bar appears only during the diarizing phase
- [x] (e2e) A running task's status line shows its elapsed time, and the time advances while it runs
- [x] (e2e) The running status line's color differs between the chunking and transcribing phases (phase shown by color)
- [x] (e2e) From a failed task's status page, Delete is reachable behind the actions menu with its confirmation, and Retry still works
- [x] (e2e) The header brand icon plays the typing animation while a task runs and rests when none does

## Acceptance criteria

- The eight findings are fixed; every view's hierarchy reads action-first, with danger demoted on read-oriented screens.
- All test-list items ticked and green; light/dark screenshots updated; user approved the diff.

## Approval log

- 2026-10-17: filed from the root agent's UI/UX analysis of the shipped frontend (screenshots + component review).
- 2026-10-17: consultation — evidence citations re-verified (the queue fields corrected to `QueueView.tsx:396–450`; the stale "finish before item 50" blocker removed, item 50 having shipped). The user chose: all eight findings in one item; a running-task status line with badges kept for QUEUED/PAUSED; an icon-height header keeping a small typing mascot; an informational connection indicator; density mode deferred. Test list proposed as worded above.
- 2026-10-17: consultation, header follow-up (the user asked whether the mascot finding had been dropped — it had not; the write-up had three gaps): the scrolled header keeps the small typing mascot (the draft's "text-only compact-on-scroll" removed); the mascot gets padding inside the band, asserted by test item 1; the dark-theme illustration backdrop is in scope with its own test item. The drop-zone mascot line reworded as the existing state it is.
- 2026-10-17: the user restated the analysis's finding 1 (the mascot is too large and dominant; its pastel backdrop clashes; the compact form steals reading space) and its fix, superseding this consultation's earlier header answers: a static corner icon ≤ 40 px with no backdrop (the 40 px cap and its measured e2e assertion were chosen by the user just before), a text-only compact header, the full illustration only in the empty drop zone, the typing animation out of the header. Test items 1 and the backdrop item rewritten accordingly; the backdrop item now covers both themes.
- 2026-10-17: the typing animation lives in both places the analysis named (the user): the favicon while a task runs, carrying item 17's running signal in the browser tab, and the empty drop zone's illustration; two e2e test items added.
- 2026-10-17: spec and test list approved by the user as worded above. Status: spec-approved. Filed on `main` through the docs PR that archives item 53.
- 2026-10-18: rework consultation on the root agent's review (findings 1-7). The user approved: the rest of Proposal 3's task-row work — exactly one visible progress bar until diarizing, the diarization bar only while diarizing, an advancing elapsed time and a per-phase status-line color — with three new e2e test-list items; both (discovered) items (phone scroll margin; Delete quieter than Retry) are confirmed as approved work and are logged here; the failed/canceled status page demotes its Delete behind the actions menu, with a new e2e item; and three test corrections (restore the home-link artwork's aria-hidden assertion, derive the header band cap from the real tokens — 40 + 2×4 + 1 px = 49 — and rename the raised-shadow check to what it asserts). The favicon's tab animation is to be checked by the user in a real Chromium before any wording change. American spelling is used for "color" from here on.
- 2026-10-18: the user reversed the “typing animation leaves the header” answer after the favicon animation could not be confirmed in a real Chrome/Firefox tab (the tab icon is too small to see): the 40 px header icon now plays the typing animation while a task runs and rests when none does, with a new e2e test item. The favicon swap stays as a second signal (both remain; its tab-size animation stays unverified). Item 1's “static” wording and its no-typing assertions were amended accordingly.

- 2026-10-18: root-agent review of the rework (frontend suite, prettier, tsc, and the docs and
  e2e checks green; the e2e specs were reviewed, not run here) and the user's approval to land
  after item 41. The spec body was aligned with the header-typing reversal and the
  `QueueView.tsx` citation corrected. The branch was rebased onto item 41's main; item 59's 13
  e2e fixtures moved to `FIXTURE_CLIPS[32..44]` (item 41's race clip keeps index 31). Landed as
  the item's single squash commit.

- 2026-10-19: CI's first e2e run on the rebased branch found two test defects, fixed here:
  item 59's queued-row fixture was byte-identical to item 41's race clip (both 8-11s), so its
  upload was answered `409 duplicate-audio` and no row appeared — the clip is now 1-3s; and the
  history-row retry check waited for the retried undecodable task to appear in the queue, where
  it never lingers (it re-fails at chunking in about a second) — the check now proves the retry
  by its 200 response and the task's new finish time. Both specs re-run locally: 2 passed.
