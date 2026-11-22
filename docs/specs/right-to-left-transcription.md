# 76 — Right-to-left transcription: direction-aware transcripts and layout

Status: done (roadmap item 76, approved 2026-11-26)
Depends on: [67](tui-frontend.md) (the TUI whose transcript, dialogs and mouse targets this item
now covers), [70](../roadmap/70-rust-gui-window.md) (the window is the TUI's UI, and it exists only
after 70) — the browser part is independent of 70; [74](desktop-presentation.md) supplies
the transcript's `lang`/fonts, a related but independent change (direction itself is
content-derived)
Blocks: —

## Goal

Transcribed text in right-to-left scripts — Arabic, Hebrew, Urdu, Persian, Sindhi, Yiddish, Pashto
— reads and lays out correctly: **each paragraph's direction is decided by its first alphabetic
character** (numbers and punctuation before it do not count), the transcript's reading column
mirrors for an RTL-first transcript, and speaker names and filenames follow the same rule. The
The direction comes from the text itself, not from the task language, so mixed-language and
auto-detected recordings work without storing a detected language. LTR content is untouched.
Direction is presentation only: the stored text and the txt/srt/vtt downloads stay byte-identical.
The same rule applies in every frontend: the browser, the Slint window (item 70) and the terminal
TUI (item 67), which draw one UI. The TUI orders each wrapped line itself with the Unicode bidi
algorithm and maps its cursor, selection and mouse hits through that order. A cell grid cannot shape
glyphs — Arabic joining and ligatures need a text shaper — so order and mirroring are identical
across the frontends while the emulator decides the glyph forms; that is the recorded difference.

## Evidence

- `docs/api/languages.json` carries seven RTL codes: `ar` (line 19), `he` (26), `ur` (37), `fa`
  (47), `sd` (79), `yi` (82), `ps` (87); `backend/src/main/resources/languages.json` is a
  byte-identical packaged copy that `LanguagesContractSpec` enforces
  (`backend/src/test/kotlin/stt/http/LanguagesContractSpec.kt:10-26`). The list matters for
  transcription and fonts, but **not for direction**: the rule below is content-derived.
- A task stores the **requested** language (`http/TaskJson.kt:13,42`; `http/UploadRequest.kt:27-29`);
  `auto` is the default and whisper's detected language is not stored — which is fine, because the
  text decides its own direction.
- **No direction exists in the frontend**: no `dir` attribute or `direction` CSS under
  `frontend/src` (grep finds only positional `left`/`right` geometry), and `frontend/index.html:2` is
  `<html lang="en">` with no `dir`; the app chrome is English and LTR.
- The transcript already renders each turn as a **paragraph**: `TurnText` is a `Text`
  (`frontend/src/shared/ui/typography.tsx:8-10`, `Text = styled.p`) with the segments as inline
  spans inside it (`features/history/TranscriptTurns.tsx:41-66`). The layout is direction-dependent
  by position: the time gutter is the grid's first column and the speaker/text follow it
  (`:11-19`). Under `dir="rtl"` the grid mirrors (the first column moves to the right) — the
  intended effect, but nothing sets the direction.
- User content appears outside the transcript too: queue/history filenames
  (`features/queue/rowParts.ts:20-23`) and speaker names, with no `dir="auto"`.
- Fonts for Arabic and Hebrew are already in the bundle (Plex Arabic/Hebrew,
  `nix/desktop-bundle.nix:35-48`); the gap is direction and layout, not glyph coverage. Item 74
  (draft) marks `lang` for font selection from the task language; direction and `lang` are
  independent here.
- Exports are plain UTF-8 txt/srt/vtt (`docs/specs/output-formats.md`); the files carry no direction
  metadata, and none is needed for them.
- The e2e fixture model is `whisperTinyEn` (`TEST_WHISPER_TINY_EN_MODEL`, `versions.json`), which is
  English-only, so it cannot produce a real RTL transcript; the e2e seeds a completed task's rows
  instead (Decided, 2026-10-28).
