# 52 — Edit the transcription: correct a segment's text, with isolated segment playback

Status: done (roadmap item 52, approved 2026-10-23)
Depends on: [12](http-api-and-sse.md) (done; the `/turns` contract), [22](speaker-rename.md) (done; the COMPLETED-only in-place-edit precedent, SSE events, contract-first examples)
Blocks: —

## Goal

Let the user fix what the transcriber got wrong: any segment of a finished transcription can be
selected and its text edited, and the edited text shows everywhere the segment shows — the
transcript view, the downloads, the speakers-off view. To make this the copy-edit workflow it is,
a segment is easy to play in isolation (its own window only) while the user edits it.

## Already decided

- Segments are stored rows (`segments`, V1; `SegmentsTable.id` autoincrement), one per whisper
  segment; nothing in the app rewrites `segment.text` today.
- Item 22's edit precedent: only COMPLETED tasks are editable (409 `not-completed` otherwise),
  unknown ids answer 404, writes are in-place row updates, an SSE event is emitted after the
  write, new endpoints extend item 12's contract with `docs/api/` examples, and the UI is driven
  by machines (ADR 0038).
- The transcript view (item 15/25/31): every segment is already a click-to-seek button
  (`TranscriptTurns` `SegmentButton` → `seekTo(startMs)`, which plays from the segment's start to
  the end of the clip). There is no stop-at-end: "play the segment in isolation" does not exist
  yet and is small new behavior on the existing `playbackMachine`/audio element.
- `/turns`'s `TurnSegmentJson` carries `startMs`, `endMs`, `text` — but **no id**; addressing an
  edit by segment needs the id on the wire (contract change plus `turns*.json` examples).
- Downloads (txt/srt/vtt) and `/turns` render the stored segment text (item 10's renderers, ADR
  0030 trims at read time) — an edit flows into them with no renderer change.
- COMPLETED tasks are never retried (item 11) — an edit cannot be lost to a rerun; FAILED and
  CANCELED tasks are retried and their segments replaced, so they are not editable (same rule item
  22 uses).

## Decided (consultation, 2026-10-14)

The user's design, confirmed: an edit control per segment opens the segment's text for editing
inline in the transcript; beside it a play control that plays just that segment's window
(`[startMs, endMs)`) so the user listens, then corrects.

