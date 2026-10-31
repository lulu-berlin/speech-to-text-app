# 73 — Reassign speakers: move selected segments to an existing or a new speaker

Status: done (roadmap item 73, approved 2026-10-31)
Depends on: —
Blocks: —

## Goal

When diarization puts distinct speakers under one id, the user can fix it: **Reassign speakers**
lets the user select individual segments — inside a turn or across turns and speakers — and move
them to an existing speaker or to a new one, the remedy that wrongly merged speakers lack today
(merge exists for the opposite case). Improving the automatic detection itself is item
[83](../../roadmap/83-diarization-accuracy.md).

## Evidence

- **User report 2026-10-14**: diarization sometimes puts distinct speakers under one id and
  sometimes splits one speaker across ids. A split has the merge remedy; an unseparated pair has no
  split tool, and re-running reproduces the same clustering for the same input.
- Improving the detection that produces these errors (the speaker count, the embedding windows,
  the clustering) is item [83](../../roadmap/83-diarization-accuracy.md) since 2026-10-29; it was this item's
  second half until then.
- Embeddings are CAM++ (`diarization/SpeakerModel.kt`); the fixed-speaker-count mode already exists
  per upload (`settings`/task options) and is the current manual workaround when the auto count is
  wrong.
- Merge exists (`POST /api/tasks/{id}/speakers/merge`, `DELETE …/speakers/merges`,
  `http/SpeakerRoutes.kt:77,95`; speaker model per ADR 0028/0034); there is no split endpoint, event
  or UI.

## Proposal

1. **Split remedy first**: `POST /api/tasks/{id}/speakers/{speakerId}/split` moves the selected turns
   (one or more) to a new speaker with a default name, under the same COMPLETED-only rule as
   rename/merge, published as an SSE event, with a UI action beside Merge. *(Superseded
   2026-10-31: the turn-level split was replaced by reassigning segments, see the amendment below.)*
2. The auto-count measurement that was step 2 here (a labelled local set, the threshold and gap
   window, a sensitivity control) moved to item [83](../../roadmap/83-diarization-accuracy.md) on 2026-10-29.

## Decided (consultation, 2026-10-28)

- **Split first, measurement second** in the same item: the missing remedy is the user-visible gap
  and has no data dependency; the auto-count work follows on the labelled set. *(Superseded
  2026-10-29: the measurement is item 83; this item is the split action only.)*
- **A split moves the selected turns** to a new speaker (default name, renameable), one or more
  turns per action and repeated splits allowed. It is COMPLETED-only (409 otherwise, 404 for an
  unknown speaker id), publishes its own SSE event, and renames, merges and split→merge keep
  working across it.

## Decided (split contract, 2026-10-29)

*(Superseded 2026-10-31 where the amendment below differs: the endpoint, the event, the error
name, the selection and the all-segments refusal. The new speaker's id and name rules and the
merge bake-in stay.)*

- **Request**: `POST /api/tasks/{id}/speakers/{speakerId}/split` with `{ "segmentIds": [<id>, …] }`.
  The UI sends the segment ids of the selected turns: a segment's assignment to a turn changes, its
  id does not, so a stale view can never move the wrong text.
- **Refusals**: `409 not-completed` on a task that is not COMPLETED; `404 not-found` for an unknown
  task, or a speaker that is unknown or merged into another; `400 invalid-split` when `segmentIds`
  is empty, names a segment the speaker does not have, or names all of the speaker's segments (that
  would leave an empty speaker; a rename does that job).
- **The new speaker** takes the task's highest speaker id + 1 (speakers hidden by a merge counted),
  named `"Speaker N"` with N = id + 1 (ADR 0028) or, when that name is taken in any letter case, the
  next free N above it, so a split never collides with a name the user gave.
- **Response and event**: `200` with Speakers (`docs/api/speakers.json`), as rename and merge; a new
  SSE event `speakers-split` with the speakers-event payload `{ id, speakers }`, published after the
  write; an open view refetches `/turns` on it, as on `speakers-merged`.
- **Storage**: the selected segments' `speaker_id` is rewritten (no migration), so a split is
  permanent and undone by merging the new speaker back.