- **A terminal can order text itself; it cannot shape it** (2026-11-17). The TUI owns every cell it
  draws (ratatui's buffer, `client/stt-tui/src/view.rs`), so the Unicode bidi algorithm can order
  each wrapped line before it is written; `unicode-bidi` is the Rust implementation of that
  algorithm. Glyph shaping needs a text shaper that a cell grid does not have: whether Arabic letters
  join is the emulator's choice. Of the common emulators, only a few apply bidi themselves; the
  notable one, mlterm, would reorder the TUI's already-visual cells, and no terminal escape sequence
  reports or disables that.

## Decided (2026-10-23, user)

- **The first alphabetic character of a paragraph decides that paragraph's direction**; numbers,
  punctuation and whitespace before it are ignored. This is the Unicode bidi rule for paragraph
  direction (the "first strong character" — letters of the L, R and AL classes). The browser's
  `dir="auto"` approximates it, but the app implements the rule itself (Decided, 2026-11-22).
- **The same rule applies to speaker names** (and, consistently, to filenames): the direction
  follows the script of the name's first alphabetic character, not the task language.
- **Direction is per paragraph, not per segment**: a turn's text is one paragraph; inline segments
  inherit its direction and the bidi algorithm orders embedded runs inside it. A Latin word or a
  number inside an RTL paragraph does not flip the paragraph.
- If a paragraph has **no alphabetic character** (digits/punctuation only), it falls back to the
  direction of its container; a standalone name or filename with no alphabetic character falls back
  to LTR.
- **The segment editor is the one sub-paragraph exception** (item 52): while the inline editor is
  open, its textarea takes the segment's own first-strong direction — the same rule applied to the
  segment's text — independent of the paragraph. The surrounding paragraph keeps its direction, and
  closing the editor restores the paragraph rule; this applies in edit mode only.
- The direction does **not** come from `docs/api/languages.json` or a stored detected language; no
  task-schema or contract change is needed for it.

## Proposal

(Amended 2026-11-22/23: the app's own first-strong implementation replaces `dir="auto"`; each turn
row — timestamp, speaker name and text — takes its paragraph's direction; the player and the app
chrome follow the app language, item 88.)

1. **The rule, implemented by the app**: one function per frontend takes a text and returns `rtl`,
   `ltr` or none (no alphabetic character), by the first alphabetic character; the browser sets the
   result as an explicit `dir` on each turn's text (`TurnText`, already a `<p>`), and the segment
   spans stay inline without their own `dir`, so the paragraph keeps one direction and the bidi
   algorithm orders its runs. The paragraph's direction comes from the stored segment texts, never
   from the view's placeholder for an empty segment. The rule's cases, for single texts and for a
   transcript's turns, are one table, `docs/text-direction-cases.json`, that every frontend's
   implementation is tested against (the browser's: `frontend/src/shared/textDirection.test.ts`).
2. **Turn rows and the no-letter fallback**: each turn row — its timestamp gutter, its speaker name
   and its text — takes its paragraph's direction, so an RTL turn has its timestamp on the right and
   its name right-aligned, and an LTR turn the opposite. The speaker name is placed by its turn but
   isolated: its own first alphabetic character orders it inside. A turn with no alphabetic
   character takes the previous turn's direction; at the start of the transcript, the next turn's
   with a letter; when no turn has a letter, the app language's.
3. **Layout**: direction-dependent geometry in the transcript uses logical properties
   (`margin-inline`, `padding-inline`, `inset-inline-*`, `text-align: start/end`); the turn row's
   grid mirrors by `direction`. The player and app chrome follow the app language's direction (item
   88), never the transcript's.
4. **User content outside the transcript**: the rule's explicit `dir` and start alignment on
   queue/history filenames and speaker names, LTR when they have no alphabetic character; the app
   shell's own layout follows the app language (item 88).
5. **Exports unchanged**: no RTL marks or BOM are added; the same task's txt/srt/vtt bytes are
   pinned before and after.
6. **Tests**: component tests for the rule's cases (RTL-first, Latin-first, digits/punctuation
   before the first letter, no letter at all, mixed runs, names), the turn rows' mirror and
   mixed-direction turns; an e2e that produces or seeds an RTL transcript at desktop and phone
   widths; the user's visual sign-off.