- **API** (extends item 12's contract; the item-22 precedent):
  - `TurnSegmentJson` gains `id` (contract change; `turns*.json` examples updated).
  - `PUT /api/tasks/{id}/segments/{segmentId}` with `{ "text": "…" }` → `200` with the updated
    turns (or the updated turn); `404 not-found` (task or segment), `409 not-completed`
    (**COMPLETED tasks only**, item 22's rule).
  - Text rules (item 22's amendment style): **the client trims; the API rejects
    empty/whitespace-only and control-character text (`400 invalid-segment-text`); no length cap;
    stored as sent.**
  - Event: `segment-edited` → `{ id, segment: { id, startMs, endMs, text } }` after the write;
    **the open view updates in place — no `/turns` refetch** (a text edit is not a structural
    change; only merges refetch).
- **UI**: per segment, an "Edit" action opens an inline editor — **a textarea with explicit
  Save/Cancel buttons; Ctrl+Enter saves, Escape discards** — and a **separate** per-segment play
  control that seeks to `startMs`, plays, and **~~pauses~~ stops at `endMs` (plays the window once)** (stops: the Pause/Stop model, 2026-10-23); the
  existing click-to-seek keeps playing past the segment (items 9/29/31 behavior and its tests
  unchanged). A rejected save keeps the old text and shows the error. Works for speakers-off
  tasks (speaker `null`) unchanged.
- **Downloads (txt/srt/vtt) render the stored text, so edits appear in them with no renderer
  change — expected and pinned by the test list.**
- **Out of scope**: changing timings, splitting or joining segments, speaker reassignment (item
  22's merge covers speakers), server-side undo (the textarea's native undo suffices; a saved
  edit is just re-edited). Concurrent editors: last write wins, no optimistic locking.
- **Knock-on to record when this item starts**: the "Turn rows keyed by array index" known issue
  concerns whole-array RELOADs; an in-place segment edit replaces one segment and reconciles by
  the existing per-segment keys, so it is not triggered — but the item should keep the in-place
  update (no refetch) so it stays that way.

## Decided (2026-10-23, user)

- **The inline editor's direction is the segment's own, and only in edit mode**: while the textarea
  is open, its direction and alignment follow the segment's first alphabetic character (Unicode
  first-strong, `dir="auto"`), independent of the turn paragraph's direction. Numbers and
  punctuation before the first letter do not decide, and a segment with no letter falls back to the
  paragraph's direction. Closing the editor restores the transcript paragraph rule (item 76). The
  editor's Save/Cancel controls stay in the turn's layout; only the textarea's text direction and
  alignment follow the segment, and the direction can change while typing as the first strong
  character changes.
- ~~**A segment's text is one line** (amends the 2026-10-14 "multi-line edits work"): the editor
  saves every line break in the draft as a single space, so a segment stays one subtitle cue in
  every download. The API keeps rejecting control characters, a newline among them (cycle 3).~~
  — **amended 2026-10-23 after the user tried the editor:** the editor takes **no newlines at
  all**. Enter and Ctrl+Enter save, Shift+Enter does nothing, and a pasted line break becomes a
  space. The API still rejects control characters, a newline among them (cycle 3).
- ~~**The editor's box and its ways out** (2026-10-23, user trial of the first editor): it is a
  small box *inside* the turn's paragraph — a block of its own, so the rest of the turn's text
  starts below it — holding the single-line field and two small buttons, **Save** and
  **Discard**.~~ The box gave way to a dialog (the user's review, below). What still holds: Enter
  or Ctrl+Enter save; Discard or Escape close it without saving, leaving the
  stored text unchanged. While it is open the player's **Play/Pause is disabled**, so playback
  cannot start under an unfinished edit. A click anywhere else — even one that lands on no
  control — leaves the editor and its draft open (only Discard or Escape close it), and there is
  **no "are you sure?" dialog**: a one-line draft and two deliberate ways out are enough.
- **Isolated playback includes the editor** (2026-10-23, the user's question): the current
  segment's Play-segment control plays exactly `[startMs, endMs)` and stops there (back at
  `startMs` — the user's review, below), and while the
  editor is open the same control sits inside its box before Save — replaying a segment to check
  a word keeps the editor and draft open, and the main player's Play/Pause stays disabled (the
  rule is about the main player, not about all playback).
- **Saving the text the editor opened with is not a save** (2026-10-23, the user's rule): Save,
  Enter and Ctrl+Enter close the editor without a request when the draft (trimmed) equals the
  stored text the editor was opened on. Typing whisper's original text back *is* an ordinary
  save (only `edited` decides whether Revert shows), and Revert is the one route back from the
  server's side.
- **Revert restores whisper's original text** (2026-10-23, the user's choice; ADR 0060): the
  pipeline keeps each segment's original text from the first write, and a
  `POST /api/tasks/{id}/segments/{segmentId}/revert` puts it back — the same 200 turns and
  `segment-edited` an edit produces. The turns expose whether a segment differs from its
  original (`edited`), so the editor offers Revert only for an edited segment. Undo goes back to
  the beginning, for that one segment — the user's analogy with un-merging speakers.
- ~~**Segment controls belong to the selected segment**~~ — superseded the same day by **One
  current segment** below. (Was: the selection carries a sticky outline distinct from the
  playhead's fill and stays put while the playhead moves; a single click selects without
  seeking; double-click or Enter/Space seek and play; the arrows move the selection; Escape or a
  click outside clears it; opening the editor pauses playback.) Unchanged from it: the editor's
  textarea takes the segment's place, pre-filled with its text.
- **One current segment, and focus apart from it** (user's keyboard trial, 2026-10-23: the
  outline and the browser's focus ring were two frames, click-outside cleared only the focus,
  and Space restarted or paused depending on where focus had wandered):
  - **One filled highlight marks the current segment**: the playhead's segment. In a gap
    between segments (silence) the segment just played stays highlighted until the next one
    starts, playing or not (amends item 9's "nothing in a gap"). There is no separate
    selection mark.
  - **Staging focus — focused but not current**: keyboard focus on a segment that is not the
    current one shows only an empty frame (the focus ring), a position before choosing; nothing
    plays or changes. Tab moves it through every focusable element as usual. The arrow keys move
    it only between segments — Left/Up previous, Right/Down next, skipping the controls and
    everything else — scrolled into view, without changing playback; with focus outside the
    segments they scroll the transcript as before.
  - **Choosing a segment makes it current at its start**: a click, a double-click, or
    Enter/Space on a staging-focused segment. A click while not playing does not start playback;
    a click while playing keeps playing from there (a jump); a double-click or Enter/Space plays.
  - **Enter/Space on the current segment toggles**: playing → pause, the highlight staying on
    the last played segment; not playing → play on from the playhead (where it paused, or the
    segment's start after a Stop). Focus stays on the segment.
  - **While playing, focus rides along with the current segment** as long as it is on it (no
    scroll of its own; follow mode decides scrolling), so a repeated Space pauses what is
    playing. A Tab or an arrow key turns it into staging focus elsewhere.
  - **Escape, or a click outside, removes the staging focus and ~~pauses~~ stops** (stops: the
    user's review, below): playback ~~pauses~~ stops if
    it is playing, and focus lands on the current (last played) segment, not on the staged one;
    the highlight stays, so Space ~~resumes~~ plays it from its start. "Outside" is a click that lands on no control — not
    a segment or its controls, nor any button, link or field (the player, the downloads and the
    speakers panel keep working as themselves).
  - **The player's Play/Pause button does not keep focus**: after it is clicked, focus moves to
    the current segment (before the playhead reaches any segment, to the first segment), so
    Enter/Space then toggles on that segment and Tab continues from it.
  - **The silence before the first segment is skipped** (discovered from the Play/Pause rule):
    seeking into it, or starting playback in it, puts the playhead at the first segment's start
    at once, so there is always a current segment to land on.
  - **The Edit and Play-segment controls show only while nothing plays** (paused or stopped), right after the current
    segment, so nothing moves under the pointer during playback. Editing therefore never starts
    while playing; the earlier "opening the editor pauses playback" falls away.
  - **A segment that shares its start with the next, or has zero length, can still be chosen**
    (user report 2026-10-23: a song's segment could be focused but not clicked, and playing it
    jumped to the next one — whisper emitted it at the next segment's start). The current
    segment is resolved containment-first, and at the shared instant the **chosen** segment (the
    one clicked, played, or chosen from the keyboard) stays current until the playhead leaves its
    start, so it is highlighted and can be edited; then the next one takes over. A zero-length
    segment plays nothing.
  - **A segment with no text shows a grayed-out `[empty]` in its place** (the user's choice),
    clickable, choosable and editable like any other segment, so a missed phrase can be typed
    in; the placeholder is the view's and never reaches the downloads (they render the stored
    text), and the editor opens on the stored (empty) text.
- **The user's review after the green e2e run** (2026-10-23, trial with `devenv up`):
  - **Pause and Stop are two actions** (the user's model, 2026-10-23). A **Pause** holds the
    playhead where it is and resuming carries on from there: the player's Play/Pause button, and Space/Enter
    on the current segment. A **Stop** ends playback and puts the playhead at the start of the
    current (highlighted) segment, the way a click on a segment seeks to its start, so playing
    again starts that segment over: Escape, a click on no control, the segment's own Stop
    control, and the end of a segment played on its own. While a segment plays on its own its
    Play-segment control is a **Stop (a square)** — the one active control during playback (the
    user's choice (a), 2026-10-23: ordinary playback shows no controls). Beside it **Edit stays in
    place, disabled** — grayed out and not clickable (the user, 2026-10-23, instead of hiding
    it) — so the Stop sits **exactly where the Play-segment control was** and a second click
    without moving the pointer stops. A segment played on its own and paused stays
    played on its own: resuming finishes it and stops at its end, and the next segment never
    becomes current. Choosing another segment or seeking ends playing it on its own; playback
    goes on as an ordinary play.
  - **The editor is a modal dialog** on Radix's dialog, like `ConfirmDialog` (the user's
    revision 2026-10-23: "maybe blocking the header is not a big deal. Let's use standard
    things"): a shade over the whole page, the header included; the page under it is inert and
    does not scroll; focus is trapped inside and returns to the Edit control when it closes. The
    dialog's title names the segment's speaker and time range; its field wraps over several
    rows (still no line breaks: Enter saves, a pasted break becomes a space). Closing it leaves
    the edited segment current. The ways out stay as decided: Save, Discard, Escape; a click on
    the shade leaves it open.
  - **The editor is ready to type**: opening it puts focus in the field, the caret after the last
    character. Its text uses the theme's text color (in the dark theme it was black on dark gray).
  - **The editor's preview is Play/Stop**: Play plays the segment on its own from its start; while
    it plays the control is Stop. Stop, or the segment's end, stops it back at the segment's
    start. Throughout, the edited segment stays the current one, and focus stays in the field
    with the caret where it was.

## Open questions (answered 2026-10-14)

- Which tasks are editable? → **COMPLETED only** (item 22's rule); others `409 not-completed`.
- Text rules? → **The client trims; the API rejects empty/whitespace-only and control characters;
  no length cap; stored as sent.**
- Save/cancel keys? → **Explicit Save/Cancel buttons; Ctrl+Enter saves, Escape discards** — no
  accidental loss on blur, multi-line edits work.
- Isolated play? → **A separate per-segment play control that plays `[startMs, endMs)` once**;
  click-to-seek unchanged (plays past the segment).
- After a save? → **In-place update from the `200`/`segment-edited` event; no `/turns` refetch.**
- Downloads? → **They show the edited text** (the item's test list pins it).

## Test list (proposed; behaviors only)

- [x] Editing a COMPLETED task's segment stores the new text; `/turns` and the view show it
- [x] Editing a QUEUED or FAILED task's segment returns 409 `not-completed`; unknown task or segment returns 404
- [x] Text rules: empty, whitespace-only, or control-character text (client-trimmed) is rejected 400 and leaves the stored text; a valid text is stored as sent — no length cap
- [x] A speakers-off task's segment edits the same way (no speaker attached)
- [x] The edit emits `segment-edited` after the write with the new segment; an open view updates in place without refetching `/turns`
- [x] The downloads (txt, srt, vtt) contain the edited text after the edit
- [x] (view) An Edit control opens an inline textarea pre-filled with the segment's text; Save (button or Ctrl+Enter) commits, Escape discards
- [x] (view) A rejected save keeps the old text, leaves the editor open and shows the error
- [x] (view) The editor takes no newlines: Enter or Ctrl+Enter save, Shift+Enter does nothing, and a pasted line break becomes a space
- [x] (view) Discard (or Escape) closes the editor without saving; the stored text is unchanged and no request is sent
- [x] (view) While the editor is open, the player's Play/Pause is disabled
- [x] ~~(view) The editor is a small block box inside the paragraph — field and Save/Discard inside it, the rest of the turn's text below~~
      — superseded 2026-10-23 by the dialog items below (the user's review); its test is reworked with them
- [x] (backend) Each segment keeps whisper's original text; a revert restores it, answers 200 turns with `edited` false and emits `segment-edited`; turns carry `edited`
- [x] (view) Revert shows whisper's original text in place and closes the editor
- [x] (view) Saving the text the editor opened with — trimmed, so whitespace-only changes
      count — sends no request and just closes the editor
- [x] (view) The current segment's Play-segment control plays exactly its window once — from startMs, ~~stopping at endMs — and the player behaves as before afterwards~~ (amended by the stop item below); while the editor is open the same preview sits in its box (before Save), the editor and draft stay open, and the main Play/Pause stays disabled
- [x] (view) The inline editor's direction and alignment follow the segment's first alphabetic
      character (Arabic-first → RTL, Latin-first → LTR), independent of the turn's paragraph
      direction; closing the editor restores the paragraph rule, and a letterless segment falls
      back to the paragraph direction
- [x] (e2e, ADR 0046) Against the real backend, editing a segment updates the view and the downloaded txt
- [x] (must not break) The suites, the contract-shape checks (`turns*.json` gains the segment id) and the e2e journey stay green
- [x] (discovered) Two edits of different segments of one transcript that arrive back to back
      both show in the open view
- [x] (discovered) An edit that arrives while the open transcript is still loading shows once
      the transcript has loaded
- [ ] ~~(view) A line break in the editor's draft is saved as a single space (one line per segment)~~
      — superseded 2026-10-23 by the "takes no newlines" item above (a pasted break becomes a space)
- [x] (view) One filled highlight marks the current segment, the playhead's: in a gap the segment
      just played stays highlighted until the next one starts, playing or not; a staging-focused
      segment (focused, not current) shows only an empty focus frame
- [x] (view) Choosing a segment makes it current at its start: a click while not playing does not
      play, a click while playing jumps there and keeps playing, a double-click or Enter/Space on
      a staging-focused segment plays from its start
- [x] (view) Enter/Space on the current segment pauses while playing, and otherwise plays on
      from the playhead (where it paused); focus stays on the segment
- [x] (view) While playing, focus moves along with the current segment as long as it is on it,
      without scrolling of its own; staging focus moved elsewhere stays there
- [x] (view) Escape, or a click that lands on no control, removes the staging focus, ~~pauses
      playback~~ (now stops it: the stop item below) and puts focus on the current segment; its
      highlight stays; clicks on the player, the downloads or the speakers panel act as themselves
- [x] (view) After a click on the player's Play/Pause button, focus is on the current segment
      (the first segment while the playhead has reached none), so Enter/Space toggles there
- [x] (view) The current segment's Edit and Play-segment controls show only while nothing plays (paused or stopped), right
      after it
- [x] (view) With focus on a segment, the arrow keys move the staging focus only between
      segments — previous (Left/Up), next (Right/Down), skipping the controls — scrolled into
      view, without changing playback; with focus elsewhere they scroll the transcript as before
- [x] (e2e, user trial) In the browser, Tab moves the staging focus as usual and continues from
      the current segment after a Play click, never jumping back to the player; Space plays from
      a staging-focused segment and the next Space pauses with focus still on it; Escape ~~pauses~~ (now a Stop: the Pause/Stop model)
      and returns focus to the current segment
- [x] (discovered) Seeking into the silence before the first segment, or starting playback in
      it, puts the playhead at the first segment's start at once
- [x] (discovered) After the last segment ends, it stays highlighted (no next segment starts)
- [x] (discovered) Space or Enter on the current (last) segment after the audio has ended plays
      that segment from its start, not from the beginning of the audio (the media element's
      own `play()` after its end rewinds to 0)
- [x] (discovered, user trial) Creating a staging focus — Tab, or an arrow key, onto a segment
      that is not current — stops the autoscroll, as scrolling away does
- [x] (view, user report) A segment that shares its start with the next (or has zero length) can
      be clicked and becomes current at its own instant — highlighted, with its controls — until
      the playhead leaves its start
- [x] (view, user report) A segment with no text shows a grayed-out `[empty]` in its place,
      clickable and choosable like any other; the placeholder never reaches the downloads
- [x] (discovered, user report) The current segment's Edit control keeps a word-space gap from
      the segment's frame instead of touching it
- [x] (discovered, user report) The selected segment's controls take exactly the height of the
      playhead highlight, so showing them does not change the line height or the layout
- [ ] ~~(discovered) Enter or Space on a focused segment plays and selects it~~ — superseded
      2026-10-23 by the Enter/Space items above (cycle 15's test, green, is reworked with them)
- [x] (discovered, user review) A segment played on its own is stopped at its end: playback stops
      and the playhead returns to the segment's start; the segment stays current and highlighted
      with its controls, and the next segment never becomes current
- [x] (discovered, user review; reworked for the Pause/Stop model) While a segment plays on its
      own, Escape or a click on no control stops it back at the segment's start, the segment still
      current; Space/Enter on it or the player's Pause pauses it in place, and resuming finishes
      the segment and stops at its end
- [x] (discovered, user review) Escape or a click on no control stops ordinary playback too: the
      playhead goes to the current segment's start, which stays current, and Space plays from there
- [x] (discovered, user review) While a segment plays on its own, its Play-segment control shows
      as a Stop (a square) and its Edit control stays beside it, disabled (grayed out, not
      clickable); pressing Stop stops playback back at the segment's start
- [x] (discovered, user review; e2e) In a browser, the Stop occupies exactly the Play-segment
      control's box (same position and size), so a second click without moving the pointer stops
- [x] (discovered, confirmed) Choosing another segment (a click, a double-click, Enter/Space) or
      seeking with the player while a segment plays on its own ends the isolated play: playback
      goes on from there as an ordinary play, and the old segment's end no longer stops it
- [x] (discovered, user review) The editor opens as a dialog in the middle of the viewport over a
      shade covering the page except the header; while it is open, nothing under the shade
      answers a click, a double-click or a key, Tab stays inside the dialog, and the page does not
      scroll
- [ ] ~~(discovered, user review) While the editor is open the header stays usable; leaving the
      transcript from it drops the draft without a request~~ — superseded 2026-10-23 by the
      modal dialog (the header is shaded and inert with the rest of the page)
- [x] (discovered, user review) The dialog's title names the segment's speaker (none when speakers
      are off) and its time range
- [x] (discovered, user review) The dialog's field wraps a long segment over several rows, still
      without line breaks
- [x] (discovered, user review) Opening the editor puts focus in its field with the caret after
      the last character
- [x] (discovered, user review) Closing the editor (Save, Revert, Discard, Escape) returns focus
      to the Edit control it was opened from, on the edited segment, which is still current
- [x] (discovered, user review) The editor's field text uses the theme's text color, legible in
      the dark theme as in the light
- [x] (discovered, user review) The editor's preview control reads Play while stopped and Stop
      while the segment plays; Stop, or the segment's end, stops it back at the segment's start;
      the edited segment stays current throughout, and focus stays in the field with the caret
      where it was
- [x] (discovered, user review; e2e, ADR 0046) In a browser, with the editor open, a click on a
      segment under the shade changes nothing and the page does not scroll; the edit journey runs
      through the dialog

- [x] (discovered, 2026-10-23, root review) The Edit control, the segment's Play/Stop control and
      the dialog's Play/Stop control are operable with Enter and Space, and Space on them neither
      scrolls the transcript nor stops follow mode
- [x] (discovered, 2026-10-23, root review) Typing a space or moving the caret with the arrow keys
      in the open editor does not stop follow mode; after the dialog closes, playback still
      scrolls the transcript
- [x] (discovered, 2026-10-23, root review) An edit that reaches the store after its task has left
      COMPLETED (a retry started) stores nothing and answers 409 `not-completed`; Revert is the
      same
- [x] (discovered, 2026-10-23, root review) `docs/api/openapi.yaml` documents the revert route and
      the segment's `edited` flag as the app serves them, and the contract-shape test stays green
- [x] (discovered, 2026-10-23, root review) With a segment focused, `e` opens that segment's
      editor and `p` plays it on its own (a second `p` stops it); while the dialog is open
      Alt+S or Ctrl+S saves and Alt+D or Ctrl+D discards, and Save and Discard underline their
      letter; the icon controls advertise `e` and `p` through `aria-keyshortcuts`

## Acceptance criteria

- A finished transcription's segments are editable from the transcript view, with per-segment
  isolated playback; edits persist in the database and show in the view, the downloads and the
  speakers-off view.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-14: filed from the user's request (edit button per segment; play the segment in
  isolation while editing — the typical copy-edit flow).
- 2026-10-14: consultation — COMPLETED tasks only; client trims, API rejects empty/whitespace-only
  and control characters, no length cap, stored as sent; inline textarea with Save/Cancel buttons
  (Ctrl+Enter saves, Escape discards); a separate per-segment play control playing the window
  once (click-to-seek unchanged); in-place update from the `200`/`segment-edited` event without a
  `/turns` refetch; downloads reflect edits. Test list approved; status `spec-approved`.
- 2026-10-14 (workspace, cycle 1, test-list item 1, backend half as confirmed): new
  `SegmentEditRoutesSpec` — a COMPLETED task with three segments over two speakers; the segment
  ids come from `GET /turns`, which now names every segment by its stored row id; `PUT
  /api/tasks/{id}/segments/{id}` with `"Corrected sentence."` must answer `200`, shaped like
  `turns.json`, equal to the turns derived by hand (the edited segment keeps its window and
  speaker, its text changes, and its turn's text is the rejoined trimmed texts) and equal to the
  `/turns` read after it; read from the database after the app stopped, the edited row holds the
  sent text with its window and speaker while the other rows are as seeded. Red:
  `java.util.NoSuchElementException` at `SegmentEditRoutesSpec.kt:79` — `/turns` segments carried
  no `id`. Green: `Segment` gains `id` (the row's id; null before insertion), `selectSegments`
  reads it, `TurnSegment` carries it through `speakerTurns`, and `TurnSegmentJson` is
  `{ id, startMs, endMs, text }`; one `turnsJson(details)` now builds the body for both
  `GET /turns` and the new PUT; `SegmentRepository.updateSegmentText` updates one row in one
  write transaction and reports whether the task has that segment; new `http/SegmentEditRoutes.kt`
  answers `200` with the updated turns and `404 not-found` for a non-numeric id or a segment that
  is not the task's, and reuses `completedTaskOrError` (`409 not-completed`). Contract:
  `turns*.json` examples, `openapi.yaml` (`TurnSegment.id`, the endpoint, `SegmentId`, the
  `segment-edited` schema), `docs/api/README.md` and item 12's contract updated in this commit,
  plus `docs/api/segment-edited.json` (the event itself lands in a later cycle). Existing
  exact-JSON `/turns` assertions now carry the hand-derived ids; content assertions over
  `findByTask` that do not check ids use the new test helper `withoutIds()`. `gradle check`
  green (316 tests); frontend vitest 36 files / 177 tests, prettier, `tsc --noEmit`, `vite build`
  green.
- 2026-10-14 (cycle 1 checkpoint, user decisions): all three refactors approved, each its own
  commit — `TurnSegment.toJson()` maps a turn segment in one place (the same mapping item 5's
  event needs), `SpeakerTurn.toJson()` shortens `turnsJson`, and `HttpClient.turnsOf` moved to the
  shared test helper `TurnsTestClient.kt`. `gradle check` green after each; the test-list item is
  ticked.
- 2026-10-14 (workspace, cycle 2, test-list item 2): extended `SegmentEditRoutesSpec` — a QUEUED
  and a FAILED task with one segment each plus a COMPLETED task; editing the unfinished tasks'
  segments must answer `409 not-completed`, while task `999`, task `abc`, segment `999`, segment
  `abc` and the QUEUED task's segment addressed through the COMPLETED task must each answer `404
  not-found`, every response with the `error.json` shape; after the app stops, all three tasks'
  stored texts are as seeded. Green on arrival (cycle 1's `completedTaskOrError` and
  `updateSegmentText` already cover these paths); red recorded against a temporary, reverted
  mutation (`HttpStatusCode.Conflict` → `BadRequest` in `completedTaskOrError`, this spec only):
  `expected:<409 Conflict> but was:<400 Bad Request>` at the QUEUED task's edit. No production
  change; `gradle check` green.
- 2026-10-14 (cycle 2 checkpoint, user decisions): refactor approved — `HttpResponse.shouldBeError`
  now lives once in `HttpTestAssertions.kt` (also asserting the JSON content type) and is used by
  `SpeakerRoutesSpec`, `SegmentEditRoutesSpec` and `TranscriptDownloadErrorsSpec`; the three copies
  are gone. `gradle check` green; item 2 ticked.
- 2026-10-14 (workspace, cycle 3, test-list item 3): extended `SegmentEditRoutesSpec` — a COMPLETED
  task with one stored segment; `PUT` with each of `""`, `"   "`, `"\t\n"`, `"Hello\nworld"`,
  `"Hi\u0000there"` and `"\u007F"` must answer `400 invalid-segment-text` with the `error.json`
  shape, and the seeded text is still there after the app stops; a valid text of over 10,000
  characters with internal double spaces must answer `200`, name the segment with the text as
  sent in the returned turns, and store it byte for byte with its window and speaker. Red: the
  empty text answered `200 OK` (`expected:<400 Bad Request> but was:<200 OK>` at
  `SegmentEditRoutesSpec.kt:264`). Green: `SegmentEditRoutes` rejects text that is blank or
  contains an ISO control character (`400 invalid-segment-text`, nothing written) before the
  update. Contract already named the code and rules; no doc change. `gradle check` green.
  No refactoring suggested and none approved at the checkpoint: the rule is one expression used
  once and its message appears once. Note for item 7: newline counts as a control character, so
  the API rejects multi-line text even though the textarea accepts it — to confirm with the
  editor's tests.
- 2026-10-23: the user added the editor-direction rule: in edit mode the inline textarea takes the
  segment's own first-alphabetic direction (independent of the paragraph); outside edit mode the
  paragraph rule applies. Test list amended with that scenario; the interaction is recorded in item
  76 as the one sub-paragraph exception.
- 2026-10-23 (workspace, cycle 4, test-list item 4): extended `SegmentEditRoutesSpec` — a COMPLETED
  speakers-off task with two segments and no speakers rows; `PUT` on the first segment with
  `"Corrected milk."` must answer `200`, shaped like `turns.speakers-off.json`, with `speakers: []`,
  `hasMerges: false`, one turn per segment with `speakerId: null`, the edited turn's trimmed text
  and unchanged window, and `/turns` equal to the body; read from the database after the app
  stopped, the edited row holds the new text and its `speakerId` stays null. Green on arrival (the
  speakers-off branch and `updateSegmentText` already cover it); red recorded against a temporary,
  reverted mutation in `turnsJson`'s speakers-off branch (an empty turn list): assertion failure at
  `SegmentEditRoutesSpec.kt:348`. The seed helper now skips `storeSpeakers` when its speaker list is
  empty. No production change; `gradle check` green. No refactoring suggested or approved: one
  guard line in the test seed plus one case; no duplication to remove.
- 2026-10-23 (workspace, cycle 5, test-list item 5, backend half): extended `SegmentEditRoutesSpec`
  — a COMPLETED task with one segment, a real server with the queue paused and an SSE client past
  its `snapshot`; `PUT ""` must answer `400` and `PUT "  Corrected.  "` `200`, after which the
  next frame must be `segment-edited`, shaped like `docs/api/segment-edited.json` and equal to the
  hand-derived `{ id, segment: { id, startMs: 0, endMs: 1000, text: "Corrected." } }` (stored
  window and id, text trimmed as every reader sees it, ADR 0030); after the app stops the row
  holds `"  Corrected.  "` as sent. The cycle was resumed after a sandbox crash with the work
  uncommitted and no red on record; red recorded by running the scenario with every `src/main`
  change (and `EventsOverflowSpec`'s new branch) set aside, then restored:
  `expected:<segment-edited> but was:<heartbeat>` at `SegmentEditRoutesSpec.kt:406` — no frame
  for the edit. Green: `Event.SegmentEdited(id, segment)`; `ProcessingEngine.editSegment` stores
  through `SegmentRepository.updateSegmentText` (now returning the stored row, or null when the
  task has no such segment) and only then emits; the edit route calls the engine after its text
  check, so a rejected edit emits nothing; `EventsRoutes` names it `segment-edited` with payload
  `SegmentEditedJson(id, segment: TurnSegmentJson)`; the new `Segment.toTurnSegmentJson()` (row
  id, window, trimmed text) builds both the event's segment and the speakers-off turn;
  `EventsOverflowSpec`'s exhaustive `when` names the event. Contract already described the event
  (item 12's spec, `openapi.yaml`, `segment-edited.json`); no doc change. `gradle check` green
  (347 tests). The view half of item 5 (an open view applies the event in place without a
  `/turns` refetch) is still open; the item stays unticked.
- 2026-10-23 (cycle 5 checkpoint, user decisions): three refactors approved, each its own commit.
  The scenario is named after what it asserts ("…carrying the stored segment with its text
  trimmed"); the proposed seed change (`speakerId = 0` on the inserted segment) was dropped as
  wrong — the seed inserts segments with no speaker and `storeSpeakers` assigns them, the engine's
  order, and inserting a speaker id first violates the speakers foreign key. `updateSegmentText`
  reads back only the edited row, through a file-level `ResultRow.toSegment()` beside
  `toSpeaker()` that `selectSegments` uses too. `Segment.toTurnSegmentJson()` is now
  `Segment.toJson()`, named like `TurnSegment.toJson()`. `gradle check` green after each. Next
  test, confirmed: the view half of item 5.
- 2026-10-23 (workspace, cycle 6, test-list item 5, view half): new `TaskTranscriptView` test —
  task 3 open with `turns.json` and a counting `/turns` mock; a `segment-edited` for task 5
  (segment 2, "Not this transcript.") then one for task 3 (segment 2, "Thanks for joining us,
  everyone.") arrive over the stream. The new text must show, the old text and the other task's
  text must not, the turn's other segment and the next turn stay as loaded, and `/turns` was
  read once. Red: `Unable to find an element with the text: Thanks for joining us, everyone.` at
  `TaskTranscriptView.test.tsx:783` — the event was not handled. Green: `ServerEvent` gains
  `segment-edited` (`id`, `segment`), `subscribeToEvents` listens for it, `connectionMachine`
  keeps the latest edit per task in `segmentEdits` (the `speakerChanges` pattern), the view sends
  it to `turnsMachine` as `SEGMENT`, which replaces the segment with that id in the loaded turns.
  The turn's `text` is left as loaded (nothing in the frontend reads it). Vitest 36 files / 178
  tests, prettier, `tsc --noEmit`, `vite build` green; backend unchanged.
- 2026-10-23 (cycle 6 checkpoint, user decisions): two test-only refactors approved, each its own
  commit — `countTurnsRequests(body)` is the one counting `/turns` mock of the speakers-event and
  segment-event tests, and the speakers test sends the other task's rename first, so its
  "changes nothing here" check runs after the stream delivered it. Vitest 178 tests, prettier and
  `tsc --noEmit` green after each; item 5 ticked. Raised at the checkpoint and not reproduced in
  jsdom: two edits of one task arriving in one stream chunk might render once and show only the
  later. The user proposed sending the current text with the new one and storing only when the
  current text matches the database; that departs from "last write wins, no optimistic locking"
  (Decided, 2026-10-14) and is open until the user decides. Next test, confirmed: item 6.
- 2026-10-23 (workspace, cycle 7, test-list item 6): extended `SegmentEditRoutesSpec` — the
  `TranscriptDownloadSpec` seed (three segments, speakers 0, 0, 1); `PUT` of the middle segment
  with `"  Corrected sentence.  "` answers `200`, then `GET /transcript?format=txt|srt|vtt` must
  each equal golden strings derived by hand from item 10's rules for that seed, with `" again."`
  replaced by the edit, trimmed (ADR 0030). Green on arrival (downloads render the stored text,
  as the spec expected); red recorded against a temporary, reverted mutation in
  `updateSegmentText` (the update writes the column's own value, so the PUT still answers `200`):
  the txt case at `SegmentEditRoutesSpec.kt:468`, expected `…Corrected sentence.…` but was
  `…again.…`. No production change; `gradle check` green (348 tests).
- 2026-10-23 (cycle 7 checkpoint, user decisions): no refactoring suggested (the repeated seed
  is two copies) and none done; item 6 ticked. The user dropped the optimistic-locking proposal:
  concurrent editors stay last write wins, as decided on 2026-10-14. The view-side risk raised
  at the cycle 6 checkpoint is added to the test list as a discovered scenario (two edits of
  different segments arriving back to back both show), to be fixed client-side by keeping the
  latest edit per segment rather than per task. Next test, confirmed: that scenario.
- 2026-10-23 (workspace, cycle 8, discovered scenario): new `TaskTranscriptView` test — task 3
  open with `turns.json`; two `segment-edited` events for task 3 (segment 1 "Good morning, all.",
  segment 2 "Thanks for coming.") arrive in one stream chunk through the new
  `controllableEventStream().sendTogether`, so EventSource dispatches both before the page renders
  again; both new texts must show. A throwaway check first confirmed the loss happens only with
  one chunk (two separate chunks rendered in between). Red: `Unable to find an element with the
  text: Good morning, all.` at `TaskTranscriptView.test.tsx:816` — only the later edit showed.
  Green: `connectionMachine.segmentEdits` keeps the latest edit per task and segment id, and the
  view sends every edit of its task to `turnsMachine` as `SEGMENT` (re-applying one is
  idempotent). Vitest 36 files / 179 tests, prettier, `tsc --noEmit`, `vite build` green.
- 2026-10-23 (cycle 8 checkpoint, user decisions): refactor approved — `turnsMachine` takes a
  task's edits as one `SEGMENTS` event (matched by id), so they land in one state update; vitest
  179 tests, prettier and `tsc --noEmit` green; the discovered scenario ticked. A second
  discovered scenario added at the user's request, though not observed: `turnsMachine` applies
  edits only once loaded, so an edit arriving while `/turns` loads would be lost when that read
  ran before the write. (Speaker renames share the same gap; that is item 22's, noted only.)
  Next test, confirmed: that scenario.
- 2026-10-23 (workspace, cycle 9, discovered scenario): new `TaskTranscriptView` loading-state
  test — task 3 opens; its `/turns` handler pushes a `segment-edited` for segment 2 ("Thanks for
  joining us, everyone.") into the stream, yields one macrotask so the stream delivers it, then
  answers `turns.json` with the old text (a read that ran before the write); the new text must
  show once loaded and the old must not. Red (5 of 5 runs): `Unable to find an element with the
  text: Thanks for joining us, everyone.` at `TaskTranscriptView.test.tsx:905` — the edit arrived
  while loading and the answer replaced it. Green: the view's edit effect also depends on the
  turns being loaded, so a task's edits are applied again each time its turns (re)load. Passes in
  either event order. Vitest 36 files / 180 tests, prettier, `tsc --noEmit`, `vite build` green.
- 2026-10-23 (cycle 9 checkpoint, user decisions): no refactoring suggested (one effect
  dependency; the edit payload repeats in two tests only) and none done; the discovered scenario
  ticked. The same loss for `speakers-renamed` was reproduced by a throwaway test and recorded in
  `docs/known-issues.md` (MED; item 22's area). Spec amended: the 2026-10-14 answer "multi-line
  edits work" contradicted cycle 3's API rule (a newline is a control character, `400`); the user
  chose to keep the API rule and have the editor save line breaks as spaces — a new Decided bullet
  and test-list item. Next test: item 7, starting with Edit opening a textarea pre-filled with the
  segment's text and Save committing it.
- 2026-10-23 (user decisions, before item 7): the user's UX concern — clicking a segment plays
  it and the highlight moves on, so an Edit control beside the clicked segment could be taken
  for the highlighted one's. Decided: controls belong to a selected segment with a sticky mark
  distinct from the playhead highlight, the editor replaces the segment in place, and opening it
  pauses playback. Two Decided bullets and two test-list items added.
- 2026-10-23 (workspace, cycle 10, selection item, first scenario): new `TaskTranscriptView` test
  — task 3, no Edit control at first; clicking segment 2 seeks to 2 400 ms as before, marks it
  `aria-current="true"` and puts the one "Edit segment" control right after it; moving the
  playhead to 6 000 ms highlights segment 3 while segment 2 keeps the mark and the control;
  the selection is an outline (`outline-style: solid`) without the accent fill, the playhead the
  accent fill without an outline. Red: `toHaveAttribute("aria-current", "true")` failed at
  `TaskTranscriptView.test.tsx:219`. Green: `TaskTranscriptView` keeps the selected segment id
  (`useState`; a plain value) and sets it from the existing click/Enter handler, which now passes
  the segment; `TranscriptTurns` marks the selected segment (`aria-current`, a token outline in
  longhands — jsdom does not expand the `outline` shorthand, checked with a throwaway probe) and
  renders an Edit `IconButton` after it, without behavior until item 7's first scenario. Two
  `within(required(…))` lookups in the new test became `toContainElement` for typing; no
  assertion changed. Vitest 36 files / 181 tests, prettier, `tsc --noEmit`, `vite build` green.
  Visual check not possible here (no browser in the sandbox).
- 2026-10-23 (cycle 10 checkpoint, user decisions): no refactoring suggested and none done. The
  user checked the mock UI: the pencil's place and icon are right, but its square is taller than
  the text line, so it raises the line height and shifts the layout; it should be exactly the
  height of the highlight — added to the test list as a discovered scenario. Playback with the
  selection could not be judged in the mock (no audio). Next test, confirmed: the rest of the
  selection item (another segment takes the selection; Escape clears it).
- 2026-10-23 (workspace, cycle 11, selection item, second scenario): new `TaskTranscriptView`
  test — segment 1 selected, then a click on segment 4 seeks to 9 600 ms and moves the mark and
  the Edit control there (segment 1 keeps neither); Escape in the transcript leaves no
  `aria-current` in the transcript region and no Edit control. A first run failed on the app's
  nav link (`aria-current="page"`): the check was page-wide, so it was scoped to the transcript
  region before the red. Red: `expected <span role="button" …> to be null` at
  `TaskTranscriptView.test.tsx:263` — Escape left the selection. Green: the transcript region's
  key handler clears the selection on Escape (moving it already worked from cycle 10). Vitest 36
  files / 182 tests, prettier, `tsc --noEmit`, `vite build` green. The item stays unticked: the
  Enter path selects through the same handler as a click but no test shows it yet.
- 2026-10-23 (cycle 11 checkpoint, user decisions): no refactoring suggested and none done. The
  user tried the selection against the real backend: the frame around the clicked segment works.
  Two UX gaps reported: there is no way to move between segments without clicking (which seeks
  the audio there), and no way to remove the frame once it is set (Escape exists but was not
  found). Open until the user decides. For the pencil-height report the user chose two checks —
  a Vitest style check and a Playwright measurement of the real layout. Next test, confirmed:
  the pencil height.
- 2026-10-23 (workspace, cycle 12, discovered scenario from the user report): new
  `TaskTranscriptView` test — after selecting segment 2, the Edit control's computed `display`
  is `inline`, its vertical padding `0px`, and its icon no taller than the body font size; jsdom
  has no layout, so new e2e spec `52-edit-segment.spec.ts` measures it in the real app: after a
  click on the first segment of `HEADER_CLIP`'s transcript, the control's box height equals the
  segment highlight's height on its last line (±0.5 px) and the icon lies inside the box. Red:
  `expected 'inline-flex' to be 'inline'` at `TaskTranscriptView.test.tsx:277`. Green: the Edit
  control is an inline element with the button role (like the segment), no padding, the `sm`
  icon token (1rem = the body size), vertically centred in the line; `IconButton` no longer
  used there. Vitest 36 files / 183 tests, prettier, `tsc --noEmit`, `vite build` green; the e2e
  spec is formatted but not run here (no browser, no e2e dependencies in the sandbox).
- 2026-10-23 (user decisions, the cycle 11 UX gaps): a single click selects without seeking;
  seeking and playing from a segment moves to a double-click or Enter/Space (both also select),
  replacing the single-click seek of items 9/29/31 — their specs (`docs/specs/`), unit tests and
  e2e specs that click a segment to seek change with it. With a segment selected, the arrow keys
  move the selection without seeking (focus follows, scrolled into view); a click outside the
  segments and their controls clears the selection, besides Escape. The selection test-list
  item is reworded and two items added (double-click/Enter seek; arrow keys); cycles 10–11's
  scenarios assert the single-click seek and change in the next selection cycle.
- 2026-10-23 (cycle 12 checkpoint): no refactoring suggested (`SegmentControl` is used once; the
  Play-segment control will reuse it) and none done. The user ran e2e spec `52-edit-segment` on
  their laptop: 1 passed (12.1 s) against the real backend and the branch's built frontend —
  the control is the highlight's height with its icon inside. The run first failed in the e2e
  global setup (the Linux devenv ffmpeg has no WAV muxer, a pre-existing problem now in
  `docs/known-issues.md`); it passed with a full ffmpeg first on PATH for that run
  (`nix shell nixpkgs#ffmpeg-headless`). The pencil-height item ticked.
- 2026-10-23 (workspace, cycle 13, double-click seek): new `TaskTranscriptView` test — a
  double-click on segment 2 must seek to 2 400 ms, play once, and select it (its Edit control
  follows). Red: `expected +0 to be 2.4` at `TaskTranscriptView.test.tsx:217` — a double-click
  did nothing. Green: a segment's `onDoubleClick` calls the same handler as its click, so the
  seek-and-play that the click still owns is reachable by a double-click too; the next cycle
  takes it off the single click. Vitest 36 files / 184 tests, prettier, `tsc --noEmit`,
  `vite build` green. One gates run failed six unrelated tests on the 5 s timeout (the known
  contention flake); the clean re-run was green. The double-click/Enter test-list item stays
  unticked until the single click stops seeking.
- 2026-10-23 (workspace, cycle 14, the single click only selects): the click-to-seek test is
  rewritten as "clicks a segment to select it without seeking or playing" — the player stays at
  0 ms, play is not called, the playhead highlight stays on segment 1 `[0,2100)`, and the clicked
  segment is selected with its Edit control. Red: `expected 2.4 to be +0` at
  `TaskTranscriptView.test.tsx:199`. Test corrected after the red, before any code change: the
  first draft expected no highlighted segment at all, which is wrong at 0 ms (segment 1 contains
  it); the expectation was derived again from turns.json. Green: `TranscriptTurns` takes
  `onSegmentSelect` (click) and `onSegmentSeek` (double-click, Enter/Space) in place of
  `onSegmentClick`; the view selects on the first and seeks, plays and selects on the second.
  Cycles 10–11's tests lose their seek assertions. Cutover in the same commit: e2e
  `06-playback.spec.ts` double-clicks to seek and `gifs-record.spec.ts` dispatches a DOM
  `dblclick`; both count segments as `[role="button"]:not([aria-label])`, since the selected
  segment's controls are buttons too (unrun in the sandbox; the user runs them). Dated amendments
  in `docs/specs/frontend-history-ui.md` (Proposal, answered question, log) and a log line in
  `docs/specs/frontend-backend-end-to-end.md`. Vitest 36 files / 184 tests, prettier (frontend
  and e2e), `tsc --noEmit`, `vite build`, the docs link/architecture checks green.
- 2026-10-23 (cycle 14 checkpoint): approved and done as `Refactor:` — the double-click test's
  stale comment ("the single click keeps them only until the next cycle") now says the click
  only selects, and the selection tests drop the `play` stubs a click no longer needs. Not done
  (recommended against): a shared e2e segment-selector constant in `helpers/ui.ts`. Discovered
  and added by the user: Enter/Space plays and selects, not only seeks — the double-click/Enter
  item stays unticked until it is green.
- 2026-10-23 (workspace, cycle 15, Enter/Space play and select): new `TaskTranscriptView` test —
  Enter on segment 2 selects it with its Edit control, plays once and seeks to 2 400 ms; Space on
  segment 4 moves the selection, plays again and seeks to 9 600 ms (turns.json's starts). Green
  on arrival (the keys already reach the double-click's handler); red recorded via a reverted
  mutation (the keys doing nothing): `expect(element).toHaveAttribute("aria-current", "true")`
  at `TaskTranscriptView.test.tsx:241`. Commit `1c386183`. Vitest 36 files / 185 tests, tsc,
  prettier green. Refactor suggestions (a shared selection assertion; trimming the older
  keyboard test) were not taken: the selection tests are reworked under the new model below.
- 2026-10-23 (user's keyboard trial in the real app, then decisions): cycle 15 works as
  specified, but the model confused — the selection outline and the browser's focus ring were
  two frames, a click outside cleared only the focus, the next Space restarted or paused
  depending on where focus had wandered, and Tab sometimes went back to the player.
  Approved: **one current segment, and focus apart from it** (Decided section): one filled
  highlight, the playhead's segment, which stays on the segment just played through a gap;
  staging focus (focused, not current) as an empty frame, moved by Tab and, between segments
  only, by the arrow keys; choosing a segment (click, double-click, Enter/Space on a staged one)
  makes it current — a click while paused moves the player without playing, a click while
  playing jumps; Enter/Space on the current segment toggles pause/resume; focus rides along with
  the current segment while playing; Escape or a click on no control removes the staging focus,
  pauses and returns focus to the current segment; the player's Play/Pause hands focus to the
  current segment; the controls show only while paused (pause-on-Edit falls away). Discovered
  and added by the user: the silence before the first segment is skipped. The test list's
  selection, double-click/Enter, pause-on-Edit and cycle-15 items are replaced by the new items;
  cycles 10–15's tests are reworked by them. Specs amended in cycle 14 (items 15/21) change
  again with the click's new seek.
- 2026-10-23 (workspace, cycle 16, the highlight kept through a gap): item 9's highlight test
  (renamed "…keeps the one just played through a gap…") now expects segment 1 highlighted at
  2 250 ms (gap 2 100–2 400), segment 2 from 2 400 ms, and segment 2 kept at 5 500 ms (gap
  5 200–5 800), from turns.json's times. Red: `AssertionError: the given combination of
  arguments (undefined and string) is invalid` at `TaskTranscriptView.test.tsx:177` (nothing
  highlighted in the gap). Green: `segmentAtTime` returns the last segment starting at or before
  the playhead (null before the first). Item 15's spec amended (highlight bullet, log). Commit
  `c65d4ddf`; vitest 36 files / 185 tests, tsc, prettier green. The highlight item stays
  unticked: its staging-focus frame part is open.
- 2026-10-23 (cycle 16 checkpoint): approved and done as `Refactor:` (`b3d21c20`) —
  `segmentAtTime` renamed `currentSegmentAt` (in a gap it returns the current segment, not the
  one at the time); `check-architecture-names` green; item 15's historical log keeps the old
  name. Discovered and accepted: the last segment stays highlighted after it ends (the same code
  already does it; no test yet). User report, accepted as the next test: the Edit pencil
  touches the segment's frame and needs a word-space gap (a left margin of `spacing.xs`, not
  padding, so the hover and focus boxes stay around the icon).
- 2026-10-23 (workspace, cycle 17, the pencil's gap): new test — the Edit control's left margin
  is `spacing.xs` (0.25rem, about a word space) and its left padding 0. Red: `expected '0' to be
  '0.25rem'` at `TaskTranscriptView.test.tsx:353`. Green: `SegmentControl` gets
  `margin-left: spacing.xs`. Test correction after the green, not a weakened value: jsdom
  resolves rem to px, so the expectation goes through the file's `px()` (16 px per rem) —
  `4px`. Commit `d0ab58f0`; vitest 36 files / 186 tests, tsc, prettier, `vite build` green. The
  user checks the gap by eye. Checkpoint: no refactoring suggested (two short style tests, one
  report each); no discoveries.
- 2026-10-23 (workspace, cycle 18, the last segment kept): new test — at 15 000 ms, past
  turns.json's last segment [9600,14250), that segment stays highlighted. Green on arrival
  (cycle 16's rule); red via a reverted mutation (null once the last segment has ended): nothing
  highlighted at `TaskTranscriptView.test.tsx:209`. Commit `c4570e9d`; vitest 36 files / 187
  tests (a first run timed out two unrelated tests at 5.1–5.8 s under load; clean rerun green),
  tsc, prettier green. Checkpoint: no refactoring suggested; no discoveries. Next, confirmed: a
  click while paused makes the segment current at its start without playing.
- 2026-10-23 (workspace, cycle 19, a click while paused makes the segment current): cycle 14's
  test becomes "clicks a segment while paused to make it current at its start, without playing"
  — a click on segment 2 puts the player at 2.4 s, `play()` uncalled, segment 2 filled
  (`data-active`, accent background), `aria-current`, no outline, its Edit control after it; a
  click on segment 4 moves all of it to 9.6 s, one Edit control. Red: `expected +0 to be 2.4` at
  `TaskTranscriptView.test.tsx:226`. Green: the separate selection is gone — the current
  segment (the playhead's) carries `aria-current` and the Edit control; a click calls `seek`,
  the double-click and Enter/Space `playFrom`; Escape clears nothing. Removed as decided away:
  cycle 10's sticky-outline test and cycle 11's move-and-Escape test (its move part lives on in
  the new test). Same commit: `17-follow-mode.spec.ts` counts only segments (the Edit control
  now shows from load on); e2e comments and `52-edit-segment`'s title say "current"; item 15's
  spec re-amended (click-to-seek, answered question, log) and item 21's spec logged. Commit
  `8b2e494d`; vitest 36 files / 185 tests, tsc, prettier (frontend, e2e), `vite build`, docs
  checks green; e2e unrun here. Interim until their items: the Edit control also shows while
  playing, and Escape does nothing.
- 2026-10-23 (cycle 19 checkpoint): approved and done as two `Refactor:` commits —
  `activeSegment` → `currentSegment` (view, `TranscriptTurns` prop, `$active` → `$current`; the
  `data-active` attribute stays for the e2e specs and tests) (`34e3af27`), and the double-click,
  Enter/Space and control-height tests named after the current segment (`c1ec5b2e`). No
  discoveries. Next, confirmed: a click while playing jumps there and keeps playing.
- 2026-10-23 (workspace, cycle 20, a click while playing jumps): new test — with playback
  running (Play clicked, the audio's `play` event), a click on segment 4 puts the player at
  9.6 s, makes it current, calls no `pause()`, and the player still shows Pause. Green on
  arrival (a click only seeks); red via a reverted mutation (the click also pausing): `expected
  "pause" to not be called at all, but actually been called 1 times` at
  `TaskTranscriptView.test.tsx:265`. Commit `9b376dd3`; vitest 36 files / 186 tests on the clean
  rerun, tsc, prettier green. The first run timed out three unrelated tests (5.4–6.3 s) — the
  known flake's second recurrence today, recorded in `docs/known-issues.md` (raising
  `testTimeout` is due, outside this item). Checkpoint: no refactoring suggested; no
  discoveries. The choosing item is ticked (click while paused: cycle 19; click while playing:
  20; double-click: 13; Enter/Space on a segment that is not current: 15). Next, confirmed:
  Enter/Space on the current segment toggles pause/resume.
- 2026-10-23 (workspace, cycle 21, the Enter/Space toggle): new test — with focus on segment 2,
  Enter (not current yet) plays from 2.4 s; playback runs on to 3 s; Enter again (current,
  playing) calls `pause()` once, no `play()`, stays at 3 s, stays current, player shows Play;
  Space (current, paused) calls `play()` with the player still at 3 s and focus on the segment.
  Red: `expected "pause" to be called 1 times, but got 0 times` at
  `TaskTranscriptView.test.tsx:338` (Enter restarted the segment). Green: `TranscriptTurns`
  routes Enter/Space on the current segment to the new `onCurrentToggle` (any other segment
  still `onSegmentPlay`); the view's `togglePlayback` pauses or resumes. Item 15's spec amended
  (click-to-seek bullet, log). Commit `be1efe57`; vitest 36 files / 187 tests, tsc, prettier,
  `vite build`, architecture checks green. The toggle item is ticked.
- 2026-10-23 (cycle 21 checkpoint): approved and done as `Refactor:` (`bba66719`) — one toggle:
  `AudioPlayer` takes `onToggle` for its Play/Pause button instead of its own play/pause
  choice, which `togglePlayback` in the view now owns (same for the segment keys; also the
  hook the Play-button-focus item needs). The first gates run timed out one unrelated test
  (5 057 ms) — the known flake again, now recurring often; clean rerun green. Discovered and
  added by the user: after the audio has ended, Space/Enter on the (current, last) segment
  would rewind to 0 by the media element's own rules; it must play that segment instead.
- 2026-10-23 (workspace, cycle 22, focus rides along): new test — focus on segment 2, Enter
  plays, the playhead runs into segment 3: focus moves to segment 3 with `preventScroll`; focus
  then put on segment 4 by hand stays there as the playhead runs on. Red: `expected
  <span role="button" …(2)></span> to be <span role="button" …(4)></span>` at
  `TaskTranscriptView.test.tsx:384`. Green: `TaskTranscriptView` remembers the current
  segment's element and moves focus only if focus was on or inside the previous one. The first
  draft keyed the ref on the `Segment` model, not the element; `tsc` rejected it
  (`Property 'contains' does not exist on type 'Segment'`). Commit `14a754d9`; vitest 36 files /
  188 tests, tsc, prettier green. The focus item is ticked.
- 2026-10-23 (cycle 22 checkpoint): no refactoring suggested (the two effects share one selector
  string; merging them would hide their different jobs); no discoveries.
- 2026-10-23 (workspace, cycle 23, Escape): new test — playing from segment 2 into segment 3,
  Escape in the region pauses once, keeps segment 3 current, highlighted and focused; with
  staging focus on segment 4, Escape lands on segment 3 and pauses nothing more. Red: `expected
  "pause" to be called 1 times, but got 0 times` at `TaskTranscriptView.test.tsx:419`. Green:
  the region's key handler handles Escape before the scroll keys: pause if playing, focus the
  current segment with `preventScroll`. Commit `f95038a7`; vitest 36 files / 189 tests, tsc,
  prettier green.
- 2026-10-23 (cycle 23 checkpoint): approved and done as `Refactor:` (`8ba9f2db`) —
  `currentSegmentElement()` in the view, one name for the `[data-active]` lookup the follow
  effect, the focus effect and Escape each made; the pause pair stays inline in its two places.
- 2026-10-23 (workspace, cycle 24, a click on no control): new test — playing into segment 3
  with staging focus on segment 4, a click on the region's own area pauses once, lands focus on
  segment 3, which stays current, and leaves segment 4 not current. Red: `expected "pause" to
  be called 1 times, but got 0 times` at `TaskTranscriptView.test.tsx:457`. Green: a document
  click listener ignores any target inside `CONTROLS` (buttons, links, fields, labels, the
  button/link/slider roles, editable text) and otherwise pauses and focuses the current
  segment. Commit `b8d2485a`; vitest 36 files / 190 tests, tsc, prettier green — the broad
  listener broke no existing click path.
- 2026-10-23 (cycle 24 checkpoint): no refactoring suggested (Escape and the outside click
  repeat three lines at two sites; not yet a name).
- 2026-10-23 (workspace, cycle 25, controls act as themselves): new test — Play plays with no
  pause from the outside rule; Pause while playing pauses exactly once; a click into the speakers
  panel's name field pauses nothing. Green on arrival (the listener ignores controls); red via a
  reverted mutation (`button` dropped from `CONTROLS`): `expected "pause" to be called 1 times,
  but got 2 times` at `TaskTranscriptView.test.tsx:480`. Commit `6527ad0d`; vitest 36 files /
  191 tests, tsc, prettier green. The Escape/click item is ticked (downloads are links, in the
  same selector; their clicks are unchanged and the journey's download specs cover them).
- 2026-10-23 (cycle 25 checkpoint): no refactoring suggested (the pause pair still has two
  sites); no discoveries.
- 2026-10-23 (workspace, cycle 26, the button hands focus over): new test — a click on Play
  calls `play()` and leaves focus on segment 1 (current at 0 ms); Space with that focus pauses.
  Red: `expected <body><div>…(1)</div></body> to be <span role="button" …(4)></span>` at
  `TaskTranscriptView.test.tsx:500`. Green: the player's `onToggle` goes through
  `toggleFromButton` — toggle, then focus the current segment (before one, the first); the
  segment keys keep `togglePlayback`, which leaves focus alone. Prettier reformatted the new
  query chain. Commit `7c2082dc`; vitest 36 files / 192 tests, tsc, prettier green. The
  Play-button-focus item is ticked.
- 2026-10-23 (cycle 26 checkpoint): no refactoring suggested (the two toggles differ only in the
  focus step, which is the point); no discoveries.
- 2026-10-23 (workspace, cycle 27, controls only while paused): new test — paused at 0 ms
  segment 1 carries the Edit control; after Play none is rendered, nor as the playhead runs into
  segment 3; after Pause the control returns after segment 3. Red: the control still rendered
  while playing, `TaskTranscriptView.test.tsx:526`. Green: `TranscriptTurns` takes `playing`
  and renders the control only for the current segment while paused (`playback.matches
  ('playing')` from the view). Commit `97de07f4`; vitest 36 files / 193 tests, tsc, prettier
  green. The controls item is ticked.
- 2026-10-23 (cycle 27 checkpoint): no refactoring suggested (one prop, one condition); no
  discoveries.
- 2026-10-23 (workspace, cycle 28, the arrow keys): new test — focus on segment 1 (current,
  paused, its control after it): ArrowRight → segment 2, ArrowDown → segment 3, ArrowUp →
  segment 2, ArrowLeft → segment 1; nothing played or seeked and no hand scroll ("Back to
  playing position" absent). Red: ArrowRight left focus on segment 1,
  `TaskTranscriptView.test.tsx:552`. Green: with focus on a segment, a new `ARROW_STEP` map
  moves the staging focus between segments (skipping the controls), `preventDefault`ed and
  never a hand scroll; elsewhere the scroll keys behave as before. The segment selector became
  the module's `SEGMENTS` (three uses). Commit `b3c5bf44`; vitest 36 files / 194 tests, tsc,
  prettier green. The arrow-keys item is ticked.
- 2026-10-23 (cycle 28 checkpoint): no refactoring suggested (the selector extraction came with
  the cycle); no discoveries. The user's keyboard-trial model is now implemented end to end in
  unit tests; the e2e trial item remains for the user's browser run.
- 2026-10-23 (workspace, the staging-focus cycle, the user's trial discovery): a staging focus
  is a scroll away, so following stops. Updated cycle 28's test (its arrows now stop following)
  and a new test: follow scrolled at load and into segment 3 (2 calls); focus on segment 4 while
  segment 3 is current shows "Back to playing position"; the playhead running into segment 4
  scrolls nothing more. Red: both tests found no Back button (arrow test :563, new :581). Green:
  a native `focusin` listener on the transcript region — focus on a segment that is not current
  sends SCROLLED; the view's own focus moves all land on the current segment, so they never
  stop it. Two sandbox findings on the way: React's synthetic `onFocus` never fires for jsdom's
  `element.focus()`, so the listener is native (jsdom does dispatch `focusin`); and a raw
  `focus()` in a test needs `act()` for the re-render it causes, or the assertions read stale
  DOM. Commit `b90bb612`; vitest 36 files / 195 tests, tsc, prettier green.
- 2026-10-23 (user report, filed in `docs/known-issues.md`, Frontend MED, commit `04b66fd2`): a
  transcribed song produced a segment that only the keyboard can reach — not clickable, and
  playing it jumps to the next segment, so it can never be current. Reads as a blank or
  zero-length segment: the transcript's segments are their text alone (no hit area when blank),
  and the current segment is the last one starting at or before the playhead. `/turns` keeps
  them (only trimming), and whisper output can carry them (`ParseSegments` defaults missing
  offsets to 0 and text to ""). A fix direction is open (placeholder with a hit area and a way
  to make it current, versus dropping/merging blanks before storage — oracle parity), so it is
  not yet a test-list item.
- 2026-10-23 (user follow-up to that report): the segment is not blank — it is whisper's
  consecutive duplicate at the next segment's start (also filed as a Backend MED known issue,
  commit `7a65412b`: nothing deduplicates whisper's output). The user decided the view side:
  the chosen segment wins at its own instant, and a text-less segment shows a grayed-out
  `[empty]`. Both recorded in the Decided section with their test-list items
  (commit `9450615f`).
- 2026-10-23 (workspace, cycle 30, the shared start): new test with a mocked `/turns` whose turn
  at 9600 ms carries "Ghosted by the bar" `[9600,9600)` before "Let's start with your
  background." `[9600,14250)`: a click seeks to 9.6 s and the duplicate is current with its Edit
  control while the neighbour is not; Enter plays it, and at 9.7 s the neighbour takes over.
  Red: the duplicate never got `data-active` (`TaskTranscriptView.test.tsx:619`). Green: the
  view remembers `chosenSegmentId` (set by click, double-click, Enter/Space) and lets it be
  current while the playhead stands at its own instant — exactly, for a zero-length segment.
  The first version's zero-length test let it hold at any later time; the test's second half
  caught it. Commit `940232ef`; vitest 36 files / 196 tests, tsc, prettier green. Ticked.
- 2026-10-23 (workspace, cycle 31, the [empty] placeholder): new test — a mocked `/turns` with a
  whitespace-only segment: it renders as a button named `[empty]` in `textMuted`, a click seeks
  to it and makes it current with its Edit control, and the txt download link is untouched.
  Red: no such button (`TaskTranscriptView.test.tsx:650`). Green: `TranscriptTurns` renders
  `EMPTY_TEXT` for a segment whose text trims to nothing and greys it (`$empty`); the
  placeholder exists only in the view. Commit `2c7c4ca8`; vitest 36 files / 197 tests, tsc,
  prettier green. Ticked. Checkpoint: no refactoring suggested (one condition and one token
  colour); no discoveries.
- 2026-10-23 (the user tried the first editor, mock UI): three findings — no way out (playing
  left it open), the multiline field was ugly, and the full-size Save button raised the line and
  sat before the following text. Decisions recorded in the Decided section: playback disabled
  while editing; the editor is a small block box with Save, Revert and Discard inside it; one
  line only, Enter/Ctrl+Enter save, Shift+Enter nothing, pasted breaks become spaces; Escape or
  Discard close without saving; a click anywhere else leaves the editor open; no confirm dialog;
  and Revert restores whisper's original (ADR 0060). The sentence of the same day that edits
  inside the box's field cannot be multiline replaces the earlier "line break becomes a space on
  save" rule.
- 2026-10-23 (workspace, cycle 32, the editor opens and saves): new test — Edit opens a textarea
  named "Segment text" holding the stored text; Save sends
  `PUT /api/tasks/3/segments/2 {"text":"Thanks, everyone."}`, the view shows the answer's text
  and closes the editor, with no `/turns` refetch. Red: no such field
  (`TaskTranscriptView.test.tsx:687`). Green: `client.editSegment`, a `SegmentEditor` with its
  own draft, and the view applying the answer through the machine's `SEGMENTS` event (text
  trimmed). Test corrections: the PUT assertion awaits the async request, and a mock route for
  the edit was added so `dev:mock` works too (`097134bc`). Commit `0f4b8668`; vitest 36 files /
  198 tests, tsc, prettier green.
- 2026-10-23 (workspace, cycle 33, no newlines): new test — Shift+Enter sends nothing and leaves
  the editor; `\n` typed or pasted becomes a space in the field; Enter saves and closes;
  reopening on the edited text, Ctrl+Enter saves too. Red: the pasted break survived (`:731`).
  Green: `rows={1}`, the change handler maps `\r\n|\r|\n` to a space, Enter is intercepted
  (saves unless Shift). Reopen helper correction: it clicks the segment's current text. Commit
  `9e7260dc`; 199 tests green.
- 2026-10-23 (workspace, cycle 34, Discard and Escape): new test — Discard closes, keeps the
  stored text and sends nothing; a click on the region's own area keeps the editor and draft
  ("Still typing"); Escape from the field closes without a request. Red: no Discard button
  (`:768`). Green: the editor's Discard button and Escape handling; the outside-click listener
  returns early while an editor is open (deps `[playback, editingSegmentId]`). Commit
  `5152249c`; 200 tests green. Decisions recorded: an open editor ignores outside clicks; no
  "are you sure?" dialog (`6ab3d5b9`).
- 2026-10-23 (workspace, cycle 35, the player disabled): new test — with the editor open the
  Play button is disabled and a click never calls `play()`; after Discard it is enabled. Red:
  `toBeDisabled` (`:797`). Green: `AudioPlayer` takes `disabled` and hands it to the icon
  button; the view passes `editingSegmentId !== null`. Commit `0b988d85`; 201 tests green.
- 2026-10-23 (workspace, cycle 36, the editor's box): new test — the field's parent is a
  block-level flex box with a solid border holding both buttons, each at the small font size.
  Red: `'inline'` vs `'flex'` (`:816`). Green: `Button` gains `size="sm"` (padding
  `xs`/`sm`, small font token) and `SegmentEditor` renders `EditorBox` with the field
  `flex: 1`; the `font` shorthand in `Button` became longhands because a jsdom CSSOM rule
  ignores a `font-size` after the shorthand (a backtick inside the styled template briefly broke
  the build). Commit `b8d3c4fc`; 202 tests green; `vite build` green.
- 2026-10-23 (workspace, the revert feature, ADR 0060): ADR written and indexed; contract gains
  `edited` on every segment (`docs/api/turns*.json`, `segment-edited.json`, the route table,
  the HTTP spec) and `POST …/segments/{segmentId}/revert`; migration `V13__segment_original_text`
  adds `original_text`, backfilled from `text` (V12 is the Java migration); inserts keep the
  first write; `revertSegmentText` copies it back; `ProcessingEngine.revertSegment` emits
  `segment-edited`; the route answers the updated turns with the edit's refusals. New
  `SegmentRevertSpec`: edit, then `edited` true and the txt download holds the correction and
  not whisper's line; revert, then the answer holds the original with `edited` false, the
  download holds whisper's line and not the correction, and the row stores the original. Red:
  Flyway first (V12 taken), then the contract had no `edited`; green after the seed gave its
  task a speaker. 15 existing assertions followed the contract (V13 count, `originalText` on
  compared rows, `edited` in expected turns — true only where edited). Commit `b0f52c1a`;
  `gradle check` green (323 tests).
- 2026-10-23 (workspace, the Revert button): new test — an unedited segment's editor has no
  Revert; an edited one has it, and pressing it posts to the revert route, shows whisper's text
  in place and closes the editor. Red: no Revert button (`:857`). Green: `edited` in the
  frontend types, `client.revertSegment`, the editor's Revert (small, secondary) and the view's
  handler applying the answer; the mock gained the revert route and now marks a mocked edit
  `edited`. Commit `a5928fb4`; 203 tests green.
- 2026-10-23 (workspace, unchanged save): user rule recorded — a draft trimmed equal to the text
  the editor opened with is not a save; new test — Save and Enter on an untouched or
  whitespace-padded draft close the editor and send nothing. Red: a request was sent (`:888`).
  Green: `commit()` in the editor closes instead of saving when the trimmed draft is unchanged.
  Commit `fa07f6e6`; 204 tests green. Ticked; the rejected-save behavior is now its own unticked
  item.
- 2026-10-23 (workspace, rejected save): new test — a 400 `invalid-segment-text` from the PUT
  leaves the editor open with the draft, shows the server detail in a `role="alert"` line inside
  the box, and Discard keeps the stored text. Red: no alert (`TaskTranscriptView.test.tsx:917`).
  Green: `editError` in the view (set from `errorMessage`, cleared on open/discard/success) is
  passed through `TranscriptTurns` to the editor, which prints it on its own line in `danger`.
  Commit `70cf17e3`; vitest 36 files / 205 tests, tsc, prettier green. Ticked.
- 2026-10-23 (workspace, isolated segment playback): new test — with segment 2 current the
  Play-segment control seeks to 2.4 s and plays; at 5.0 s it is still going and at 5.2 s (the
  segment's end) it pauses itself. Red: no such control (`TaskTranscriptView.test.tsx:938`).
  Green: a `windowEndMs` in the view (set by the control, cleared on stop), the player's
  onPlayhead pausing at it, and a Play-segment `SegmentControl` beside Edit while paused.
  Commit `9fc0e699`; 206 tests green.
- 2026-10-23 (the user's question, recorded above): the preview also sits inside the editor
  box before Save, so a segment can be replayed while its text is corrected; the editor and the
  draft stay, the main Play/Pause stays disabled. New test — the editor is open with a draft,
  the box's Play-segment seeks to 2.4 s and plays, and the draft is still there. Red: no
  control in the box (`:964`). Green: `onPreview` in `SegmentEditor`. Commit `e690e7b5`;
  207 tests green. Ticked.
- 2026-10-23 (workspace, the leading silence): new test — a transcript whose first segment
  starts at 800 ms: Play pressed at 0 lands on 0.8 s and makes that segment current; the seek
  bar released at 0 s lands on 0.8 s too. Red: playback started at 0 (`:997`). Green: `seek`
  clamps a target before the first segment to its start, and resuming playback does the same
  before `play()`. Commit `24bbbee2`; 208 tests green. Ticked.
- 2026-10-23 (refactor checkpoint, asked for by the user after several cycles went by without
  one): four approved refactors, each its own commit, all tests unchanged —
  `7554bd24` one answer path (`settleEdit`) for edits and reverts (they repeated the
  find/apply/close/clear block); `813eb4f0` one reading of the first segment's start, moved
  above its users; `0414a315` the editor comment describes what it is (one line, three buttons,
  preview, error line); `47944761` test helpers `openSegmentEditor` and `turnsWithSegmentTwo`
  replace four open-editor closures and three hand-built fixtures. Vitest 36 files / 208 tests,
  tsc, prettier green.
- 2026-10-23 (workspace, after the end): new test — the playhead past the end (15 s, the last
  segment still current) and the element ended: Space on that segment plays once and puts the
  player at 9.6 s (its start), not at 0. Red: it stayed at 15 s (`:1023`). Green: resuming
  playback restarts the current segment when the element has ended, before the leading-silence
  clamp. Commit `dbec7658`; 209 tests green. Ticked.
- 2026-10-23 (workspace, the editor's direction): `dir="auto"` on the field is the whole
  implementation (the browser resolves the first strong character; a letterless segment falls
  back to the inherited direction, and the editor's account is the only thing that carries
  direction — the segment itself has none). New test pins the attribute on the open field and
  that the segment carries no `dir` once the editor closes; jsdom does not resolve `auto`, so
  the browser check stays with the e2e journey. Red: no attribute (`:1034`). Commit
  `0bd84de2`; 210 tests green. Ticked.
- 2026-10-23 (refactor checkpoint after the end-of-audio cycle): approved and done as
  `6bd4cbb0` — `settleResumePosition(audio)` names the two resume rules (ended → the current
  segment; leading silence → the first), so the play branch reads "settle, play".
- 2026-10-23 (workspace, the staging frame): new test — focus on a segment that is not current
  leaves it unfilled and the current segment filled, with no outline of ours (the only mark is
  the one fill; focus is the browser's ring). Green on arrival; red via a reverted mutation (a
  `:focus` accent fill): `expected 'rgb(0, 87, 168)' not to be 'rgb(0, 87, 168)'`.
  Commit `e5a79a4e`; 211 tests green. The first highlight item is ticked: its gap rule came
  in cycle 12 and its staging frame here.
- 2026-10-23 (workspace, the e2e edit journey): new spec `e2e/tests/52-edit-journey.spec.ts` —
  transcribe the clip, open it, pick the segment whose line occurs exactly once in the txt
  download, edit it to a marker, Save, assert the view and the download carry the marker and not
  that line, then Revert and assert the reverse both ways. The user's first run failed at
  `getByLabel('Segment text')`: the harness serves `frontend/dist` and never builds it, so an
  older UI (pencil, no editor) ran against the freshly compiled backend — filed in
  `docs/known-issues.md` (Tests, MED, `059538f5`). After `pnpm build` the spec passed in 12.3 s
  (`1 passed`). Commit `7bf37790`; ticked.
- 2026-10-23 (workspace, the user's keyboard-trial spec): `e2e/tests/52-keyboard-trial.spec.ts` —
  Play hands focus to the current segment; Tab moves to another segment (not the player, not the
  segment's controls while playing); Space starts playback on the staged segment and the second
  Space pauses with focus kept; after Tab moves off the current segment, Escape returns focus to
  it. Commit `6f6e5705`. The user ran both new specs green: `2 passed (27.2 s)` — the journey
  in 13.0 s and the trial in 1.0 s. Ticked.
- 2026-10-23 (must not break, first full e2e run by the user): 39 passed, 1 skipped (the macOS
  Metal spec), 4 failed — `06-playback`, `15-screenshots`, `17-follow-mode`, `59-header` — all
  on one strict-mode violation: `getByRole('button', { name: 'Play' })` matches by substring, so
  it also found the new "Play segment" control. Fixed in the specs with `exact: true` (the
  player's Play is meant; the keyboard trial already scopes to the player group), commit
  `364374e6`. The contract-shape check (`14-contract`, `turns*.json` with `edited`) and both
  new item-52 specs passed in that run. Backend `gradle check` and the frontend gates were green
  at `b8cdb54f`. The item stays open until the user's rerun of the whole suite.
- 2026-10-23 (must not break, the user's rerun after `364374e6`): the whole e2e suite green —
  43 passed, 1 skipped (the macOS Metal spec). The line-break item is struck as superseded by the
  no-newlines item, and the known-issues row for unreachable blank/zero-length segments is
  removed (handled by the two user-report items above). The user's own trial with `devenv up`
  then gave five review points on isolated playback and the editor (dark-mode field text, focus
  on open, other segments choosable while editing, the window's end, a Stop for the preview);
  they become discovered items, so "(must not break)" stays open for a final rerun.
- 2026-10-23 (the user's review, decisions): every stop of a segment played on its own is a Stop
  — back at the segment's start, the segment still current (its end, and Space, Enter, Escape or
  a click on no control while it plays). The editor becomes a centered dialog over a shade that
  spares the header; the header stays usable and leaving the transcript drops the draft; the
  title names the speaker and time range; the field wraps over several rows. Recorded in the
  Decided section; the box item is struck, the Play-segment item amended, eleven discovered
  items added. The user's note: Escape stops closing the current editor once another segment is
  clicked; not fixed, the dialog removes the cause (focus cannot leave it).
- 2026-10-23 (workspace, review cycle A1, the window's end): new `TaskTranscriptView` test —
  segment 2 [2400,5200) played from its Play-segment control; the first time update past its end
  lands at 5.9 s, inside segment 3 [5800,…) as a coarse `timeupdate` can. Expected (from the
  user's rule): one pause, playhead 2.4 s, segment 2 current with its control, segment 3 not.
  Red: playhead stayed at 5.9 (`:955`). Green: the isolated play remembers its segment
  (`windowSegment` replaces `windowEndMs`); at its end the view pauses, seeks to the segment's
  start and chooses it, without passing the late playhead on. Commit `132fe43a` (it also carries
  the decisions above); 212 tests green, tsc, prettier, vite build.
- 2026-10-23 (review cycle A1 checkpoint): refactor 2 approved — a `playheadAt(audio, seconds)`
  test helper replaces the 34 "set `currentTime`, fire `timeupdate`" pairs (`14b0096e`, 212
  green). Refactor 1 (a named stop next to `playSegmentWindow`) deferred to this cycle's
  checkpoint.
- 2026-10-23 (workspace, review cycle A2, stopping partway): new `TaskTranscriptView` test — segment
  2 [2400,5200) played on its own, stopped at 3.6 s five ways in turn: Space and Enter on the
  segment, Escape, a click on the region's own area, the player's Pause (the user's answer: "any
  stop of a segment played on its own goes back to its start"); each time one more pause, the
  playhead at 2.4 s, segment 2 current; then Space plays again from 2.4 s. Red: Space paused at
  3.6 (`:980`). Green: one `pausePlaying` — pause, and for a segment played on its own seek back
  to its start and choose it — used by `togglePlayback` (Space/Enter, the player's button), Escape
  and the click on no control. The window's end keeps its own copy until the refactor
  checkpoint. Commit `58ad3521`; 213 tests green, tsc, prettier, vite build. Discovered by
  reading the code: the isolated play outlives a jump elsewhere — clicking a later segment while
  it plays seeks past the old segment's end, so the next time update stops playback and rewinds
  to the old segment. Added as a discovered item, to confirm.
- 2026-10-23 (review cycle A2 checkpoint, user decisions): refactor 1 approved — one
  `stopSegmentWindow` for the window's end and a stop partway (`09550276`, 213 green). The
  user's **Pause/Stop model**: a Pause stays where it stopped (the player's button, Space/Enter
  on the current segment); a Stop ends playback at the current segment's start (Escape, a click
  on no control, the segment's own Stop control — a square while it plays on its own — and a
  segment's end). Including the player's Pause in A2 was a misreading of "any stop"; A2's item
  and test are reworked, the Escape/click items amended (they now stop ordinary playback too —
  a new item), and two new items added: the square Stop control, and the confirmed "choosing
  another segment or seeking ends playing it on its own".
- 2026-10-23 (workspace, review cycle A2 reworked): the A2 test now plays segment 2
  [2400,5200) on its own three times and pauses it at 3.6 s by Space, Enter and the player's
  button: one pause, the playhead stays at 3.6 s, segment 2 current; the same key resumes at
  3.6 s and a time update at 5.3 s stops it back at 2.4 s. Then Escape and a click on the
  region's own area each stop it at 3.6 s back at 2.4 s, and Space plays from 2.4 s. Red: Space
  stopped at 2.4 instead of pausing at 3.6 (`:982`). Green: `togglePlayback` pauses in place
  again and keeps the isolated play; only Escape and the click stop. Commit `9da9eb14`; 213
  tests green, tsc, prettier, vite build.
- 2026-10-23 (reworked A2 checkpoint): renaming `pausePlaying` waits for this cycle's checkpoint;
  the A2 test is split in two — the pause ways and the stop ways — with a shared
  `playOnItsOwnUntil(audio, seconds)` helper (`8d3b3393`, 214 green).
- 2026-10-23 (workspace, review cycle: Escape/click stop ordinary playback): new
  `TaskTranscriptView` test — playing from segment 2 (double-click), run on to 7 s inside
  segment 3 [5800,9100), then Escape, and again a click on the region's own area: each time one
  pause, the playhead at 5.8 s, segment 3 current and focused; Space then plays from 5.8 s.
  Expected from the user's model. Red: Escape left the playhead at 7 s (`:510`). Green: the
  Escape/click path, when no segment plays on its own, pauses and seeks to the current
  segment's start, choosing it; the click listener's effect also tracks the current segment.
  Commit `9471c4c5`; 215 tests green, tsc, prettier, vite build. The e2e keyboard trial presses
  Escape only while paused, so it is unaffected.
- 2026-10-23 (Escape/click stop checkpoint, user decisions): all three refactors approved, with
  the rule "Pause means pause, stop means stop" across code, tests, comments and docs. (1)
  `144940b6`: `pausePlaying` → `stopPlaying`; the playback machine's `paused` state becomes
  `idle` (events `PLAYING`/`IDLE`) — idle is any time nothing plays, the element reporting a
  Pause and a Stop alike as `pause`; comments in `TaskTranscriptView`, `TranscriptTurns` and
  `AudioPlayer`; the spec `frontend-history-ui.md` gains the Pause/Stop amendment; this file's
  Decided section and test list say "not playing" where nothing plays and "stop" where Escape and
  the click stop (the approval log stays as written). (2) `e58ab84e`: one `stopAt(segment)` for
  every Stop — `stopPlaying` stops at the segment playing on its own, or else the current one,
  and the segment's end stops at its own. (3) `2744fb59`: test names and comments ("stops on
  Escape…", "stops on a click…", "while not playing", "only while nothing plays"). Follow mode's
  "stops following" is a different stop and stays. 215 tests green, tsc, prettier, vite build,
  the docs checks. The user's question: the segment's Play control hides whenever anything plays
  (`TranscriptTurns.tsx:329`); choice (a) — only a segment playing on its own shows it, as a
  square Stop; Edit stays hidden; ordinary playback shows none. Recorded in the Decided section.
- 2026-10-23 (user answers after the refactors): `idle` for "not playing" approved; follow mode's
  "stops following" is the autoscroll giving up on a scroll away (a hand scroll or a staging
  focus), untouched. The Stop must sit exactly where the Play-segment control was, so a second
  click without moving stops it; the user then chose to keep Edit beside it **disabled** (grayed
  out, not clickable) rather than hidden. The Decided section and the Stop item say so; a new e2e
  item checks that the Stop takes the Play-segment control's box.
- 2026-10-23 (workspace, review cycle: choosing elsewhere ends playing on its own): new
  `TaskTranscriptView` test — segment 2 [2400,5200) played on its own, at 3 s, left four ways: a
  click, a double-click and Enter on segment 4 (then a time update at 10 s), and the seek bar's
  ArrowLeft to 2 s (then 5.3 s, past segment 2's end). Expected each time: no pause, the
  playhead where the time update put it. Red: the click on segment 4 stopped playback (`pause`
  called once, `:1099`). Green: `seek` ends a segment played on its own; `playSegmentWindow`
  sets its segment after its own seek. Commit `43f1ac4f`; 216 tests green, tsc, prettier, vite
  build.
- 2026-10-23 (checkpoint): refactor approved — `stopAt` no longer clears the segment playing on
  its own itself; its `seek` does (`3e3d7f44`, 216 green).
- 2026-10-23 (workspace, review cycle: the square Stop): new `TaskTranscriptView` test — segment
  2 played on its own, at 3.6 s: a "Stop segment" control after it holding lucide's square, no
  "Play segment"; Edit present with `aria-disabled`, in the muted text color, and a click on it
  opens no editor; Stop then stops (one pause, playhead 2.4 s, segment 2 current), and Play
  segment and an enabled Edit return. Red: no "Stop segment" control (`:1120`). Green: the
  view passes the segment playing on its own (`playingOnItsOwnId`, only while playing) and
  `stopPlaying` as `onStopSegment`; `TranscriptTurns` keeps that segment's controls while it
  plays, in a new `SegmentControls`: one control that is Play or Stop (same element, same
  place), and Edit disabled — `aria-disabled`, out of the Tab order, no click handler, the
  muted and default-cursor style of the other disabled controls. The spec's Pause/Stop
  amendment names the Stop control and "choosing elsewhere ends playing on its own". Commit
  `17b281f3`; 217 tests green, tsc, prettier, vite build.
- 2026-10-23 (square Stop checkpoint): refactor approved — `playing` computed once in
  `TaskTranscriptView` (`a7400054`, 217 green).
- 2026-10-23 (workspace, review cycle: the Stop's box, e2e): a second test in
  `e2e/tests/52-edit-segment.spec.ts` — reads `/turns` for the open transcript, clicks its
  longest segment (so it still plays at the second click), takes the Play-segment control's box
  and mouse-clicks its center; the Stop must have the same box (x, y, width, height within
  0.5 px) and Edit `aria-disabled`; a second mouse click at the same point stops — audio paused,
  at the segment's start (within 5 ms), Play back in place. Commit `064ff92b`. The user ran it
  after `pnpm build`: `2 passed (50.3s)` (in their clone's `workspaces/77-upload-spec-shared-temp-dir`
  directory, whose copy carries this spec and the Stop control). Green on arrival — the app code
  came with the unit cycle; the user accepted the green run without a mutation red.
- 2026-10-23 (review cycle B, the field's text color): a unit test is blind here — jsdom gives a
  textarea no user-agent `fieldtext` color, so `EditorArea` computed the inherited color and the
  test passed before any fix (deleted, not committed). The red is the user's own observation in
  the dark theme (black on dark gray, the review that opened this cycle); the pin is an e2e test
  in `52-edit-segment.spec.ts` — the header's theme button steps to dark, the first segment's
  editor opens, and the field's computed color must equal the segment text's (and not the
  browser's black). Green: `EditorArea` takes `color: inherit`, so the field shows the theme's
  text color. Commit `13af192f`; 217 unit tests green, tsc, prettier, vite build; the e2e run is
  the user's.
- 2026-10-23 (review cycle B, the user's run): `52-edit-segment.spec.ts` with all three tests —
  the control height, the Stop's box and the field's dark-theme color — `3 passed (31.6s)`, in
  `workspaces/52-edit-transcription` this time, after `pnpm build`.
- 2026-10-23 (workspace, review cycle C): new `TaskTranscriptView` test — opening the editor:
  `document.activeElement` is the field and `selectionStart`/`selectionEnd` are its value's
  length. Red: focus stayed on the page (`:1253`). Green: `SegmentEditor` keeps a ref on the
  field and, on mount, focuses it and puts the caret after the last character. Commit
  `8710aff3`; 218 tests green, tsc, prettier, vite build.
- 2026-10-23 (workspace, review cycle D1, the dialog): the editor renders in an `EditDialog` —
  a portal to the body (outside the transcript region), a shade over the page at
  `colors.overlay` under the sticky header, a panel at the middle of the viewport, `main`
  marked `inert` and `body.overflow: hidden` while it is open, Tab cycling through the dialog's
  own controls and Escape discarding from anywhere in it. The segment keeps its text and hides
  its controls while the editor is open (the dialog is the only active thing); the superseded
  inline-box test is deleted. New `TaskTranscriptView` test: `role="dialog"` with
  `aria-modal`, the field inside it, the dialog outside the region, `main` `inert`, the body's
  scroll locked, Tab from Discard to the field and Shift+Tab back, and all of it undone on
  close. Red (temporary mutation: the editor rendered without the dialog): "Unable to find an
  accessible element with the role \"dialog\"". A Radix dialog (`ConfirmDialog`'s base) was not
  used: a modal blocks the header, which the user keeps usable. The design-value check required
  the `overlay` token and `sizes.pageMaxWidth` over literals. Commits `ce42aceb`; 218 tests
  green, tsc, prettier, vite build. e2e: the journey now works inside the dialog; a new
  `52-edit-segment` test clicks under the shade (dialog stays, no playback, no playhead move) and
  wheels over it (nothing scrolls) — for the user to run. The spec's amendment names the dialog.
- 2026-10-23 (user's revision, right after D1): "maybe blocking the header is not a big deal.
  Let's use standard things." The hand-rolled overlay gave way to **Radix's dialog**, the base
  `ConfirmDialog` already uses: `Dialog.Overlay` over the whole page, `Dialog.Content` with a
  `Dialog.Title`, the page inert and not scrolling, focus trapped and returned to the Edit
  control on close, Escape closing it, and an outside click prevented from closing it (the
  earlier "a click on the shade leaves it open" holds, now by decision rather than by nothing
  happening). The D1 test is reworked for the modal (role, portalled out of the region, focus
  inside, Tab wrapping, Escape closing, the scroll lock released); the player test now asserts
  the player is out of reach and its button disabled; the "header stays usable" item is
  superseded; the close-focus item now says focus returns to the Edit control. The decision and
  the spec say "modal dialog over the whole page". Commit `5b77103e`; 218 tests green, tsc,
  prettier, vite build. The shade e2e test still holds (an outside click and a wheel do
  nothing).
- 2026-10-23 (workspace, review cycle D3, the dialog's title): new `TaskTranscriptView` test —
  editing segment 2 of turns.json gives the dialog the accessible name "Speaker 1 · 00:02–00:05";
  with speakers off (task 4, `turns.speakers-off.json`) segment 2 [4100,7900) gives
  "00:04–00:07" — the range alone. `EditDialog` takes the title from its caller, which builds it
  from `formatDuration` of the segment's start and end and the turn's speaker. Green on arrival
  (the code came first); red recorded by a temporary title of "Edit segment":
  `expect(element).toHaveAccessibleName()` failed. Commit `8144b641`; 219 tests green, tsc,
  prettier, vite build.
- 2026-10-23 (workspace, review cycle D4, the wrapping field): the field takes `rows={3}` (it was
  `rows={1}`), so a long segment wraps instead of scrolling sideways; the no-line-breaks rule is
  unchanged (a typed or pasted break becomes a space, Enter saves). Nothing of this is visible to
  jsdom, so the pin is an e2e test in `52-edit-segment.spec.ts`: the longest segment of the open
  transcript is edited, and the field has no horizontal overflow (`scrollWidth` within 1 of
  `clientWidth`), at least 2.5 line-heights of room and `rows="3"`. Commit `1b14b4bb`; 219
  unit tests green, tsc, prettier, vite build; the e2e run is the user's.
- 2026-10-23 (the user's e2e run, after D4): `52-edit-journey` + `52-edit-segment`, six tests —
  five passed: the journey through the dialog, the control height, the Stop's box, the wrapping
  field and the shade's blocking/scroll. One failed: the dark-theme color test, with the field at
  `rgb(232,232,232)` (the dark theme's text, correct) against a reference of `rgb(20,22,26)`.
  The test's flaw, not the app's: it took the first segment as its reference, and that segment is
  the current one — highlighted, so its text is the accent's contrast color. The dialog made the
  segment visible again while editing (before it, the segment's place held the editor, so the
  first segment was a non-current one). Fixed (`deb672a6`): the reference is the page's own text
  color (`body`), and it must not be black. A rerun of that test is the user's.
- 2026-10-23 (the user's rerun of `52-edit-segment` after `deb672a6`): `5 passed (31.7s)` — the
  control height, the Stop's box, the field's dark-theme color, the wrapping field, and the
  shade's blocking and scroll lock.
- 2026-10-23 (workspace, review cycle D5, focus on close): new `TaskTranscriptView` test — the
  editor opened on segment 2 and closed by Save, Discard and Escape: each time the dialog is
  gone, `document.activeElement` is the segment's Edit control and segment 2 is still current.
  Red (`:865`): focus fell back to `<body>` twice over — the trigger was unmounted while the
  dialog was open, and a synthetic click never focuses the span, so Radix had nothing to
  restore. Green: the segment's controls stay mounted under the modal (Radix hides the page for
  assistive tech, and the dialog itself is the only thing to touch), and `EditDialog` takes the
  close focus itself — `onCloseAutoFocus` prevented, then the current segment's Edit control is
  focused (the segment, if it has no Edit control). Commit `da770b5f`; 220 tests green, tsc,
  prettier, vite build.
- 2026-10-23 (workspace, review cycle E, the preview's Play/Stop): new `TaskTranscriptView` test —
  the dialog's preview clicked with a draft and a caret at 5: it plays the segment on its own
  (2400 ms), reads Stop with the square, the draft and the caret stay, and focus stays in the
  field; Stop pauses once at 2400 ms with the dialog, the draft, the caret and Play intact; the
  segment's end (a time update at 5.3 s) stops it back at 2400 ms with the draft still open. Red
  (temporary `previewing={false}`): no "Stop segment" control. Green: `SegmentEditor` takes
  `previewing` and `onStopPreview` — from `playingOnItsOwnId` and `onStopSegment` — and its
  control switches label and icon; `onMouseDown` prevents the default so the click never takes
  the focus (the caret is untouched). Commit `b9e7411b`; 221 unit tests green, tsc, prettier,
  vite build. A new e2e test (`52-edit-segment`) pins the same in a browser: a caret at 5,
  Play, and the field still focused with the caret at 5; Stop leaves the dialog and the text —
  for the user to run.
- 2026-10-23 (ticked): the dialog e2e item — the shade test and the journey through the dialog
  passed in the user’s runs (\`5 passed\` for \`52-edit-segment\`, the journey in the run before).
  One item stays open: "(must not break)", for the final full e2e suite.

- 2026-10-23 (the review cycles, done; the user’s trial of `devenv up` drove them): all five
  points landed — a segment played on its own stops back at its start (its end, Escape, a click,
  its square Stop); the field’s text takes the theme’s color; opening it focuses the field with
  the caret after the text; the editor is a Radix modal dialog (the page inert, no scroll, focus
  trapped and returned to the Edit control) on the segment’s speaker and time range, its field
  wrapping over three rows; and its preview is a Play/Stop that never takes the focus. Eight
  refactors, each its own commit (stopPlaying, `idle`, one `stopAt`, wording, test split, the
  redundant clear, `playing` computed once, the dialog on Radix).
- 2026-10-23 (**in review**): the user’s full e2e suite — `48 passed, 1 skipped (7.3m)`, the
  skip being the macOS Metal spec — after `pnpm build`; `14-contract` green with `edited` in
  the turns. The workspace gates: `gradle check`, `tsc`, `prettier`, `vitest` (221 tests) and
  `vite build` green. Every test-list item ticked or superseded; the item is set to
  `in-review` for the root agent to present the diff.
- 2026-10-23 (root review, five scenarios appended and approved; the user's clarification: Enter
  activates, Space must not scroll or page down). Cycle 1, the keyboard (HIGH): new
  `TaskTranscriptView` test — Enter on Edit opens the dialog; Space on Play-segment plays it
  (2400 ms) without "Back to playing position" appearing (following stays on); Enter on the Stop
  control stops it back at 2400 ms. Red: no dialog for Enter (`:890`). Green: `activationKeys`
  in `TranscriptTurns` — Enter and Space activate, preventing the default so the region's scroll
  keys never see them — on the Edit control (when enabled), the segment's Play/Stop and the
  dialog's preview. `52-keyboard-trial.spec.ts` extended: from the current segment, Tab reaches
  Edit, Enter opens the dialog with the field focused, Escape closes it back onto Edit, Tab
  reaches Play-segment and Enter plays it. Commit `2b0bc1c1`; 222 tests green, tsc, prettier,
  vite build; the e2e run is the user's.
- 2026-10-23 (cycle 1 checkpoint): refactor approved and done — `SegmentControl` is a component
  taking `label`, `onActivate`, `disabled` and `keepsFocusInField`, so every control built on it
  is keyboard-operable by construction (`54732f3b`, 222 green).
- 2026-10-23 (root review cycle 2, typing and follow mode): new `TaskTranscriptView` test — the
  editor opened, a Space and an ArrowLeft pressed in the field, Escape: no "Back to playing
  position" afterwards, and choosing segment 4 still scrolls it into view. Red: the button
  appeared (`:904`) — the dialog's keys bubbled through the React tree into the region, whose
  scroll keys took them for a hand scroll. Green: the region's `handleKeyDown` returns early for
  any event whose target lies inside `[role="dialog"]`; the dialog's own Escape is untouched
  (the field and Radix handle it). Commit `4ba6b1c6`; 223 tests green, tsc, prettier, vite build.
- 2026-10-23 (root review cycle 3, the lost edit): two new `SegmentRepositorySpec` scenarios — a
  COMPLETED task with a segment, its status set to RUNNING (a retry started), then the edit and
  the revert called directly: `SegmentWriteOutcome.NotCompleted` and the stored text unchanged.
  Red: `Unresolved reference 'SegmentWriteOutcome'` (a compile red, as the item's earlier cycles
  did). Green: `SegmentWriteOutcome` (Stored / NoSegment / NotCompleted) replaces the nullable
  return of `updateSegmentText` and `revertSegmentText`, each reading the task's status inside
  its own write transaction (`taskIsCompleted`, COMPLETED else nothing); the engine emits
  `segment-edited` only for a stored outcome; the routes answer `409 not-completed` ("left
  COMPLETED") for it, `404` for NoSegment, the turns otherwise. Not covered by a test: the
  route's 409 branch, which needs a seam between the route's own check and the engine call —
  the root review allowed the repository-level proof instead. Commit `b7824532`;
  `gradle check` green (ktlintFormat first).
- 2026-10-23 (cycle 3 checkpoint): refactor approved and done — one `respondNotCompleted` shared by
  `completedTaskOrError` and the segment routes, which takes the task's status when it is known
  and otherwise says the task "left COMPLETED" (`aa93701a`; `gradle check` green).
- 2026-10-23 (root review cycle 4, the contract docs): `docs/api/openapi.yaml` gains the
  `POST /api/tasks/{id}/segments/{segmentId}/revert` path (200 turns, 404, 409 — the PUT's
  responses) and `edited` on `TurnSegment` (`required: [id, startMs, endMs, text, edited]`).
  `segment-edited.json` already carried `edited`. Documentation only, so no behavioral red: the
  guard is the contract-shape e2e test (green in the user's runs) plus the docs checks —
  `check-links`, the architecture name and diagram checks, and `actionlint` all green. Commit
  `7fab8927`.
- 2026-10-23 (root review cycle 5, the shortcuts): new `TaskTranscriptView` test — segment 2
  focused: its Edit and Play-segment controls carry `aria-keyshortcuts` E and P; `e` opens its
  editor, whose Save and Discard underline S and D; Ctrl+S closes it (an unchanged draft, no
  request) and Alt+D discards; `p` plays segment 2 on its own from 2400 ms and a second `p`
  stops it back there. Red: no shortcuts (`:890`). Green: the region answers `e` and `p` for
  the segment that holds the focus (the focused element is that index in the ordered segments),
  `openEditor` extracted from the Edit handler; `SegmentControl` takes `keyshortcut`;
  `EditorBox` handles Alt/Ctrl/Cmd with S and D by `code` (the letter survives macOS Alt
  mangling), and the two buttons underline their letter. The user's answer: both Alt and Ctrl.
  Deviation from the approved wording, recorded: `e` and `p` act on the **focused** segment,
  not the playhead's current one — with the playhead at 0 ms the test showed `p` starting
  segment 1 while segment 2 had the focus. Commit `7c019809`; 224 tests green, tsc, prettier,
  vite build.
- 2026-10-23 (the user's browser run after the root-review cycles):
  `52-keyboard-trial.spec.ts` — `1 passed (37.0s)` — the trial now drives Edit and a
  segment play from the keyboard as well, after `pnpm build`. The other browser specs are
  unchanged since their last green run; a full suite run before the merge is the root agent's
  call. Status stays `in-review`.
- 2026-10-23 (PR prep, the user): squashed onto `main` `aff2206a` and finished. Conflicts were
  three: the segment ADR was renumbered to **0061** because item 78's ADR took 0060 on `main` (every
  segment reference now says 0061); `docs/known-issues.md` kept `main`'s rows plus this item's two
  e2e rows; `e2e/tests/06-playback.spec.ts` took `main`'s paused-time check (item 49) and this item's
  `exact: true`/segment selector and double-click wording. The item moved to
  `docs/specs/edit-transcription.md` with `Status: done`, `roadmap/README.md`'s row is `done` and
  its graph node `✓`/`:::done`, and item 78's gate link points at the spec.
- 2026-10-24 (CI, PR #59, the e2e job: 45 passed, 1 skipped, 3 failed). Two causes, both fixed
  in `2f170459`:
  - **`52-edit-journey` timed out waiting for `getByRole('button', { name: 'Save' })`.** The
    `Button` is `display: inline-flex` with a `gap`, so `<u>S</u>ave` became two flex items —
    visually "S  ave", and in Chromium the accessible name "S ave". jsdom's name computation
    concatenates without the gap, so the unit tests and the earlier browser runs were green.
    Fix: each label is one `<span>` around the underlined letter, so the button is one flex
    item and the name is "Save" / "Discard".
  - **`52-edit-segment` (the Stop's box, the wrapping field) chose its segment by text.** CI has
    no GPU ("Device probe: 0 GPU(s)") and its whisper segmentation splits the JFK clip
    differently — two segments shared a text, so one test hit a strict-mode violation and the
    other clicked a different segment than the one it measured. Fix: a
    `transcriptSegments(page)` helper reads `/turns` and returns the segment list together with
    the rendered buttons; both tests pick `buttons.nth(index)` of the longest segment.
  After the fix: 224 unit tests, `tsc`, prettier and `vite build` green; the CI rerun is the
  user's (the agent pushes nothing).
- 2026-10-24 (CI, PR #59, run 40051850332: Linux e2e and macOS e2e each 1 failed — the wrapping
  field on Linux, the Stop's box on macOS). The by-position fix above changed only the specs; the
  cause was the app. A click seeks to the segment's start, and the browser's `seeked` reported
  the playhead as `currentTime * 1000` — for 32760 ms that is 32759.999999999996 (Chromium:
  32759.999), before the start, so the previous segment became current and the Edit and Play
  controls moved to it: Edit opened the previous segment's text (Linux, reproduced locally on the
  CPU transcript: segment 4 clicked, segment 3 edited), and the Play control moved away from the
  measured box (macOS; a throwaway copy of the Stop spec aimed at the 32760 ms segment failed on
  the old build and passed on the fixed one — macOS itself is unverified). Only starts that are
  inexact as doubles trigger it, so it depends on the platform's segmentation. Fix:
  `playheadMs(audio)` in `AudioPlayer.tsx` reports the playhead in whole milliseconds (the unit
  of segment bounds and seek targets), used by the time updates, `seeked` and the
  leading-silence resume check. New `TaskTranscriptView` test "keeps a clicked segment current
  when the browser confirms the seek to its start" — red: `data-active` missing on the clicked
  segment (`:727`); green after the fix. 225 unit tests, `tsc`, prettier and `vite build` green.