- **Merges become permanent on a split**: a split on a task with merges first, in the same write,
  stores every merged-away speaker's segments under the speaker it was merged into and deletes the
  task's merge rows and the merged-away speakers' rows, so "Reset all merges" is gone; later merges
  create new merge rows as usual. The transcript view asks for confirmation before a split only when
  the task has merges ("Split speaker?" — "This task has merged speakers. Splitting makes those
  merges permanent: "Reset all merges" will no longer be available." — Split / Cancel).

## Decided (amendment, 2026-10-31): reassign segments, not turns

- **Why** (the user's own test, 2026-10-31): when two people share one id, their alternating
  segments form the same turns, so moving whole turns cannot separate them; and segments could only
  go to a new speaker, never to one that exists.
- **The flow** (the user): the Speakers panel has a **"Reassign speakers"** button, placed **before
  "Merge speakers…"**: reassigning is the general correction, while merging only helps when one
  person was wrongly split into two speakers. It shows whenever the task has a speaker (one speaker
  is the main case to correct). Only after it is pressed does the transcript show an instruction
  ("Select segments to move to a different speaker") and a selection box beside every segment and
  beside every turn; a turn's box selects all of that turn's segments, or clears them. With segments
  selected, the user moves them to a speaker — an existing one or a new one ("Move to…" with a
  speaker picker listing the shown speakers and "New speaker").
- **The mode's bar** (the user): the instruction, "N segments selected", "Move to…" (disabled until
  a segment is selected) and **Cancel** sit in a bar pinned to the top of the transcript, under the
  app header, so they stay in reach while scrolling a long transcript. (A phone layout might pin it
  to the bottom instead; that belongs to item [86](../../roadmap/86-mobile-feasibility.md)'s research, not here.)
- **Leaving the mode**: Cancel or **Escape** leaves it with nothing changed (while the picker or the
  confirmation is open, Escape closes that first); a successful move ends it; leaving the page ends
  it with nothing changed.
- **The mode does only one thing** (the user): while it is on, nothing else on the page works — no
  playback (Play, segment play, seeking by double-click or Enter/Space), no segment editing or
  reverting, no renaming, no merging or resetting merges, no other task action. Entering the mode
  pauses playback.
- **Any selection** (the user): one move may take segments of several speakers.
- **An emptied speaker is removed** (the user): a speaker left without segments loses its row and
  name. The new speaker's id is still the highest id over all the task's speaker rows + 1, read
  before anything is removed, so no id is reused; its name stays the first free `"Speaker N"` from
  N = id + 1.
- **Merges become permanent on every move** (the user): on a task with merges, a move first bakes
  them in, in the same write, and the view asks first ("Reassign speakers?" — "This task has merged
  speakers. Reassigning makes those merges permanent: "Reset all merges" will no longer be
  available." — Move / Cancel).
- **One task-scoped endpoint and one event** (the user), replacing the split endpoint,
  `speakers-split` and `invalid-split` (nothing has merged yet): `POST
  /api/tasks/{id}/speakers/reassign` with `{ "segmentIds": [<id>, …], "speakerId": <id> | null }`,
  `null` meaning a new speaker; `200` with Speakers; the SSE event `speakers-reassigned` with
  `{ id, speakers }`, published after the write. Refusals: `409 not-completed`; `404
  not-found` for an unknown task or a target speaker that is unknown or merged away; `400
  invalid-reassignment` for an empty selection, a segment the task does not have, or a move that
  changes nothing (every selected segment already belongs to the target).
- **The selection holds segment ids**: a segment keeps its id when the turns are
  re-read, so a reload while selecting (another client's change) keeps exactly the segments the
  user ticked, and can never select one the user did not. This replaces the turn-index selection
  and its list binding (review round 2).
- **Out of scope**: a speaker change inside one whisper segment — splitting a stored segment is item
  [83](../../roadmap/83-diarization-accuracy.md)'s question.

## Test list (approved; behaviors only)

The ticked items below describe the turn-level split built before the 2026-10-31 amendment. Where
the amendment changes a behavior (the endpoint, the event, the error, the selection, the
all-segments refusal), the amendment's items replace them; the rest carry over.

- [x] A split action on a COMPLETED task moves the selected turns to a new speaker, persists, and
      the view updates in place; a further split works, a non-COMPLETED task answers 409 and an
      unknown speaker id 404 (cycles, 2026-10-29):
  - [x] 1a. A split on a COMPLETED task stores the selected segments under a new speaker
        `"Speaker N"` and leaves the other segments and speakers unchanged; `/turns` then shows it
  - [x] 1b. A further split creates the next speaker id
  - [x] 1c. A non-COMPLETED task answers 409 `not-completed`; an unknown or merged-away speaker
        answers 404 `not-found`; an invalid selection answers 400 `invalid-split`
  - [x] 1d. A split publishes `speakers-split` after the write
  - [x] 1e. In the transcript view the user selects turns and splits them; the open view updates
        in place on `speakers-split`
- [x] Merging after a split still works (split → merge round-trips)
- [x] Renamed speakers keep their names across a split, and a split speaker can be renamed
- [x] (must not break) The existing rename/merge, fixed-count and diarization suites stay green
- [x] (e2e) On a completed task with the turns Speaker 1 → Speaker 2 → Speaker 1, the user selects the
      last turn and splits it off: the view shows Speaker 1 → Speaker 2 → Speaker 3 without a reload,
      the split survives a reload, and renaming Speaker 3 relabels that turn
- [x] (discovered) A split on a task with merges first makes the merges permanent (merged segments
      stored under their survivor, the merge rows and merged-away speakers removed, "Reset all
      merges" gone); the transcript view asks for confirmation before such a split
- [x] (discovered) When the new speaker's default name is taken (any letter case), the split names
      it with the next free `"Speaker N"` above it
- [x] (discovered) A segment the view shows under a survivor, because its speaker was merged away,
      can be split as that survivor's: the merge bake-in happens before the selection is checked
- [x] (discovered) A rejected split (`400 invalid-split`) on a task with merges leaves the merges
      intact and nothing baked (asserted inside 1c's scenario)
- [x] (discovered) The e2e contract check covers `speakers-split` against
      `docs/api/speakers-split.json`, and the e2e capture helper records it
- [x] (review 1, 2026-10-30) A split never reuses a merged-away speaker's id: the new speaker
      takes the task's highest speaker id + 1 over all its rows, before the bake-in
- [x] (review 2, 2026-10-30) A reload of the turns drops the ticked turns, so no stale index can
      address another turn
- [x] (review 3, 2026-10-30) The Split action's label matches its flow: "Split selected turns", with
      the confirmation only when the task has merges
- [x] (review 4, 2026-10-30) Cancelling the confirmation clears a previous refusal's message
      (unreachable as filed: SPLIT already clears it; the visible rule is pinned by a test)
- [x] (review 5, 2026-10-30) The header scenario's scroll-to-top is accepted (log line only)
- [x] (review round 2, 2026-10-31) A tick made while the turns reload does not survive into the new
      turns: no tick made against one turn list counts against another
- [x] (review round 2, 2026-10-31) The reload test's split-elsewhere data is a state the backend can
      produce (test data only, a refactor)

### Amendment of 2026-10-31 (approved 2026-10-31)

- [x] (api) Moving some segments of one turn to a new speaker stores them under a new speaker (id
      and name rules unchanged), and `/turns` splits that turn around them
- [x] (api) Moving segments to an existing shown speaker stores them under it, creates no speaker,
      and that speaker's turns that become adjacent join
- [x] (api) One move may take segments of several speakers
- [x] (api) A speaker left without segments is removed, and its id is never reused
- [x] (api) A task that is not COMPLETED answers `409 not-completed`; an unknown task or a target
      speaker that is unknown or merged away answers `404 not-found`; an empty selection, a segment
      the task does not have, or a move that changes nothing answers `400 invalid-reassignment`; a
      refused move changes nothing, merges included
- [x] (api) A move on a task with merges makes them permanent first, in the same write
- [x] (api) A move publishes `speakers-reassigned` with the task's speakers after the write
- [x] (ui) "Reassign speakers" comes before "Merge speakers…" in the Speakers panel, and shows for a
      task with one speaker too
- [x] (ui) No selection boxes show until "Reassign speakers" is pressed; then a bar pinned to the top
      of the transcript shows the instruction, the count of selected segments, "Move to…" and Cancel,
      and a box appears beside every segment and every turn
- [x] (ui) Cancel and Escape each leave the mode with nothing changed; with the picker or the
      confirmation open, Escape closes that first
- [x] (ui) While the mode is on, playback, segment editing and reverting, renaming, merging,
      resetting merges and the other task actions are unavailable, and entering it pauses playback
- [x] (ui) A turn's box selects all of that turn's segments, and clears them when all are selected
- [x] (ui) "Move to…" offers every shown speaker and "New speaker"; choosing one sends the selected
      segment ids and the target, the view shows the new turns in place, and the mode ends
- [x] (ui) On a task with merges the move asks first, and Cancel sends nothing
- [x] (ui) A reload while selecting keeps the selected segments selected and selects nothing else
- [x] (ui) An open view re-reads `/turns` on `speakers-reassigned`
- [x] (e2e, ADR 0046) On a fixture with a turn of at least two segments, the user presses
      "Reassign speakers", ticks one segment inside that turn and moves it to a new speaker — the
      turn splits around it at once; then moves it to the other existing speaker — the turns join;
      a turn's box selects its segments; the result survives a reload; `speakers-reassigned`
      matches its `docs/api` example
- [x] (discovered, 2026-10-31) The split endpoint, `speakers-split` and `invalid-split` are gone
      everywhere: the route, `SplitOutcome`, `splitSpeaker`, the event, the client call and machine,
      the split UI, the split scenarios, their `docs/api` examples and every doc entry
- [x] (must not break) Rename, merge and reset, fixed-count and the diarization suites stay green;
      merging after a move and renaming a new speaker still work
- [x] (review round 3, 2026-10-31) In the reassignment mode a segment is inert: a click does not
      seek, a double-click and Enter/Space start nothing, and the text carries no button role
- [x] (review round 3, 2026-10-31) The move's target is validated inside the write transaction, so a
      target a merge made unknown refuses the move (404) instead of failing on the foreign key
- [x] (review round 3, 2026-10-31) The dead split fixture is gone from the e2e package
- [x] (review round 3, 2026-10-31) The speakers-event KDoc names `speakers-reassigned`, not the removed
      `speakers-split`

## Acceptance criteria

- Reassign speakers exists with API, event, UI, e2e and tests, per the 2026-10-31 amendment.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-23: filed from the `docs/known-issues.md` triage (user report 2026-10-14); not yet
  discussed.
- 2026-10-28: consultation with the user: split remedy first (moving selected turns to a new
  speaker), then the auto-count measurement on a local-only labelled set with only aggregate scores
  committed. Test list approved as written; status set to spec-approved.
- 2026-10-30 (amendment, the user): the detection measurement moved to item
  [83](../../roadmap/83-diarization-accuracy.md); this item is the split action only. Proposal step 2, test-list
  item 4 and the two measurement decisions left; the title, Goal and acceptance criteria were
  narrowed to match. Status stays spec-approved.
- 2026-10-29: ADR 0046 gap closed with the user: the e2e line for the split action (split the last
  turn of the two-speaker fixture to a new Speaker 3, survives a reload, rename relabels it) added
  to the test list and approved as written.
- 2026-10-29: split contract decided with the user (Decided (split contract, 2026-10-29)); item 1
  broken into cycles 1a–1e; the discovered merges-become-permanent scenario approved and added;
  first test: 1a, as a backend route test.
- 2026-10-29 (cycle 1a, a split stores the selected segments under a new speaker): new
  `SpeakerRoutesSpec` scenario — speakers 0, 1, 0, 0; speaker 0's last turn (segments 3 and 4) is
  split off; expected `200` with speakers 0–2 (`"Speaker 3"` new), `speakers.json`'s shape, and on a
  fresh server `/turns` with turns 0 [1], 1 [2], 2 [3, 4] and `hasMerges: false`, all derived by
  hand. Red: `expected:<200 OK> but was:<404 Not Found>` (no route). Green:
  `SegmentRepository.splitSpeaker` (one write transaction: a `speakers` row with the highest id + 1
  and its default name, the listed segments of the speaker rewritten to it),
  `ProcessingEngine.splitSpeaker`, and the route; contract docs (`openapi.yaml`, `docs/api/README.md`,
  item 12's contract, `docs/backend-reimplementation.md`, `docs/architecture.md`) updated. `gradle
  check` green (384 tests, 1 skipped); doc checks green.
- 2026-10-29 (cycle 1a checkpoint, user decisions): no refactoring; 1a ticked. The taken-default-name
  rule is reading (a): start at the ADR 0028 default (N = id + 1) and count up to the first free
  name; its discovered line is approved and added. Next test: 1c.
- 2026-10-29 (cycle 1c, the split's refusals): new `SpeakerRoutesSpec` scenario — a QUEUED and a
  FAILED task answer `409 not-completed`; task `999`/`abc`, speaker `7`/`x` and a speaker merged
  away answer `404 not-found`; speaker 0 (segments 5 and 7) refuses `[]`, another speaker's segment,
  a mix, another task's segment, an unknown id and `[5, 7]` (all its segments) with
  `400 invalid-split`; both COMPLETED tasks' `/turns` are unchanged afterwards. Red: `expected:<404
  Not Found> but was:<200 OK>` (the unknown speaker 7 got a new speaker; the 409s and unknown-task
  404s already came from `completedTaskOrError`). Green: the route answers 404 for a speaker that is
  not one of the effective speakers (the rename's check); `SegmentRepository.splitSpeaker` checks the
  selection against the speaker's own segments in the write transaction and returns the new
  `SplitOutcome` (`Split` / `Invalid`), mapped to `400 invalid-split`. Contract:
  `docs/api/error.invalid-split.json`, `openapi.yaml`'s 400/404, item 12's split bullet and error
  table, `docs/backend-reimplementation.md`. `gradle check` green (385 tests, 1 skipped); doc checks
  green.
- 2026-10-30: rebased onto `main` a5d1ecdf at the user's request (item 73 narrowed to the split
  action; the measurement is item 83). One conflict, in this file's Approval log; main's amended
  title, Goal, Proposal, decisions, test list and acceptance criteria taken, this branch's status,
  ticks, added lines and log entries kept. After the rebase: `gradle check` green (385 tests, 1
  skipped), `gradle integrationTest` green (0 failures), the doc checks and their `node --test`
  suites green (21 tests). The user's request to continue with the next proposed cycle started 1d;
  the cycle 1c refactor question is still open, so 1c stays unticked.
- 2026-10-30 (cycle 1d, a split publishes `speakers-split` after the write): new `SpeakerRoutesSpec`
  SSE scenario on cycle 8's task (speakers 0, 1, 0, 2) — a refused split of all of speaker 0's
  segments `[1, 3]` answers 400 and emits nothing; splitting `[3]` answers 200 and the next frame is
  `speakers-split` with `{ id, speakers }` of Speaker 1–4 (the new speaker 3 can only come from a
  read after the write), in `docs/api/speakers-split.json`'s shape. Red: `expected:<speakers-split>
  but was:<heartbeat>`. Green: `Event.SpeakersSplit`, emitted by `ProcessingEngine.splitSpeaker` on
  `SplitOutcome.Split` with the speakers read back; `EventsRoutes` names and encodes it with the
  speakers-event payload; `EventsOverflowSpec`'s exhaustive `when` names it. Contract:
  `docs/api/speakers-split.json`, `openapi.yaml` (event frame, endpoint summary),
  `docs/api/README.md`, item 12's events list and split bullet, `docs/backend-reimplementation.md`,
  `docs/architecture.md`. `gradle check` green (386 tests, 1 skipped); doc checks green.
- 2026-10-30 (cycles 1c and 1d checkpoint, user decisions): two refactors approved and done, each
  its own commit, `gradle check` green after each (386 tests, 1 skipped): rename and split share
  `RoutingCall.shownSpeakerIdOrNotFound` (the shown-speaker `404 not-found`); the three speaker
  events implement `Event.SpeakersChanged`, so `EventsRoutes.sseData` encodes their payload in one
  branch. 1c and 1d ticked. Next test: the discovered taken-default-name line.
- 2026-10-30 (cycle "a taken default name gets the next free Speaker N"): new `SpeakerRoutesSpec`
  scenario on cycle 8's task (speakers 0, 1, 0, 2) with speaker 0 renamed `"speaker 4"` and speaker
  1 `"Speaker 5"`; splitting speaker 0's segment 3 must name the new speaker 3 `"Speaker 6"` (its
  default 4 is taken in another letter case, 5 is taken, 6 is free; derived by hand from the rule),
  in the `200` and in `/turns`, the other names unchanged. Red: the new speaker was named
  `"Speaker 4"` (`Values differed at keys speakers`). Green: `SegmentRepository.splitSpeaker` names
  the new speaker with the first `"Speaker N"` from N = id + 1 upwards that no speaker of the task
  has in any letter case (the rename's comparison). Contract: item 12's split bullet and
  `docs/backend-reimplementation.md` state the rule. `gradle check` green (387 tests, 1 skipped);
  doc checks green.
- 2026-10-30 (taken-name checkpoint, user decisions): no refactoring (the naming expression stays
  inline in `splitSpeaker`); the line is ticked. Next test: 1b.
- 2026-10-30 (cycle 1b, a further split creates the next speaker id): new `SpeakerRoutesSpec`
  scenario — cycle 1a's task (speakers 0, 1, 0, 0) after `[3, 4]` was split to speaker 2; splitting
  speaker 2's segment 4 again answers `200` with Speaker 1–4 and `/turns` with one turn per segment
  of speakers 0, 1, 2, 3 (derived by hand). Green on arrival (20 tests, 0 failures): the highest id
  + 1 rule already covers it. Proven able to fail by a temporary, uncommitted break (`speakerId + 2`
  instead of the highest + 1): this scenario then gets id 4 and fails with `Values differed at keys
  speakers` (`speakerId, 4` where 3 was expected), while cycle 1a stays green. Mutation reverted;
  `gradle check` green (388 tests, 1 skipped).
- 2026-10-30 (cycle 1b checkpoint, user decisions): refactor approved and done
  (`65c00451` Refactor: the turns expectations are built from `turnSegment`/`turn`/`turnsJson`
  helpers; `speakerList` for non-contiguous ids; `gradle check` green, 388 tests); 1b ticked. Next
  test: the backend half of the discovered merges-become-permanent line.
- 2026-10-30 (cycle "a split makes the merges permanent", backend half of the discovered line): new
  `SpeakerRoutesSpec` scenario on cycle 8's task (speakers 0, 1, 0, 2) after `{0, 1}` was merged into
  0 — splitting speaker 0's segment 3 must answer `200` with speakers 0, 2 and a new 3 "Speaker 4";
  `/turns` with `hasMerges: false` and turns 0 [1, 2] (segment 2 stored under its survivor),
  3 [3], 2 [4]; a "Reset all merges" after it restores nothing; and in the database segment rows
  carry speakers 0, 0, 3, 2 with only speakers 0, 2, 3 left. All derived by hand. Red:
  `Values differed at keys hasMerges` (expected false, was true — the merge was still a mapping).
  Green: `SegmentRepository.splitSpeaker` makes the task's merges permanent inside the same write
  (each merged speaker's segments rewritten to its survivor, the `speaker_merge` rows and the
  merged-away `speakers` rows deleted) and validates the selection against the speaker's segments as
  readers see them, i.e. through the merge mapping, so a merged-away speaker's segment can be split
  as its survivor's. Contract: item 12's split bullet and `docs/backend-reimplementation.md` state
  the rule. `gradle check` green (389 tests, 1 skipped); doc checks green.
- 2026-10-30 (cycle "a refused split bakes nothing", the second discovered line, inside 1c's
  scenario): after the 400 refusals on the merged copy of cycle 8's task, an empty selection answers
  400, `/turns` still reports `hasMerges: true`, and a "Reset all merges" then lists speaker 1 again
  — so nothing was baked. Green on arrival (21 tests, 0 failures): the checks run before `bakeMerges`.
  Proven able to fail by a temporary, uncommitted move of `bakeMerges` above the checks: the refused
  split then baked the merge and the test failed with `expected:<true> but was:<false>` (hasMerges).
  Mutation reverted; `gradle check` green (389 tests, 1 skipped).
- 2026-10-30 (cycle "merging after a split", the split → merge round trip): new `SpeakerRoutesSpec`
  scenario — cycle 8's task merged `{0, 1}` into 0, then split to speakers 0, 2, 3; merging `{0, 3}`
  into 0 answers `200` with speakers 0 and 2, `/turns` with `hasMerges: true` and the three segments
  of speakers 0 and 3 forming one turn again, and a "Reset all merges" restores the split's turns;
  in the database no merge row is left after the reset and segment 3 keeps the split's speaker.
  Green on arrival (22 tests, 0 failures). Proven able to fail by a temporary, uncommitted no-op
  `bakeMerges`: the scenario then failed with `Values differed at keys hasMerges` (as did the
  merges-permanent scenario). Mutation reverted; `gradle check` green (390 tests, 1 skipped).
- 2026-10-30 (cycle "renamed speakers and a split"): new `SpeakerRoutesSpec` scenario — cycle 8's
  task with speakers 0 and 2 renamed Alice and Carol; splitting speaker 0's segment 3 answers `200`
  with Alice, Speaker 2, Carol and a new speaker 3 named "Speaker 4" (its default, free), `/turns`
  with segment 3 as speaker 3's and every other segment unchanged; renaming speaker 3 to Bob answers
  `200` with Bob on its row only and `/turns` speakers showing Bob. Derived by hand. Green on
  arrival (23 tests, 0 failures): a split only inserts a row. Proven able to fail by a temporary,
  uncommitted reset of the split speaker's name to its default inside `splitSpeaker`: the scenario
  then failed with `Values differed at keys speakers` (Alice reset), as did the taken-name scenario.
  Mutation reverted; `gradle check` green (391 tests, 1 skipped).
- 2026-10-30 (cycle "a merged-away speaker's segment splits as its survivor's", the first discovered
  line): new `SpeakerRoutesSpec` scenario — cycle 8's task with `{0, 1}` merged into 0; splitting
  segment 2 (stored under speaker 1, shown as speaker 0's) as speaker 0's answers `200` with
  speakers 0, 2 and 3 "Speaker 4", `/turns` with segment 2 as speaker 3's and speakers 0's segments 1
  and 3 in separate turns, and in the database segment speakers 0, 3, 0, 2 with no merge rows. Green
  on arrival (24 tests, 0 failures). Proven able to fail by a temporary, uncommitted return to raw
  ownership (`it.speakerId == speakerId`, ignoring the merge mapping): the scenario then answered
  `400` instead of `200`, and it was the only failure. Mutation reverted; `gradle check` green (392
  tests, 1 skipped).

- 2026-10-30 (cycle 1e, first half: ticking turns and splitting them in the view): new vitest
  scenario in `TaskTranscriptView.test.tsx` — with /turns' two-speaker fixture, the Split action is
  disabled until the third turn (Speaker 1's, starting 00:09) is ticked; pressing it sends
  `{ "segmentIds": [4] }` to `POST /api/tasks/3/speakers/0/split` (the ticked turn's speaker and its
  segment ids), the view then shows the split turns with "Speaker 3" on that turn and a name field
  for speaker 3, and the ticked box is cleared. Implementation, per the ADR 0038 rule for UI flows
  with steps: `speakerSplitMachine` (idle/sending, typed error, `split` action), a selection checkbox
  per turn in `TranscriptTurns` (with the shared focus ring, which the App focus-ring walk requires),
  a "Split selected turns…" action beside Merge in `SpeakersPanel`, the client's `splitSpeaker`, an
  MSW handler (mock addition), `speakers-split` in the event types, and the connection machine
  refetching /turns on it as on `speakers-merged`. Red recorded by removing the action
  (`TestingLibraryElementError: Unable to find an accessible element with the role "button" and name
  "Split selected turns…"`). Checks: frontend 267 tests, `tsc --noEmit` and prettier clean; backend
  `gradle check` green (392 tests, 1 skipped); doc checks green. The `speakers-split` event path (a
  second client's split updating an open view) is the half still open, so 1e stays unticked.
- 2026-10-30 (cycle 1e checkpoint, user decisions): refactor approved and done (`810a5c98`:
  `speakers-merged` and `speakers-split` record their turns change through one
  `withTurnsChanged` helper in the connection machine; 267 frontend tests green). Next test: the
  `speakers-split` event half of 1e.
- 2026-10-30 (cycle 1e, second half: a split made elsewhere updates the open view): new vitest
  scenario — with the transcript open on turns.json (Speaker 1, Speaker 2, Speaker 1), a pushed
  `speakers-split` for the task must make the view read /turns a second time and show the split turns:
  "Speaker 3" on the third turn, "Speaker 1" once, and a name field for speaker 3. Red:
  `TestingLibraryElementError: Unable to find an element with the text: Speaker 3` — the client's
  event subscription (`subscribeToEvents`) listened only to the named events it knew, so
  `speakers-split` never reached the connection machine. Green: `subscribeToEvents` listens to
  `speakers-split` like `speakers-merged`. Checks: frontend 268 tests, `tsc --noEmit` and prettier
  clean.
- 2026-10-30 (cycle 1e checkpoint, user decisions): no refactoring; 1e ticked and, with all of its
  parts green, item 1. Next test: the view half of the merges-become-permanent line (the
  confirmation before a split on a task with merges).
- 2026-10-30 (cycle "the view asks before a split that makes merges permanent", the view half of
  the discovered merges-become-permanent line): new vitest scenario — /turns reports merges until the
  split; ticking the third turn and pressing "Split selected turns…" opens the dialog "Split
  speaker?" with the agreed text; Cancel closes it, sends nothing and keeps the tick; pressing the
  action again and confirming with Split sends `{ "segmentIds": [4] }`, the view shows "Speaker 3",
  and "Reset all merges" is gone. Red: `TestingLibraryElementError: Unable to find an accessible
  element with the role "dialog" and name "Split speaker?"`. Green: `speakerSplitMachine`'s SPLIT
  carries `confirm` (the view sets it from `/turns`' `hasMerges`) and, when asked, waits in
  `confirming` for CONFIRM or CANCEL; the view renders the shared `ConfirmDialog` for that state.
  Checks: frontend 269 tests, `tsc --noEmit` and prettier clean; doc checks green.
- 2026-10-30 (confirmation checkpoint, user decisions): no refactoring; the merges-become-permanent
  line ticked; the discovered e2e-contract line approved and added. Next test: the e2e line.
- 2026-10-30 (cycle "the e2e split journey"): new `e2e/tests/09-split.spec.ts` against the real
  backend — a new two-speaker fixture clip (LibriSpeech readers 1188, 1089, 1188 back to back, so its
  bytes and sha256 differ from the merge clip's), which diarizes to three turns Speaker 1 → Speaker 2
  → Speaker 1; ticking the last turn and pressing "Split selected turns…" (no merges, so no
  confirmation) shows Speaker 1 → Speaker 2 → Speaker 3 at once, the same after a reload, and renaming
  speaker 3 to Bob relabels that turn. `SPLIT_CLIP`/`SPLIT_SOURCE_IDS` and the fixture generator gain
  the clip, with its sample count asserted in `lib/fixtures.test.ts`. Green on the first run (1
  passed, 1.1 m, the backend diarizing task 1 as expected); prettier and `tsc` clean in `e2e/`.
- 2026-10-30 (cycle "the e2e contract check covers speakers-split", the discovered line): the SSE
  recorder (`tests/helpers/capture.ts`) records `speakers-split`, and `tests/14-contract.spec.ts`
  maps it to `docs/api/speakers-split.json` for any journey that produces it. The conformance
  assertion itself lives in `09-split.spec.ts`, where a real split happens: the contract journey's
  own clip is single-speaker, and a split must leave the speaker a turn, so it cannot produce the
  event. That spec now installs the recorder before the app loads, expects exactly one
  `speakers-split` event and checks it with `mustConform(['speakers-split'], …)`. Green on the run
  (1 passed, 19.9 s); prettier and `tsc` clean.
- 2026-10-30: rebased onto `main` 4ea75524 at the user's request (items 71, 74 and 84 merged); no
  conflicts. Then the "must not break" run: `gradle check` green (397 tests, 1 skipped),
  `gradle integrationTest` green (440 tests, 355 skipped by tags, 0 failures), frontend 271 tests with
  `tsc --noEmit` and prettier clean, the doc checks and their `node --test` suites green (21), the e2e
  package's `node --test` suites green (17).
- 2026-10-30 (the e2e run, one failure fixed): the full Playwright journey failed one header
  scenario — `16-header-and-navigation.spec.ts`'s last step scrolled the transcript to the bottom and
  restored the desktop viewport, expecting the page to stop scrolling and the header's mark to return.
  The split action adds one control to the speakers panel, so the transcript page now scrolls at
  1280×800 and the compact header stayed. Removed neither control (the action belongs beside Merge);
  the step now scrolls to the top before asserting the mark, which is what its comment already
  described ("the mark returns at the top"). Re-run: the full journey green, 55 passed, 1 skipped, 0
  failed (9.6 m).
- 2026-10-30: test list complete and green; `Status: in-review` (the root agent prepares the PR).
- 2026-10-30 (review round, finding 1: a merged-away speaker's id must not be reused): new
  `SpeakerRoutesSpec` scenario — cycle 8's task (speakers 0, 1, 0, 2) with `{0, 2}` merged into 0, so
  the highest id belongs to a merged-away speaker; splitting speaker 0's segment 3 must create
  speaker 3 (default name "Speaker 4"), with `/turns` showing segment 4 (baked into 0) in its own
  turn, segment 2 still speaker 1's, and stored rows 0, 1, 3 — derived by hand from the decision.
  Red: `Values differed at keys speakers` (the new speaker got 2). Green: `splitSpeaker` reads the
  highest speaker id before `bakeMerges`, so the bake-in cannot free an id for reuse; the Decided
  section, the KDoc and `docs/backend-reimplementation.md` are true as written again.
  `SpeakerRoutesSpec` green (25 tests, 0 failures).
- 2026-10-30 (review round, finding 2: ticked turns must not survive a reload): new vitest scenario
  in `TaskTranscriptView.test.tsx` — a turn is ticked (index 1), another client's `speakers-split`
  reloads /turns with four turns (so index 1 now points at a different turn): the tick must be gone
  and "Split selected turns" disabled, and the same after a `speakers-merged` reload. Red:
  `expect(element).toBeDisabled()` — the stale tick stayed and would have split the wrong turn.
  Green: the view now loads through `loadTurns`/`reloadTurns`, which clear the selection before
  asking for /turns (the initial load, a task change, a speakers event, "Reset all merges" and the
  split's own reload all go through them), so an index can never address a turn the user did not
  tick. Frontend green (272 tests), `tsc --noEmit` and prettier clean.
- 2026-10-30 (review round, finding 4: a cancelled confirmation leaves no stale message): the
  finding's premise does not hold — the confirmation only opens through SPLIT, and SPLIT already
  assigns `error: null`, so the context error is always clear when CANCEL runs; an error set while
  confirming is unreachable. No code change (user decision): the finding is recorded as unreachable.
  A new vitest scenario pins the visible rule anyway — a confirmed split refused with `400
  invalid-split` shows `segmentIds names no segment` in the speakers panel, and a second attempt
  cancelled leaves no message and sends nothing. Green on arrival; proven able to fail by temporarily
  removing SPLIT's `error: null` (`expected document not to contain element, found <p…`), then
  reverted. Frontend green (273 tests).
- 2026-10-30 (review round, finding 3: the Split label matches its flow; user decision: no
  ellipsis, ask only for merges): the action is now "Split selected turns" — without merges it splits
  at once, and with merges the existing "Split speaker?" dialog still asks first, so no flow changed.
  The label and every selector naming it (the four vitest scenarios and `e2e/tests/09-split.spec.ts`)
  changed together; a rename has no red of its own. Frontend green (273 tests), `tsc --noEmit` and
  prettier clean; the split journey green on a fresh build (1 passed, 20.4 s).
- 2026-10-31 (findings 3 and 4 checkpoint, user decision): no refactoring; review items 3 and 4
  ticked.
- 2026-10-31 (review round, finding 5): the root review accepted the incidental
  `window.scrollTo(0, 0)` in `e2e/tests/16-header-and-navigation.spec.ts` (the split action makes the
  transcript page taller, so the restored desktop viewport no longer scrolls it back to the top); that
  spec is unchanged.
- 2026-10-31: root review round closed; `Status: in-review`. (1) MEDIUM, a merged-away speaker's id
  was reusable: `splitSpeaker` now takes the highest id over all speaker rows before the bake-in
  (approved refactor: one read of the rows). (2) MEDIUM, ticked turns went stale across a reload:
  every (re)load of the turns now drops the ticks. (3) LOW, the label: "Split selected turns" without
  the ellipsis; the confirmation stays for tasks with merges only (user decision). (4) LOW, a stale
  message after Cancel: unreachable as filed (SPLIT already clears the error); recorded, with a
  test pinning the visible rule (user decision). (5) LOW, the header spec's `window.scrollTo(0, 0)`
  accepted, unchanged. Closing run: `gradle check` green (398 tests, 1 skipped), `gradle
  integrationTest` green (441, 356 tag-skipped, 0 failures), frontend 273 tests with `tsc` and
  prettier clean, the docs package's steps green (30 `node --test` tests; item 75's retirement check on a
  clean `git archive` export), the e2e package's prettier, `tsc` and 17 `node --test` tests green, and
  the split journey green on a fresh build (1 passed, 19.9 s).
- 2026-10-31 (review round 2, finding 1: a tick made during a reload must not survive it): new
  vitest scenario — another client's `speakers-split` starts a reload whose /turns answer is held on a
  promise; the old list stays on screen and the user ticks its second turn (Speaker 2's, 00:05); the
  answer then lands with segment 2 moved to a new Speaker 3, so index 1 is a different turn (00:02).
  Every box must be unticked and "Split selected turns" disabled. The trigger is the split event, not
  a merge: a merge on this fixture joins everything into one turn, so no index could survive to
  mislead. Red: `expect(element).not.toBeChecked()` — the tick carried its index into the new list.
  Green: the selection records the turn list it was made against (`selection.list`, the `turns`
  array shown when it was ticked) and counts only while that same list is on screen, so no tick
  made against one list can count against another, whatever loads in between; toggles build on the
  shown list's ticks. Frontend green (274 tests), `tsc --noEmit` and prettier clean.
- 2026-10-31 (review round 2, finding 1 checkpoint, user decisions): the list-bound selection kept
  (explained: it holds by construction for every load path and has no frame with a stale tick);
  `loadTurns`/`reloadTurns` keep their clears, so ticks still vanish when a reload starts. Approved
  refactor: finding 2's test data, checked with both mutations. Finding 1 ticked.
- 2026-10-31 (review round 2, finding 2, approved refactor: the reload test's data): the finding-2
  test's `splitElsewhere` mock had segment 2 in two turns and two adjacent turns of speaker 0, which
  the backend cannot produce. It is now a real split result derived by hand: segment 2 moved to a
  new speaker 2 "Speaker 3", speakers 0, 1, 2, turns [0: seg 1], [2: seg 2], [1: seg 3], [0: seg 4];
  every assertion kept. Mutation checks, each temporary and reverted: (a) `reloadTurns` stops
  clearing — still green, since the list-bound selection from finding 1 discards the stale tick when
  the new list lands; (b) the list check dropped — still green, since `reloadTurns` clears when the
  reload starts; (c) both dropped (the pre-fix behavior) — red, `expect(element).toBeDisabled()`. The
  two safeguards each hold this test alone; finding 1's test is the one that pins the list check by
  itself (red under (b): `expect(element).not.toBeChecked()`). Frontend green (274 tests), `tsc` and
  prettier clean.
- 2026-10-31: root review round 2 closed; `Status: in-review`. (1) LOW, a tick made while the turns
  reload survived into the new list: the selection is now bound to the turn list it was made
  against and counts only while that list is shown (the reload clears stay, user decision).
  (2) LOW, the reload test's split-elsewhere data was a state the backend cannot produce: replaced by
  a real split result (refactor), every assertion kept, red again with both safeguards removed.
  Closing run: frontend 274 tests with `tsc --noEmit` and prettier clean; the split journey green on
  a fresh build (1 passed, 57.5 s); `gradle check` green (398 tests, 1 skipped; backend untouched,
  inputs unchanged since round 1's closing run).
- 2026-10-31 (the user tested the PR-ready branch): whole turns are the only unit a split can take,
  and segments can only go to a new speaker, so two people sharing one id cannot be separated. The
  PR preparation was undone (the branch is back at `955042ef`, its reviewed history) and the item
  amended with the user: "Reassign speakers" enters a selection mode with a box beside every segment
  and every turn (a turn's box selects or clears all its segments); the selected segments move to an
  existing speaker or a new one; any selection, several speakers included; an emptied speaker is
  removed; every move makes merges permanent after confirming; one task-scoped endpoint and one
  event replace the split's. Amendment test list proposed; the wordings, leaving the mode, the
  refusals and the id-based selection were proposals.
- 2026-10-31 (the user): every proposal approved, with three additions: "Reassign speakers" sits
  before "Merge speakers…" (the general correction first; merge only helps when one person was
  wrongly split); the mode's controls are a bar pinned to the top of the transcript with Cancel, and
  Escape cancels too (a bottom bar for phones is item 86's question); while the mode is on, only
  reassigning works — no playback, editing, renaming or merging. Amendment and its test list
  approved; status in-progress.
- 2026-10-31 (amendment cycle 1, api: segments of one turn moved to a new speaker): new
  `SpeakerRoutesSpec` scenario — speakers 0, 1, 0, 0 (the last turn holds segments 3 and 4);
  `POST /api/tasks/{id}/speakers/reassign` with `{ "segmentIds": [3], "speakerId": null }` must
  answer `200` with Speaker 1–3 in `speakers.json`'s shape, and a fresh server's `/turns` must show
  [0: 1], [1: 2], [2: 3], [0: 4] — the last turn split around the moved segment; derived by hand.
  Red: `expected:<200> but was:<404>` (no route). Green: `SegmentRepository.reassignSegments` (one
  write transaction: a new speaker row with the highest id over all rows + 1 and the first free
  `"Speaker N"`, the listed segments of the task rewritten to it), `ProcessingEngine.reassignSegments`
  and the route; merges, refusals and the event come with their own items. Contract: `openapi.yaml`,
  `docs/api/README.md`, item 12's contract, `docs/backend-reimplementation.md` and
  `docs/architecture.md` name the endpoint; the split entries stay until the item that removes them.
  `gradle check` green; doc checks green.
- 2026-10-31 (amendment cycle 1 checkpoint, user decisions): no refactoring (the new-speaker block
  shared with `splitSpeaker` goes when the split endpoint does); cycle 1a's split test is replaced by
  this scenario and is deleted in the item that removes the split endpoint. Item ticked. Next test:
  moving segments to an existing speaker.
- 2026-10-31 (amendment cycle 2, api: segments moved to an existing speaker): new
  `SpeakerRoutesSpec` scenario — speakers 0, 1, 1, 0; reassigning segment 2 to speaker 0 must answer
  `200` with Speaker 1 and Speaker 2 only, `/turns` must join segments 1 and 2 into one turn of
  speaker 0 ([0: 1, 2], [1: 3], [0: 4]), and no speaker row may be added; derived by hand. Red:
  `Values differed at keys speakers` (a third speaker was created). Green: `reassignSegments` moves
  the segments to the given speaker and creates one only for `null`, through the new private
  `insertNewSpeaker` (the id and name rules). Item 12's contract says an existing target adds no
  speaker. `gradle check` green; doc checks green.
- 2026-10-31 (amendment cycle 2 checkpoint, user decisions): no refactoring; no split test replaced.
  Item ticked. Next test: one move takes segments of several speakers.
- 2026-10-31 (amendment cycle 3, api: one move takes segments of several speakers): new
  `SpeakerRoutesSpec` scenario — speakers 0, 1, 0, 1; one reassignment of segments 2 (speaker 1's)
  and 3 (speaker 0's) to a new speaker must answer `200` with Speaker 1–3 and `/turns` [0: 1],
  [2: 2, 3], [1: 4] — the moved segments adjacent, so one turn; derived by hand. Green on arrival
  (28 tests): the move names one target for every listed segment. Proven able to fail by a
  temporary, uncommitted restriction of the update to speaker 0's segments: this scenario and the
  existing-speaker one failed (`Values differed at keys turns`). Mutation reverted; `gradle check`
  green.
- 2026-10-31 (amendment cycle 3 checkpoint, user decisions): no refactoring; item ticked. Next test:
  an emptied speaker is removed and its id is never reused.
- 2026-10-31 (amendment cycle 4, api: an emptied speaker is removed and its id is never reused): new
  `SpeakerRoutesSpec` scenario — speakers 0, 1, 0, 2; moving speaker 2's only segment (4) to a new
  speaker must answer `200` with speakers 0, 1 and the new 3 "Speaker 4" (the id read over all rows,
  max 2, before the removal), `/turns` [0: 1], [1: 2], [0: 3], [3: 4], and in the database speaker 2's
  row gone with segment speakers 0, 1, 0, 3; derived by hand from the amendment. Red:
  `Values differed at keys speakers` (speaker 2's row stayed and the new speaker took id 2). Green:
  `reassignSegments` deletes the task's speaker rows that no segment references, after the move and
  after `insertNewSpeaker` has counted them. Item 12's contract and
  `docs/backend-reimplementation.md` state the rule. `gradle check` green; doc checks green.
- 2026-10-31 (amendment cycle 4 checkpoint, user decisions): refactor approved and done — the
  emptied-speaker cleanup became `deleteEmptySpeakers` (first commit `b83a2030` nested it inside the
  transaction and did not compile; `eadd129f` puts it beside `reassignSegments`; `gradle check` green,
  402 tests). Item ticked. Next test: the refusals.
- 2026-10-31 (amendment cycle 5, api: the move's refusals): new `SpeakerRoutesSpec` scenario —
  QUEUED and FAILED tasks answer `409 not-completed`; task `999`/`abc` answer `404 not-found`; a
  target speaker `7` and a speaker merged away answer `404 not-found`; an empty selection, a segment
  the task does not have (`99`) and a move that changes nothing (segment 1 already speaker 0's)
  answer `400 invalid-reassignment`; both COMPLETED tasks' `/turns` are unchanged afterwards and the
  copy's merge is intact (`hasMerges: true`). Red: `expected:<400> but was:<200>` (nothing was
  validated). Green: `SegmentRepository.reassignSegments` returns the new `ReassignOutcome`
  (`Reassigned` / `Invalid`) and checks, inside the write and before anything changes, that the
  selection is non-empty, every listed segment is the task's, and — for an existing target — not
  every selected segment is already its own as readers see it (through the merge mapping); the route
  answers `404` for a target that is not a shown speaker and maps `Invalid` to `400
  invalid-reassignment`. Contract: `docs/api/error.invalid-reassignment.json`, `openapi.yaml`'s
  400/404, `docs/api/README.md`, item 12's bullet and error table,
  `docs/backend-reimplementation.md`. `gradle check` green (403 tests, 1 skipped); doc checks green.
- 2026-10-31 (amendment cycle 5 checkpoint, user decisions): no refactoring; item ticked. Cycle 1c's
  split refusals are migrated here (the split-endpoint copy goes with the endpoint). Next test: a move
  on a task with merges makes them permanent first.
- 2026-10-31 (amendment cycle 6, api: a move on a task with merges bakes them in first): new
  `SpeakerRoutesSpec` scenario — cycle 8's task with `{0, 1}` merged into 0; moving segment 3 to a new
  speaker must answer `200` with speakers 0, 2 and the new 3 "Speaker 4" (the id read over all rows
  before the bake-in), `/turns` with `hasMerges: false` and [0: 1, 2], [3: 3], [2: 4], and in the
  database no merge rows, segment 2 stored under 0 and speakers 0, 2, 3; derived by hand. Red:
  `Values differed at keys hasMerges` (nothing was baked). Green: `reassignSegments` reads the new id
  over all rows, then `bakeMerges`, then inserts the new speaker (its name searched among the
  survivors), moves the segments and drops the emptied speakers; `insertNewSpeaker` now takes the id
  the caller chose. Contract: item 12's bullet and `docs/backend-reimplementation.md` state the
  bake-in. `gradle check` green (404 tests, 1 skipped); doc checks green.
- 2026-10-31 (amendment cycle 6 checkpoint, user decisions): refactor approved and done (`f30799c1`:
  the new speaker's id is read in one expression; no helper — one caller, and the shared copy goes
  with the split). Item ticked. The removal line is split in two (user decision): this cycle adds the
  event, the removal is the added `(discovered)` line.
- 2026-10-31 (amendment cycle 7, api: the move's event): new `SpeakerRoutesSpec` SSE scenario — an
  empty selection answers `400` and emits nothing; moving segment 3 to a new speaker answers `200`
  and the next frame is `speakers-reassigned` with `{ id, speakers }` of Speaker 1–4 (the new speaker
  can only come from a read after the write), in `docs/api/speakers-reassigned.json`'s shape. Red:
  `expected:<speakers-reassigned> but was:<heartbeat>`. Green: `Event.SpeakersReassigned` (a
  `SpeakersChanged`, so `EventsRoutes` shares the payload branch), emitted by
  `ProcessingEngine.reassignSegments` on `Reassigned`; `EventsRoutes` names it and
  `EventsOverflowSpec`'s exhaustive `when` adds it. Contract: the new example, `openapi.yaml`'s event
  frame and list, `docs/api/README.md`, item 12's events list,
  `docs/backend-reimplementation.md`, and the e2e capture list and contract map. The frontend's event
  type and refetch land with the UI item that tests them. `gradle check` green (405 tests, 1 skipped);
  doc checks green.
- 2026-10-31 (amendment cycle 8, api: the split is gone everywhere, the added discovered line): new
  `SpeakerRoutesSpec` scenario — `POST /api/tasks/{id}/speakers/0/split` must answer an empty `404`
  (no route). Red: `expected:<404> but was:<400>` (the split route still validated the selection).
  Green: deleted the route, `SplitSpeakerRequest`, `SplitOutcome`, `splitSpeaker` (repository and
  engine), `Event.SpeakersSplit`, its `EventsRoutes` name and `EventsOverflowSpec` case; the ten
  split-only `SpeakerRoutesSpec` scenarios and its `split` helper; the frontend's
  `speakerSplitMachine`, the turn checkboxes, the Split action and its dialog, `splitSpeaker` client call, split MSW handler, `speakers-split` type, connection-machine
  case, `withTurnsChanged` (single use again, so inlined) and the `subscribeToEvents` listener; the
  split vitest scenarios; `e2e/tests/09-split.spec.ts` and the e2e `speakers-split` entries; and in
  the docs `speakers-split.json`, `error.invalid-split.json`, the openapi path, event frame and
  description, the README rows, item 12's split bullet, events list and error-table entries, the
  reimplementation table row, split prose and event list, and the architecture endpoints and events.
  `SPLIT_CLIP` stays in the e2e fixtures for the reassign journey (a fixture with a two-segment turn).
  Checks: `gradle check` green (396 tests, 1 skipped), `gradle integrationTest` green (439, 354
  tag-skipped, 0 failures), frontend 268 tests with `tsc` and prettier clean, the e2e package's
  prettier, `tsc` and 17 `node --test` tests green, the docs checks and their 30 `node --test` tests
  green.
- 2026-10-31 (amendment cycle 8 checkpoint, user decisions): no refactoring. Item was already ticked
  with the removal. Next test: the Reassign speakers entry point.
- 2026-10-31 (amendment cycle 9, ui: the Reassign speakers entry point): new vitest scenario — with
  two speakers the panel's buttons list "Reassign speakers" before "Merge speakers…"; with one
  speaker, "Reassign speakers" shows and "Merge speakers…" does not. Red: `expected -1 to be greater
  than or equal to 0` (no Reassign action). Green: `SpeakersPanel` gains the action before Merge,
  shown whenever the task has a speaker (not only with two); the transcript view holds the mode's
  state and, while it is on, shows the bar pinned above the transcript with the instruction and
  Cancel (the count, "Move to…" and the selection boxes come with their items). Frontend 269 tests,
  `tsc --noEmit` and prettier clean (a first run showed six timeouts while other jobs ran; the rerun
  alone was green).
- 2026-10-31 (amendment cycle 10, ui: the selection boxes and the bar's count): new vitest scenario —
  no checkbox exists before "Reassign speakers" is pressed; then the bar shows the instruction,
  "0 segments selected" and a disabled "Move to…", a box sits beside every segment (4) and every turn
  (3), and ticking a segment reads "1 segment selected" and enables "Move to…". Red:
  `expect(element).toHaveTextContent()` (no count in the bar). Green: the selection holds segment ids
  in the view (`selectedSegments`); `TranscriptTurns` renders a focus-ringed `SelectBox` beside each
  segment and each turn (a turn's box ticks all its segments, or clears them when all are ticked);
  the bar shows the count and opens the target picker ("New speaker" and the shown speakers); a Move
  sends through the new `speakerReassignMachine` (idle/sending, typed error, `reassigned` action that
  leaves the mode and reads /turns again) with the client's `reassignSegments` and its MSW handler.
  The picker's own behavior is pinned by the next item's test. Frontend 270 tests, `tsc --noEmit` and
  prettier clean.
- 2026-10-31 (amendment cycle 11, ui: the picker moves the selection): new vitest scenario — in the
  mode, ticking "Select segment starting 00:02" and pressing "Move to…" offers "New speaker" (the
  default), "Speaker 1" and "Speaker 2"; Move sends `{ "segmentIds": [2], "speakerId": null }` to
  `POST /api/tasks/3/speakers/reassign`, the view reads /turns again and shows the moved segment as
  "Speaker 3", and the bar and every box are gone. Green on arrival (271 tests): the picker and
  `speakerReassignMachine` arrived with cycle 10. Proven able to fail by dropping the `reassigned`
  action's reload (`Unable to find an element with the text: Speaker 3`), then reverted; `tsc` and
  prettier clean.
- 2026-10-31 (amendment cycles 9–11 checkpoint, user decisions): no refactoring after each; the three
  UI items are ticked (entry point, boxes and bar, picker). Next test: a turn's box ticks or clears
  all of its segments.
- 2026-10-31 (amendment cycle 12, ui: a turn's box): new vitest scenario — the mode's first turn box
  ticks segments 1 and 2 ("2 segments selected"), the second turn's box adds segment 3 ("3"), and
  pressing the first box again clears only its two segments ("1 segment selected"). Green on arrival
  (271 tests): `toggleTurn` from cycle 10. Proven able to fail by making it always tick
  (`expect(element).not.toBeChecked()`), then reverted. `tsc` and prettier clean.
- 2026-10-31 (amendment cycle 13, ui: leaving the mode): new vitest scenario — Escape leaves the mode
  with the selection dropped and nothing sent, re-entering starts from "0 segments selected", Escape
  with the picker open closes only the picker, and Cancel leaves with the transcript untouched. Red:
  `expected document not to contain element, found <section` (Escape did nothing). Green: a window
  `keydown` listener while the mode is on closes the picker first, else leaves the mode (the picker
  and the confirmation share that order; the confirmation joins it with its item). `tsc` and prettier
  clean.- 2026-10-31 (amendment cycle 14, the mode stops everything else): vitest test — with playback
  running and a current segment, starting the mode pauses it (`playback.pause` called); the player's
  Play, the speaker name field, "Merge speakers…" and the actions menu are disabled, and the current
  segment has no Edit or Play-segment control; Cancel re-enables them. Red:
  `expected "pause" to be called at least once`. Green: starting the mode calls `pauseToIdle`; the
  player takes `disabled={reassigning || editingSegmentId !== null}`; the panel disables the name
  fields, Merge and "Reset all merges" and hides an open merge form or dialog; the transcript hides
  the current segment's controls; the actions menu trigger is disabled. Frontend 274 tests, `tsc` and
  prettier clean.
- 2026-10-31 (amendment cycle 15, the merge confirmation): vitest test — with `/turns` reporting
  merges, Move opens the dialog "Reassign speakers?" with the decision's wording; Cancel closes it,
  sends nothing and keeps "1 segment selected"; confirming sends `{ "segmentIds": [2], "speakerId":
  0 }` and, once the baked turns land, "Reset all merges" is gone. Red: `Unable to find an accessible
  element with the role "dialog" and name "Reassign speakers?"`. Green: `speakerReassignMachine`'s
  SEND carries `confirm` (the view sets it from `hasMerges`) and waits in `confirming` for CONFIRM or
  CANCEL; the bar renders the shared `ConfirmDialog`; Escape closes the confirmation before the
  picker or the mode. Frontend 275 tests, `tsc` and prettier clean.
- 2026-10-31 (amendment cycle 16, the frontend's event): vitest test — with the transcript open, a
  pushed `speakers-reassigned` for the task makes the view read /turns a second time and show the
  moved segment as "Speaker 3". Red: `Unable to find an element with the text: Speaker 3` (the client
  did not subscribe to the event). Green: `speakers-reassigned` in the `ServerEvent` type, a
  connection-machine case recording it as a turns change, and its `subscribeToEvents` listener.
  Frontend 276 tests, `tsc` and prettier clean.
- 2026-10-31 (amendment cycle 17, the selection across a reload): vitest test — in the mode, ticking
  segment 2 and reloading the turns (another client's merge) leaves exactly segment 2 ticked (four
  segment boxes, only the second checked) and "1 segment selected". Green on arrival (276 tests): the
  selection holds segment ids, so a reload cannot move it. Proven able to fail by reintroducing the
  old clear-on-reload effect (`expected [ false, false, false, false ] to deeply equal [ false, true,
  false, false ]`), then reverted; `tsc` and prettier clean.
- 2026-10-31 (amendment cycle 18, e2e): new `e2e/tests/09-reassign.spec.ts` and its fixture — reader
  1089 twice, then 1188, so the first speaker's turn holds two segments (the diarizer's log confirms
  "speaker 1: 2 segments"). The journey: press "Reassign speakers", tick the first turn's second
  segment, "Move to…" → "New speaker" → Move: the turns become Speaker 1, Speaker 3, Speaker 2 at
  once and the recorded `speakers-reassigned` matches `docs/api/speakers-reassigned.json`; move it
  to "Speaker 2": the turns join (Speaker 1, Speaker 2); a turn's box ticks both of its segments
  ("2 segments selected"); Escape leaves the mode and a reload keeps the result. `REASSIGN_CLIP`/
  `REASSIGN_SOURCE_IDS` join the fixture generator with their sample count asserted. Green on the
  run (1 passed, 21 s); e2e prettier, `tsc` and `node --test` clean.
- 2026-10-31 (amendment cycle 19, must not break): new `SpeakerRoutesSpec` scenario — speakers
  0, 0, 1, 2 with 0 renamed Alice and 2 Carol; moving segment 3 (speaker 1's only segment) to a new
  speaker removes speaker 1's row and adds speaker 3 "Speaker 4", keeping both names; speaker 3
  renames to Bob; merging Bob into Alice joins them and answers Alice and Carol; resetting that merge
  restores the move's turns (derived by hand, corrected once when the emptied speaker's removal was
  missed). Then the whole gate: `gradle check` green (397 tests, 1 skipped), `gradle integrationTest`
  green (440, 355 tag-skipped, 0 failures), frontend 277 tests with `tsc` and prettier clean, the e2e
  package's prettier, `tsc` and 17 `node --test` tests green, the full Playwright journey green (55
  passed, 1 skipped, 0 failed, 8.1 m) and the docs checks with their 30 `node --test` tests green.
- 2026-10-31: 2026-10-31 amendment complete; `Status: in-review`. The turn-level split is replaced by
  `POST /api/tasks/{id}/speakers/reassign` (segments to an existing or a new speaker), the
  `speakers-reassigned` event, and the "Reassign speakers" mode with its bar, per-turn and per-segment
  boxes, picker, confirmation, exclusive mode and reload-safe selection; the split endpoint, event,
  error and UI are gone; the e2e journey and the must-not-break run are green.
- 2026-10-31: closing check of the test list found amendment cycle 7's event item unticked although
  its scenario and log entry were in place (cycle 7 added `speakers-reassigned`); ticked here.
- 2026-10-31 (review round 3, finding 1: the mode's segments are inert): extended the "stops
  everything else" scenario — in the mode a click on a segment leaves the playhead where it is, a
  double-click and Enter or Space never call `audio.play`, and the text no longer has the button role.
  Red: `expected "play" to not be called at all, but actually been called 3 times`. Green: while the
  mode is on, a segment renders as `SegmentInert` (plain text, no role, tabIndex or
  handlers); the button-role element and its controls stay outside it. Frontend 277 tests, `tsc` and
  prettier clean.
- 2026-10-31 (review round 3, finding 2: the target is checked inside the write): new repository
  scenario in `SegmentRepositorySpec` — a task with speakers 0, 1, 0; `reassignSegments(taskId,
  [first], speakerId = 7)` answers `ReassignOutcome.UnknownSpeaker` and leaves the segments' speakers
  (0, 1, 0) and the two speaker rows untouched. Red: the scenario did not compile
  (`Unresolved reference 'UnknownSpeaker'`). Green: `ReassignOutcome.UnknownSpeaker`;
  `reassignSegments` re-checks the target against the task's stored speakers through the merge map
  inside the same transaction, before any write; the route dropped its separate pre-check and maps
  the outcome to `404 not-found`, `invalid-reassignment` staying for the invalid selections. Item 12's
  contract and `docs/backend-reimplementation.md` say the target is re-checked inside the write.
  `gradle check` green (398 tests, 1 skipped); doc checks green.
- 2026-10-31 (review round 3, finding 3, a deletion): removed the unused split fixture — `SPLIT_CLIP`
  and `SPLIT_SOURCE_IDS` and the clip's entry in `FIXTURE_CLIPS` (`e2e/lib/backend.ts`), its decode and
  write (`e2e/lib/fixtures.ts`), the `fixtures.test.ts` case that asserted it, and the stale "item
  73's split test" comments. No spec had used them since `09-reassign` replaced the split. Verified:
  no reference remains; e2e prettier and `tsc` clean; the package's 17 `node --test` tests green.
- 2026-10-31 (review round 3, finding 4, a comment): `EventsRoutes.kt`'s `SpeakersEventJson` KDoc now
  names `speakers-reassigned` (item 73's amendment) instead of the removed `speakers-split`; no
  `speakers-split` remains in the backend, `docs/api` or the specs. `ktlintCheck` green; the
  architecture-name check green.
- 2026-10-31: root review round 3 closed; `Status: in-review`. (1) MEDIUM, the mode only hid the
  segment controls: a segment is now inert while it is on (plain text, no role or keys, no seek or
  play), pinned by the extended "stops everything else" scenario. (2) MEDIUM, the move's target was
  checked only in the route: `reassignSegments` re-checks it against the task's stored speakers
  through the merge map inside the write and answers `ReassignOutcome.UnknownSpeaker`, which the route
  turns into `404` — pinned by a repository scenario. (3) LOW, the dead split fixture is removed from
  the e2e package. (4) LOW, the speakers-event KDoc names `speakers-reassigned`. Closing run:
  `gradle check` green (398 tests, 1 skipped), `gradle integrationTest` green (441, 356 tag-skipped,
  0 failures), frontend 277 tests with `tsc` and prettier clean, the e2e package's prettier, `tsc` and
  17 `node --test` tests green, the full Playwright journey green (55 passed, 1 skipped, 0 failed,
  8.5 m) and the docs checks with their 30 `node --test` tests green.
- 2026-10-31 (root agent, PR preparation): approved by the user after review round 3 and the
  reassign journey rerun on the real backend; the item's spec and final test list moved from
  `roadmap/73-diarization-speaker-separation.md` to `docs/specs/speaker-reassignment.md` with
  `Status: done`, rebased onto `main` (`17a2635e`) and squashed to one commit (ADR 0050). The
  reassignment mode joins item 87's write gate when that item lands, whichever branch is second.
