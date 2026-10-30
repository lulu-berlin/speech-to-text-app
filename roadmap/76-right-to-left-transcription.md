# 76 — Right-to-left transcription: direction-aware transcripts and layout

Status: spec-approved
Depends on: [70](70-rust-gui-window.md) (the Slint window implements the same rule, and it exists
only after 70) — the browser part is independent of 70; [74](../docs/specs/desktop-presentation.md) supplies
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
The same rule applies in the Slint window (item 70), whose bidi support this item checks; the
terminal TUI's lack of bidi is recorded as unsupported.

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

## Decided (2026-10-23, user)

- **The first alphabetic character of a paragraph decides that paragraph's direction**; numbers,
  punctuation and whitespace before it are ignored. This is the Unicode bidi rule for paragraph
  direction (the "first strong character" — letters of the L, R and AL classes), which the browser
  implements with `dir="auto"`.
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

1. **Paragraphs**: set `dir="auto"` on each turn's text (`TurnText`, already a `<p>`), and leave the
   segment spans inline without their own `dir`, so the paragraph keeps one direction and the bidi
   algorithm orders its runs.
2. **Reading column**: the transcript's reading column is `dir="auto"` as well, so it mirrors
   according to its first paragraph (the same rule applied to the container); per-turn paragraphs
   keep their own direction, so an LTR-first turn inside an RTL column aligns LTR.
3. **Layout**: replace direction-dependent geometry in the transcript with logical properties
   (`margin-inline`, `padding-inline`, `inset-inline-*`, `text-align: start/end`); the grid mirrors
   by `direction`. Timestamps, playback controls and the app chrome stay LTR (explicit
   `dir="ltr"` on the player and time elements).
4. **User content outside the transcript**: `dir="auto"` and start alignment on queue/history
   filenames and speaker names; the app shell's own layout stays LTR.
5. **Exports unchanged**: no RTL marks or BOM are added; the same task's txt/srt/vtt bytes are
   pinned before and after.
6. **Tests**: component tests for the rule's cases (RTL-first, Latin-first, digits/punctuation
   before the first letter, no letter at all, mixed runs, names), the container mirror and
   mixed-direction turns; an e2e that produces or seeds an RTL transcript at desktop and phone
   widths; the user's visual sign-off.
7. **ADR** for the direction model: content-derived paragraph direction, `dir="auto"` on
   paragraphs/names/filenames, logical CSS, the fallback rule.

## Decided (consultation, 2026-10-28)

- **The e2e seeds the database**: a completed task with RTL segments is inserted into the fixture
  SQLite database before the app starts — no multilingual model download, no licensed or recorded
  audio committed; the transcript text is asserted exactly.
- **`dir="auto"` is the mechanism**; `unicode-bidi: isolate`/`plaintext` is added only if a test
  shows an inline segment leaking into its paragraph; timestamps and the player stay `dir="ltr"`.
- **A short Latin-first name (`WHO`) stays LTR**: the first-strong rule, not a per-task override, is
  the behavior (the user's stated rule).
- **The TUI is recorded as unsupported** (terminal bidi) — a documented scope line, not a skip in
  the browser tests. **The Slint window implements the same rule** in this item, after item 70
  (this item's dependency); non-DOM consumers use the same first-strong rule.
- Because the window is part of this deliverable, the item starts after item 70; its browser part is
  ready before that.

## Test list (approved; behaviors only)

- [ ] An RTL-first paragraph is RTL even when the task's language is English or `auto`, and even
      when it starts with digits or punctuation (those do not decide)
- [ ] A Latin-first paragraph is LTR even after an RTL paragraph, and the RTL paragraph stays RTL
      (direction is per paragraph, not per transcript)
- [ ] A paragraph with no alphabetic character (digits/punctuation only) falls back to its
      container's direction; a standalone name or filename with no letter falls back to LTR
- [ ] A mixed paragraph (`WHO: مرحبا`) is an LTR paragraph with the Arabic run ordered RTL inside it
- [ ] Speaker names follow the same rule (Arabic-first → RTL, `Speaker 1` → LTR, `12 د. أحمد` → RTL)
- [ ] The transcript's reading column mirrors according to its first paragraph, and the player,
      timestamps and app chrome stay LTR
- [ ] Queue/history filenames in RTL scripts render start-aligned with their own direction, and
      mixed-script names keep their order
- [ ] The same task's txt/srt/vtt downloads are byte-identical before and after
- [ ] (e2e) A seeded RTL transcript in a real browser at desktop and phone widths shows the mirrored
      layout with no overflow or clipped text
- [ ] In edit mode (item 52), the segment editor is aligned by the segment's first alphabetic
      character, independent of its paragraph's direction; closing it restores the paragraph rule
- [ ] (after item 70) The Slint window applies the same paragraph and name direction rule to a
      seeded RTL transcript; the TUI's terminal-bidi limitation is recorded as unsupported
- [ ] (must not break) LTR transcripts, the existing e2e journey, the frontend suite and the
      output-format suites stay green

## Acceptance criteria

- Paragraphs, speaker names and filenames take their direction from their first alphabetic
  character; the transcript mirrors correctly; mixed runs and fallbacks behave as decided; LTR
  content and the downloads are unchanged; an e2e and the user's visual check prove the layout.
- All test-list items ticked and green; user approved the diff.

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