7. **ADR** for the direction model: content-derived paragraph direction implemented by the app (not
   the browser's `dir="auto"`), an explicit `dir` on paragraphs/names/filenames, logical CSS, the
   fallback rule, the UI's direction from the app language, and the TUI's own bidi ordering with
   the shaping and bidi-aware-terminal limits.
8. **The TUI**: each turn's wrapped line ordered by the Unicode bidi algorithm with the paragraph's
   first-strong base direction and written in visual order; the edit, rename and search cursor, the
   selection highlight, the mouse hit-test, the playhead and the segment markings invert that
   order; names, filenames, the help and the segment editor follow the same rule; each turn row's
   timestamp follows its turn as in the browser, and the player follows the app language.

## Decided (consultation, 2026-10-28)

- **The e2e seeds the database**: a completed task with RTL segments is inserted into the fixture
  SQLite database before the app starts — no multilingual model download, no licensed or recorded
  audio committed; the transcript text is asserted exactly.
- **`dir="auto"` is the mechanism**; `unicode-bidi: isolate`/`plaintext` is added only if a test
  shows an inline segment leaking into its paragraph; timestamps and the player stay `dir="ltr"`.
  *(Superseded 2026-11-22: the app implements the rule itself, and timestamps and the player follow
  the app language — see Decided, workspace, 2026-11-22.)*
- **A short Latin-first name (`WHO`) stays LTR**: the first-strong rule, not a per-task override, is
  the behavior (the user's stated rule).
- **The TUI draws bidi itself** (the user, 2026-11-17; this replaces the 2026-10-28 "recorded as
  unsupported" line). The TUI owns every cell, so it orders each wrapped line with the Unicode bidi
  algorithm and writes the visual order; the logical↔visual mapping backs the edit, rename and
  search cursor, the selection highlight, the mouse hit-test, the playhead and the segment markings.
  Names, filenames, the help and the segment editor follow the first-strong rule as in the browser;
  timestamps and the player stay LTR. *(Timestamps and the player superseded 2026-11-23: the
  timestamp follows its turn, the player the app language.)*
- **Shaping is the recorded difference, and the user accepts it** (2026-11-17): the TUI cannot join
  or ligate Arabic; order, mirroring and hit-testing are still the window's.
- **A bidi-aware terminal is the one unsupported configuration** (2026-11-17): an emulator that
  reorders cells itself (mlterm with bidi enabled) double-reorders the TUI's visual order, and no
  portable query reports or switches that off. The TUI names the setting in its help page where the
  environment identifies the emulator (`TERM`/`MLTERM`); every emulator without its own bidi is
  exact.
- **The Slint window implements the same rule** in this item, after item 70 (this item's
  dependency); non-DOM consumers use the same first-strong rule.
- Because the window is part of this deliverable, the item starts after item 70; its browser part is
  ready before that.

## Decided (workspace, 2026-11-22, user)

- **The app implements the first-strong rule itself; `dir="auto"` is not the mechanism.** A probe
  in Playwright's Chromium 156 showed `dir="auto"` cannot meet the decided behavior: a
  digits/punctuation-only paragraph in an RTL container resolves `ltr` (not the container's
  direction); a reading column with `dir="auto"` skips children that carry their own `dir`, so it
  resolves from a speaker name or nothing, not from its first paragraph; and an empty first
  segment's placeholder (the UI language's text) decides its paragraph. jsdom resolves some of these
  cases differently from Chromium. The TUI and the planned Rust window cannot use the browser's
  algorithm either, so only the app's own implementation is known to be consistent across the
  frontends.
- **The UI's direction follows the app language (item 88), not the transcript's language**: the
  player and the app chrome mirror with an RTL app language and never with the transcript. This
  replaces "the player, timestamps and app chrome stay LTR", which predates item 88.

## Decided (workspace, 2026-11-23, user)

- **A turn row follows its paragraph**: the timestamp and the speaker name are aligned like the turn
  that follows them — an RTL turn has its timestamp on the right and its name right-aligned, an LTR
  turn the opposite. This replaces the reading column that mirrored by its first paragraph.
- **A speaker name in a turn is placed by the turn and ordered by itself**: inside the name, its own
  first alphabetic character decides the order (an isolated run, like `<bdi>`), so an Arabic name
  above an English turn sits on the left and still reads right-to-left. Standalone names (the
  speakers panel, the rename field) follow the rule in full.
- **A turn with no alphabetic character takes the previous turn's direction**; at the start of the
  transcript, the next turn's that has a letter; when no turn has a letter, the app language's.
- **One shared table of direction cases** (text → `rtl`/`ltr`/none) is read by the tests of every
  implementation of the rule, so the browser, the TUI and the window cannot drift apart.

## Decided (workspace, 2026-11-25, user)

- **The segment editor's field without a letter** (item 52's sub-paragraph exception) takes its
  paragraph's direction — the turn's, which a letter-less turn takes from its neighbours — and,
  when no turn has a letter, the app language's. A field with a letter takes its own first letter's
  direction, re-decided as the draft is typed; the app's own rule, not the browser's `dir="auto"`.

## Test list (approved; behaviors only)

- [x] An RTL-first paragraph is RTL even when the task's language is English or `auto`, and even
      when it starts with digits or punctuation (those do not decide)
- [x] A Latin-first paragraph is LTR even after an RTL paragraph, and the RTL paragraph stays RTL
      (direction is per paragraph, not per transcript)
- [x] A paragraph with no alphabetic character (digits/punctuation only) takes the previous turn's
      direction; at the start of the transcript, the next turn's with a letter
- [x] When no turn of a transcript has an alphabetic character, its paragraphs take the app
      language's direction
- [x] A standalone name or filename with no alphabetic character falls back to LTR
- [x] A mixed paragraph (`WHO: مرحبا`) is an LTR paragraph with the Arabic run ordered RTL inside it
- [x] Speaker names follow the same rule for their own order (Arabic-first → RTL, `Speaker 1` →
      LTR, `12 د. أحمد` → RTL); inside a turn the name is placed by its turn's direction
- [x] Each turn row mirrors with its paragraph — an RTL turn's timestamp on the right and its name
      right-aligned, an LTR turn's the opposite — and the player and app chrome follow the app
      language's direction (item 88), not the transcript's
- [x] Queue/history filenames in RTL scripts render start-aligned with their own direction, and
      mixed-script names keep their order
- [x] The same task's txt/srt/vtt downloads are byte-identical before and after
- [x] (e2e) A seeded RTL transcript in a real browser at desktop and phone widths shows the mirrored
      layout with no overflow or clipped text
- [x] In edit mode (item 52), the segment editor is aligned by the segment's first alphabetic
      character, independent of its paragraph's direction; closing it restores the paragraph rule
- [x] (after item 67) The TUI draws a seeded RTL transcript in the same order as the window: a
      mixed-direction line (`WHO: مرحبا`, a number inside an Arabic run) matches a hand-derived
      visual order, a Latin-first turn stays LTR, and an RTL turn after it stays RTL
- [x] (after item 67) In the TUI the cursor, the selection highlight and a mouse click in an RTL
      line name the logical character they cover: the rename and edit cursor, a marked segment and
      the playhead land where they are drawn
- [ ] (after item 70) The Slint window applies the same paragraph and name direction rule to a
      seeded RTL transcript, and its order matches the TUI's for the same text; the recorded limit
      is glyph shaping, not order
- [x] (after item 67) Where the environment names an emulator that reorders text itself (mlterm,
      through `TERM` or `MLTERM`), the TUI's `?` reference names that setting and why it must be
      off, and a terminal that names none shows no such line
- [x] (must not break) LTR transcripts, the existing e2e journey, the frontend suite and the
      output-format suites stay green
- [x] (discovered) An empty first segment's placeholder (the app language's text) does not decide
      its paragraph's direction; the stored segment texts do
