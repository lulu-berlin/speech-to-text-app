# 75 — Retire the Python oracle: the app is the behavioral reference

Status: draft
Depends on: —
Blocks: —

## Goal

`docs/oracle/transcribe.py` is removed and no living document names it as the behavioral reference:
the app's code, tests, specs and `docs/api` examples are the authority. The no-Python rule stays
(now with no exception), and the app's current behavior — including `[Speaker 1]` in diarized txt —
is the behavior; no further oracle parity work is done.

## Evidence

- The oracle is named as the reference in **AGENTS.md:5** (project description), **AGENTS.md:15**
  (the no-Python rule: "read it to port behavior"), **AGENTS.md:39** (Canon TDD expected values may
  be derived from "the oracle's logic"), **ADR 0002** (its whole premise), and
  **ADR 0001's Context** (historical); `docs/backend-reimplementation.md:13,347,364,523,666-668`
  lists it as an authoritative source and derives conformance from it.
- The app already deliberately deviates where the oracle was wrong or its output was unwanted:
  - **ADR 0034**: txt prints the stored speaker name, so the default header is `[Speaker 1]` and
    txt differs from the oracle's `[speaker 1]` by one character;
  - **ADR 0030**: json segment text is trimmed (and **ADR 0035** drops the json download);
  - **ADR 0027**: the one-speaker rule replaces the oracle's buggy count.
  Parity has therefore been partial for a long time; the user's decision (2026-10-23) is that the
  app, not the oracle, is the reference.
- Live code and test comments still cite the oracle as the source of behavior (e.g.
  `diarization/Clustering.kt:11` "Ported from oracle `cluster_segments`", output-format specs);
  historical ADRs and moved specs are records and keep their text.
- `docs/oracle/transcribe.py` is the repository's only Python file (`git ls-files '*.py'`).
- The decision needed on the `.txt` casing row (app `[Speaker 1]` vs oracle `[speaker 1]`) is
  answered by this item: the app's casing stays.

## Decided (2026-10-23, user)

- **The oracle is dead** (the user): "At this point our app is better than the oracle and we don't
  need to keep chasing parity." The app's behavior and specs are the reference; the txt header keeps
  `[Speaker 1]`.

## Proposal

1. Delete `docs/oracle/` (git history keeps the file); no Python remains in the tree.
2. A new **ADR 0060** supersedes ADR 0002: no Python in the app, build or tests (unchanged), the
   oracle retired, and the app's code/tests/specs/`docs/api` as the behavioral authority. The
   decisions index marks 0002 as superseded by 0060.
3. Update AGENTS.md's project line and no-Python rule, and Canon TDD's expected-value wording
   (hand calculation or the spec; the oracle is not a source).
4. Update `docs/backend-reimplementation.md`: drop the oracle from the authority list and section 15,
   and stand its "oracle logic" references on the current behavior/specs it already documents.
5. Reword live source/test comments that present the oracle as the source of truth to name the spec
   or the current behavior; leave historical ADRs and moved specs untouched.
6. Run the docs checks (links, architecture names) and the full suites.

## Open questions

- Whether ADR 0002 is superseded or amended in place — ADRs are historical records here, so a new
  ADR 0060 supersedes it; 0002's text stays.
- How far to reword code comments: only the claim (the behavior is the spec's / the app's), not the
  historical rationale where it explains a past port.
- Whether the docs package (item 54) should gain a guard that fails when a live document names the
  oracle again; the first cycle decides after 54 exists.

## Test list (proposed; to be approved at the consultation)

- [ ] No Python file is tracked (`git ls-files '*.py'` is empty) and `docs/oracle/` is gone
- [ ] No live document (AGENTS.md, README.md, docs/architecture.md, docs/conventions, the roadmap)
      names the oracle as a behavioral reference or asks for oracle parity; historical ADRs and
      moved specs may still mention it
- [ ] The decisions index shows ADR 0060 superseding ADR 0002, and ADR 0060 states the authority
      (code, tests, specs, `docs/api`) and the unchanged no-Python rule
- [ ] `docs/backend-reimplementation.md`'s authority list and conformance section name only current
      sources, with no oracle reference
- [ ] App behavior is unchanged: diarized txt keeps `[Speaker 1]`, srt/vtt and the downloads are
      byte-identical, and the output-format suites stay green
- [ ] (must not break) `gradle check`, `gradle integrationTest`, the frontend and e2e `pnpm test`,
      and the docs checks stay green

## Acceptance criteria

- The oracle is deleted, ADR 0060 records the decision, AGENTS and the reimplementation doc are
  updated, live references are gone, and no app behavior changes.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-23: filed from the `docs/known-issues.md` triage after the user's decision to retire the
  oracle (the `.txt` casing row is resolved by it: the app's `[Speaker 1]` stays). Not yet
  discussed.