- [ ] (discovered) Every implementation of the rule (browser, TUI, window) passes one shared table
      of direction cases
- [x] (discovered) A download of a task with a non-ASCII name (an RTL one included) keeps that name:
      the browser saves `محاضرة الأحد.txt`, not `transcript.txt`

## Acceptance criteria

- Paragraphs, speaker names and filenames take their direction from their first alphabetic
  character; the transcript mirrors correctly; mixed runs and fallbacks behave as decided; LTR
  content and the downloads are unchanged; an e2e and the user's visual check prove the layout.
- The TUI and the window draw the same order and mirroring for the same transcript, with the TUI's
  cursor, selection and mouse hits mapped through its visual order; the TUI's glyph shaping is the
  recorded difference, and a terminal that applies its own bidi is the recorded unsupported setting.
- All test-list items ticked and green; user approved the diff.

## Cycle log

- **Cycle 1 — 2026-11-23 — a turn with no letter takes the previous turn's direction (at the
  start, the next lettered turn's).** Test: `frontend/src/features/history/TaskTranscriptView.test.tsx`
  ("gives a turn with no letter the previous turn's direction…"). Red at `0a79bc96` with the test
  uncommitted: `AssertionError: expected 'ltr' to be 'rtl'` on the leading `...` turn. Green:
  `8116ffff` (the tree the green run was on) — frontend 53 files / 343 tests, prettier, build;
  e2e `tests/88-localization.spec.ts` 2 passed. Make it right (the user's choices): every RTL
  script's letters count (the R/AL code-point ranges, 0 mismatches against Chromium 156 over all
  145,672 letters); every turn gets the computed `dir` instead of `dir="auto"` (item 88's three
  `dir="auto"` pins now assert the resolved `ltr`); an edit re-decides from the stored texts.
  Refactors (approved): `4364d1a9` `letterDirection` in `shared/textDirection.ts`; `244ce6f7`
  `turnDirections`; `aac0caa7` the test's `turnsOf` computes each turn's times once — green at
  `aac0caa7`: frontend 53 files / 343 tests, prettier, build.
- **Cycle 2 — 2026-11-23 — one shared table of direction cases (the browser's part).** Test:
  `frontend/src/shared/textDirection.test.ts`. Red at `bfb36784` with the test uncommitted:
  `Failed to resolve import "../../../docs/text-direction-cases.json"`. Green: `63ec6451` (the tree
  the green run was on) — frontend 54 files / 377 tests, prettier, build. Make it right (the
  user's choices): the table holds the turns' no-letter fallback as well (`{ letters, turns }`,
  checked against `turnDirections`); every `RTL_LETTER` range has a case, and a combining mark
  before a letter does not decide; one test per case, named by its `why`; the table must hold all
  three outcomes for texts and for turns. Refactoring: none (the user agreed; the only repetition
  is `?? 'none'` at two differently shaped calls). The item stays open: the TUI's and the
  window's implementations pass the same table in their own cycles.
- **Cycle 3 — 2026-11-23 — each turn row mirrors with its paragraph; the player follows the app
  language.** Test: `frontend/src/features/history/TaskTranscriptView.test.tsx` ("mirrors each
  turn row…"). Red at `c4acd1f6` with the test uncommitted: `AssertionError: expected 'ltr' to be
  'rtl'` on the Arabic turn's row. Green: `068fa9eb` (the tree the green run was on) — frontend 54
  files / 378 tests, prettier, build; e2e `tests/88-localization.spec.ts` 2 passed. Make it right
  (the user's choices): the computed `dir` sits on `TurnRow` (gutter, name and text), its comments
  describe the start-side gutter; the test also pins the Arabic app language (player `rtl`, the
  English turn's row `ltr`, the Arabic turn's `rtl`, timestamps in Arabic-Indic digits). jsdom has
  no layout: the on-screen position is the e2e item's. Refactors (approved): `04f1dfd4`
  `openTask`; `c89b78d2` `serveAppLanguage` — green at `c89b78d2`: frontend 54 files / 378 tests,
  prettier, build.
- **Cycle 4 — 2026-11-23 — speaker names follow the rule for their own order, placed in a turn by
  its direction.** Tests: `frontend/src/features/history/TaskTranscriptView.test.tsx` ("orders a
  speaker name by its own first letter…", "shows each speaker name in its own direction in the
  merge and reassign forms"). Red at `f6d3c9ce` with the test uncommitted: `expected 'ltr' to be
  'rtl'` on `12 د. أحمد` in the transcript. Green: `61b26777` (the tree the green run was on) —
  frontend 54 files / 380 tests, prettier, build; e2e `05`, `08`, `09`, `52`, `88` 11 passed; the
  full `pnpm --dir e2e test` 59 passed, 1 skipped, 1 failed — `87-writes-wait-for-the-connection`'s
  second spec (`GET /api/tasks answered 401` after the first spec's restart), the open entry at
  `docs/known-issues.md:87`, unrelated to this change. Make it right (the user's choices): the
  rename field re-decides while typing; the segment editor's title isolates the name; the merge
  form's checkboxes and Keep options and the reassign picker's options take their names'
  directions; the merge confirmation isolates each name with FSI…PDI (the e2e `turnTextOf` helper
  and the merge sentences follow the markup). Refactors (approved): `c650f667` `IsolatedText`;
  `88a70047` one `direction(element)` test helper — green at `88a70047`: frontend 54 files / 380
  tests, prettier, build.
- **Cycle 5 — 2026-11-25 — queue and history filenames take their own direction; mixed names keep
  their order.** Tests: `frontend/src/features/history/HistoryView.test.tsx` ("shows queue and
  history filenames in their own direction…"), `frontend/src/features/queue/QueueView.test.tsx`
  ("shows a filename in its own direction in an upload row…"). Red at `ee31417e` with the test
  uncommitted: `expected 'ltr' to be 'rtl'` on the queued `محاضرة 2024.wav`. Green: `3d8e64e3`
  (the tree the green run was on) — frontend 54 files / 382 tests, prettier, build; the full
  `pnpm --dir e2e test` 59 passed, 1 skipped, 1 failed — the same unrelated
  `87-writes-wait-for-the-connection` second spec (`docs/known-issues.md:87`). Make it right (the
  user's choices): uploading rows; the transcript page's heading in all three states; filenames
  inside sentences (finish notice, cancel and delete titles, both delete bodies) as isolated
  elements — and, at the user's choice, the merge confirmation moved from cycle 4's FSI…PDI marks
  to the same elements, so no text carries invisible characters and every locator reads the
  sentence as written. Refactors (approved): `da94c525` `FileMessage`; `620e70bc` one page
  heading in `TaskTranscriptView` — green at `620e70bc`: frontend 54 files / 382 tests, prettier,
  build.
- **Cycle 6 — 2026-11-25 — a standalone name or filename with no letter is ltr, whatever the app
  language.** Tests: `frontend/src/features/history/TaskTranscriptView.test.tsx` ("shows a name or
  a filename with no letter as ltr…"), `frontend/src/features/queue/QueueView.test.tsx` ("shows a
  filename with no letter as ltr in the queue and upload rows…"), the shared table's `standalone`
  cases. Red at `79474d22` with the test uncommitted: `expected 'rtl' to be 'ltr'` on the history
  card `٢٠٢٤` under the Arabic app language. Green: `2c2f1537` (the tree the green run was on) —
  frontend 54 files / 389 tests, prettier, build; e2e `08`, `09`, `88` 4 passed. Make it right
  (the user's choices): every standalone place takes the fallback (queue and upload rows, Keep and
  Move-to options); the rule is named once, `standaloneDirection`; the shared table gains a
  `standalone` section checked against it. Refactors (approved): `51863e40` one `StalledXhr`;
  `ebd19293` the test reads its Arabic labels from the catalog — green at `ebd19293`: frontend 54
  files / 389 tests, prettier, build.
- **Cycle 7 — 2026-11-25 — (e2e) a seeded RTL transcript mirrors per turn at desktop and phone
  widths, with nothing clipped.** Test: `e2e/tests/76-rtl-transcript.spec.ts`, seeded by
  `e2e/lib/seed.ts` (the task's rows inserted through `node:sqlite` while the service is stopped,
  Decided 2026-10-28). Red at `ce57b1d6` with the spec uncommitted: `SyntaxError: The requested
  module '../lib/backend.ts' does not provide an export named 'seedCompletedTask'`. Green:
  `cd1cc969` (the tree the green run was on) — the full `pnpm --dir e2e test` 60 passed, 1
  skipped, 1 failed — the unrelated `87-writes-wait-for-the-connection` second spec
  (`docs/known-issues.md:87`). Make it right (the user's choices): the restart in a `finally`
  around the seeding; start alignment of each turn's first line and speaker name, `أحمد` rtl; the
  same checks under the Arabic app language. The seeded task has no audio, so no player is on its
  page. Refactors (approved): `2e261513` `startSharedBackend` (13-restart's handover too);
  `0b2ff50a` `readTurnRows` — green at `0b2ff50a`: e2e `tsc`, prettier, `13-restart`,
  `76-rtl-transcript` and `88-localization` passed.
- **Cycle 8 — 2026-11-25 — the open segment editor is aligned by the segment's own first letter,
  not its paragraph's.** Tests: `frontend/src/features/history/TaskTranscriptView.test.tsx`
  ("aligns the open segment editor…", "gives the editor of a segment with no letter…"). No red: at
  `3cb73e93` the test passed at its first run (the field's `dir="auto"` already resolved from the
  draft; a first version failed on a locator only — the page behind the modal is hidden from the
  accessibility tree). Green: `be9a2af6` (the tree the green run was on) — frontend 54 files / 390
  tests, prettier, build; e2e `52-edit-segment`, `52-edit-journey`, `52-keyboard-trial` 8 passed.
  Make it right (the user's choices): the field takes the app's rule from the draft, a draft
  without a letter its paragraph's direction or the app language's (Decided, 2026-11-25); item
  52's `dir="auto"` attribute pin deleted. Refactors (approved): `90a88592` `openSegmentEditor`
  takes a catalog; `b4b17cd4` `TextDirection` moved to `shared/textDirection.ts` — green at
  `b4b17cd4`: frontend 54 files / 390 tests, prettier, build.
- **Cycle 9 — 2026-11-25 — an RTL-first paragraph is rtl whatever the task's language, digits and
  punctuation before it included.** Test: `frontend/src/features/history/TaskTranscriptView.test.tsx`
  ("makes an RTL-first paragraph rtl…"). No red: at `151a7df1` the test passed at its first run
  (the rule was built in cycles 1–2). Green: `4cba031e` (the tree the green run was on) — frontend
  54 files / 391 tests, prettier, build. Make it right (the user's choice): each render proves its
  task language through the transcript's `lang` (item 74): `en` for the `en` task, none for
  `auto`. Refactor (approved): `68c93201` `serveSnapshot` replaces twelve inline snapshot mocks —
  green at `68c93201`: frontend 54 files / 391 tests, prettier, build.
- **Rebase — 2026-11-25.** At the user's request the branch was rebased onto `main` `5b91c047`
  (`AGENTS.md` only: the next-test proposal carries the cycle's outcomes). Every hash above and in
  cycle 10's red and green is the pre-rebase commit the run was on; each rebased tree differs from
  it by `AGENTS.md` alone.
- **Cycle 10 — 2026-11-25 — direction is per paragraph: a Latin-first turn after an RTL one is
  ltr, and the RTL ones stay rtl.** Test: `frontend/src/features/history/TaskTranscriptView.test.tsx`
  ("decides the direction per paragraph…"). No red: at `42c1e511` (pre-rebase) the test passed at
  its first run. Green: `d43b6d4e` (pre-rebase; the tree the green run was on; rebased as
  `f7b6bc99`) — frontend 54 files / 392 tests, prettier, build. Make it right (the user's choice):
  the other order too (English, Arabic, English → ltr, rtl, ltr). Refactor (approved): `c27a28ec`
  `readDirections`, which the RTL-first test uses too — green at `c27a28ec`: frontend 54 files /
  392 tests, prettier, build.
- **Cycle 11 — 2026-11-25 — when no turn has a letter, the paragraphs take the app language's
  direction.** Test: `frontend/src/features/history/TaskTranscriptView.test.tsx` ("gives the
  paragraphs of a transcript without any letter…"). No red: at `51ede299` the test passed at its
  first run (such turns carry no `dir` since cycle 1). Green: `c65ebd0d` (the tree the green run
  was on) — frontend 54 files / 393 tests, prettier, build. Make it right (the user's choice): the
  whole turn rows follow the app language too, ltr under English and rtl under Arabic. Refactor
  (approved): `403c8f91` one `rowDirection` for the direction tests — green at `403c8f91`:
  frontend 54 files / 393 tests, prettier, build.
- **Cycle 12 — 2026-11-25 — a Latin-first mixed paragraph is ltr with its Arabic run ordered rtl
  inside it.** Tests: `frontend/src/features/history/TaskTranscriptView.test.tsx` ("keeps a
  Latin-first mixed paragraph ltr…") and `e2e/tests/76-rtl-transcript.spec.ts` (the seeded turn
  `WHO: مرحبا بكم`). No red: at `714f5506` both passed at their first run. Green: `3fb343a1` (the
  tree the green run was on) — frontend 54 files / 394 tests, prettier, build; e2e prettier,
  `tsc`, `76-rtl-transcript` passed. Make it right (the user's choice): the mirror case — inside
  the RTL turn `في عام 2024 …` the number keeps its digit order and sits left of the words before
  it. Refactors (approved): `2f0b094f` `expectRunOrder`; `b65f7fe8` `WITH_NUMBER` beside `MIXED`
  — green at `b65f7fe8`: e2e `tsc`, prettier, `76-rtl-transcript` passed.
- **Cycle 13 — 2026-11-25 — the txt/srt/vtt downloads of an RTL transcript are the stored text
  byte for byte.** Tests: `backend/src/test/kotlin/stt/http/TranscriptDownloadSpec.kt` (a task
  with the speaker `أحمد`, an Arabic segment with a number and `WHO: مرحبا`: txt, srt and vtt equal
  hand-written UTF-8 bytes) and `e2e/tests/76-rtl-transcript.spec.ts` (the seeded task's txt in
  Chromium is the stored turns exactly). No red: at `3479d410` the backend test passed at its first
  run. Green: `ae09b599` (the tree the green run was on) — backend `gradle check` (ktlint, 461
  tests, 1 skipped); e2e prettier, `tsc`, `07-downloads`, `76-rtl-transcript` passed. Make it
  right (the user's choice): the end-to-end download — which found that the browser saves the file
  as `transcript.txt`: the server sends the name as raw UTF-8 in `filename=`
  (`backend/src/main/kotlin/stt/outputs/DownloadFileName.kt`, `stt/http/TranscriptRoutes.kt:67`),
  which the browser ignores. At the user's choice it is a discovered item of its own, red first.
  Refactor (approved): `b1af810a` `completedTask` in the download spec — green at `b1af810a`:
  backend `gradle check` (ktlint, 461 tests, 1 skipped).
- **Cycle 14 — 2026-11-25 — (discovered) a download of a task with a non-ASCII name keeps that
  name.** Tests: `backend/src/test/kotlin/stt/http/TranscriptDownloadSpec.kt` (exact
  `Content-Disposition` for an ASCII name, an Arabic name and a name with `"`, `\`, `%`, `;`) and
  `e2e/tests/76-rtl-transcript.spec.ts` (Chromium saves `محاضرة الأحد.txt`). Red at `80c5f2f6`
  with the tests uncommitted: no `filename*` in the header; the e2e download saved as
  `transcript.txt`. Green: `40fc7dcd` (the tree the green run was on) — backend `gradle check`
  (ktlint, 462 tests, 1 skipped); e2e prettier, `tsc`, `07-downloads`, `08-rename`,
  `76-rtl-transcript` passed. Make it right (the user's choices): `attachmentDisposition` in
  `stt/outputs/DownloadFileName.kt` (RFC 6266/5987: `filename*=UTF-8''…` beside an ASCII fallback
  with each non-ASCII character, `"` and `\` as `_`); the edge cases pinned; the contract in
  `docs/specs/output-formats.md` and `docs/api/openapi.yaml`. Refactoring: none (the user
  agreed; two small pure functions side by side, composed in one line).
- **Cycle 15 — 2026-11-25 — (discovered) an empty segment's placeholder never decides its turn's
  direction.** Test: `frontend/src/features/history/TaskTranscriptView.test.tsx` ("decides a turn's
  direction from its stored texts…"). No red: at `79650bf6` the test passed at its first run (the
  stored segment texts decide since cycle 1). Green: `6e138887` (the tree the green run was on) —
  frontend 54 files / 395 tests, prettier, build. Make it right: nothing to improve (the user
  agreed: both directions of the conflict covered; the placeholder's own display is the browser's
  bidi, like any embedded run). Refactor (approved): `f38a2450` `turnsOf` builds multi-segment
  turns — green at `f38a2450`: frontend 54 files / 395 tests, prettier, build.
- **Cycle 16 — 2026-11-25 — the Rust direction rule passes the shared table (the TUI's part of
  the discovered table item).** Test: `client/stt-client/src/direction.rs` (the table's letters,
  turns and standalone sections). Red at `17523bac` with the test uncommitted: `error[E0432]:
  unresolved imports super::Direction, super::letter_direction, super::standalone_direction,
  super::turn_directions`. Green: `48676d03` (the tree the green run was on) — client `cargo fmt
  --check`, `cargo clippy --all-targets -- -D warnings`, `cargo test` (301 passed, 3 ignored).
  Make it right (the user's choices): the rule lives in the shared library crate as
  `stt_client::direction` (one Rust implementation for the TUI and item 70's window; in the
  `stt-tui` binary clippy failed on every item as dead code), a letter is `\p{L}` through the
  `regex` crate, with doc comments matching the TypeScript ones. Refactor (approved): `dd5e277c`
  typed table cases — green at `dd5e277c`: `cargo fmt --check`, clippy, the `stt-client` tests.
  The item stays open for the window's part (item 70).
- **Cycle 17 — 2026-11-25 — the TUI draws a seeded RTL transcript in visual order, its RTL turns
  mirrored.** Red at `02133c23` with the test uncommitted: the mixed turn's cells stayed in
  logical order and the Arabic turn's header sat on the left. Green: `e72b85d1` (the tree the
  green run was on) — client `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`,
  `cargo test` (302 passed, 3 ignored). Make it right (the user's choices): the header's speaker
  name is ordered by its own first strong character and the whole header mirrors; a paired
  bracket in an RTL run is mirrored; the wrapped rows keep the indent on the turn's start side.
  Refactor (approved): `9b7e0f8e` `visual_order` and `header_row` — green at `9b7e0f8e`: client
  `cargo fmt --check`, clippy, `cargo test` (302 passed, 3 ignored).

- **Cycle 18 — 2026-11-25 — the TUI's RTL rows take the hit map, the rename caret and the
  playing fill.** Tests: `client/stt-tui/tests/rtl_transcript.rs` (a click on each of the three
  rows marks its segment; the rename caret sits at the end of an Arabic name; the playing
  segment's row is filled in the accent role). The hit map and the rename caret passed at their
  first run; the playing row's red: the duration text was the clip's, not the task's. Green:
  `09691374` (the tree the green run was on) — client `cargo fmt --check`, `cargo clippy
  --all-targets -- -D warnings`, `cargo test` (305 passed, 3 ignored). Make it right (the user's
  choices): the hit map checks all three rows; the rename caret; the playing fill. Refactor
  (approved): the transcript opener takes the TUI's environment, in the same commit.

- **Cycle 19 — 2026-11-25 — the TUI's `?` reference names the emulator's reorder setting when the
  environment names one.** Test: `client/stt-tui/tests/help_bidi.rs` (with `TERM`/`MLTERM` naming
  mlterm the reference names the setting and why it must be off; with no such name the notice is
  absent). Red at `3dd37e40` with the test uncommitted: the help table had no notice. Green:
  `50cfd2ee` (the tree the green run was on) — client `cargo fmt --check`, `cargo clippy
  --all-targets -- -D warnings`, `cargo test` green; the help test's 1 passed. Make it right (the
  user's choices): the notice moved to the catalog (`tui.help.reorder`, 28 locales and 27 review
  tables); the view reads the environment once into a `reorders` field; the notice splits into two
  lines rather than widening the table; the test asserts the notice's absence. Refactor (approved):
  `45601ab5` — the `HELP_CHROME` doc comment names the notice's rows, and `notice_lines`'s split at
  the colon is documented on the function; the test's setup duplication and the environment read
  were left as they are, with the reasons recorded.

- **Cycle 20 — 2026-11-25 — the final verification.** No new test: the "(must not break)" gate on
  the final tree. Backend `gradle check` green; client `cargo fmt --check`, `cargo clippy
  --all-targets -- -D warnings`, `cargo test` green; TUI journey `gradle tuiJourney` 3 passed;
  frontend `pnpm test` 395 passed, prettier clean; e2e `pnpm --dir e2e test` 60 passed and the RTL
  spec "a seeded RTL transcript mirrors per turn at desktop and phone widths, with nothing clipped"
  passed, the run's one failure being the documented default-mode issue at
  `87-writes-wait-for-the-connection`'s second spec (`docs/known-issues.md:87`: the first spec
  restarts the per-user service whose sessions live in the process; CI's `gradle e2e` mode sets
  `E2E_BACKEND_CMD` and does not hit it). The RTL spec also passed alone in 24.4s. The two
  `(after item 70)` items stay unticked: they are item 70's implementation.

## Approval log

- 2026-10-23: filed as a draft at the user's request (right-to-left writing systems in the
  transcription and correct layouting); not yet discussed.
- 2026-10-23: the user fixed the direction rule: **the first alphabetic character of a paragraph
  (not numbers, not punctuation) determines that paragraph's direction**, and the same rule applies
  to speaker names. Direction is therefore content-derived (Unicode first-strong, `dir="auto"`), per
  paragraph, with no language metadata or stored detected language; the draft was rewritten
  accordingly (the language-contract field proposal is gone).
- 2026-10-23: the user added the segment-editor interaction (item 52): in edit mode the textarea
  takes the segment's own first-alphabetic direction, independent of the paragraph; it is the one
  sub-paragraph exception and does not overrule the paragraph rule. Item 52's spec and test list
  were amended in the same change.
- 2026-10-28: consultation with the user: the e2e seeds a completed RTL task's rows into the SQLite
  fixture, `dir="auto"` is the mechanism, and the Slint window implements the same rule (so this
  item depends on item 70), while the TUI's terminal-bidi limitation is recorded as unsupported.
  Test list approved as written; status set to spec-approved.
- 2026-11-17 (amendment, the user's instruction): the TUI is no longer recorded as unsupported — it
  draws bidi itself (the Unicode bidi algorithm per wrapped line, with the cursor, selection and
  mouse-hit maps inverted through the visual order), so the window and the TUI draw the same order.
  Glyph shaping is the recorded difference the user accepts, and a terminal that applies its own
  bidi (mlterm with bidi enabled) is the one unsupported setting. The window's test item became
  three (TUI order, TUI hit mapping, window order equals the TUI's), and, at the user's instruction
  the same day, one item covers the help reference's notice for a bidi-aware emulator: 15 items in
  total.
- 2026-11-22 (workspace, the user's decisions): the app implements the first-strong rule itself
  instead of relying on `dir="auto"` (a Chromium 156 probe showed `dir="auto"` misses the
  no-letter fallback, the reading column and the empty-segment placeholder; the TUI and the Rust
  window need their own implementation anyway); the UI's direction follows the app language (item
  88), not the transcript, which rewords the reading-column item; one discovered item added (the
  empty-segment placeholder): 16 items.
- 2026-11-23 (workspace, the user's decisions): each turn row — timestamp, speaker name and text —
  follows its paragraph's direction (the reading column's first-paragraph mirror is gone); a
  speaker name in a turn is placed by the turn and ordered by its own first letter; a turn with no
  alphabetic character takes the previous turn's direction (at the start, the next turn's with a
  letter; with none, the app language's); one discovered item added (a shared table of direction
  cases every implementation passes): 17 items. Items 3, 5 and 6 reworded accordingly.
  The same day, at the user's choice, the no-letter item was split into three (previous/next turn,
  no letter in any turn, standalone name or filename): 19 items.
- 2026-11-25 (workspace, the user's choice): one discovered item added — a download of a task with
  a non-ASCII name keeps that name (the end-to-end download of cycle 13 found it saved as
  `transcript.txt`): 20 items.
