# 35 — README and architecture overview

Status: spec-approved
Depends on: [21](../docs/specs/frontend-backend-end-to-end.md) (its screenshot set and `e2e/` suite)

## Goal

A newcomer understands what the app does, what it needs, and how to build and run it from `README.md`, and understands how it works from one easy-to-read architecture document.

## Already decided

- User requests (2026-10-11):
  - a README that explains what the app does, how to build it, how to run it, and what dependencies are needed;
  - an architecture overview document that is easy to read and understand.
- The repository has no `README.md` today; item 19 already plans a README section for Docker.
- [ADR 0046](../docs/decisions/0046-every-feature-has-an-e2e-test.md): documentation only: exempt from a Playwright test.

## Proposal

To be settled in the consultation. Starting points:

- **`README.md`** at the repository root:
  - what the app does (upload many files, a pausable and reorderable queue, transcription with speaker diarization, history, playback, speaker rename/merge, the output formats) with item 21's screenshots and a few animated GIFs of the main flows;
  - the ways to run it, each with its prerequisites: devenv (now), dev mode (item 34), and sections that items 16 (Nix), 19 (Docker) and 33 (Gradle without Nix) add as part of their acceptance;
  - configuration (`STT_*`), where data lives and how to back it up, licenses and attributions ([docs/attributions.md](../docs/attributions.md)), links to `AGENTS.md` and the architecture overview.
- **Animated GIFs**: recorded by a Playwright spec in `e2e/` (video recording of the real app) and converted with ffmpeg (from devenv) by the user; proposed flows: uploading files and watching the queue progress, transcript playback with follow mode, renaming and merging speakers; each GIF kept small (a few MB at most), committed next to the screenshots.
- **`docs/architecture.md`**:
  - one component diagram: browser UI ⇄ Ktor HTTP/SSE ⇄ queue and engine ⇄ SQLite; the engine driving ffmpeg, whisper-cli and the CAM++ model;
  - the life of a task: upload → queued → decoding and chunking → transcription → diarization → output, with pause, cancel, reorder and restart recovery;
  - the data model in a short table, the API and live updates (`docs/api/`, SSE events), the frontend structure (routes, state machines, design system);
  - a guided tour: each subsystem walked through in depth, in reading order, each section ending in a short "where in the code" part;
  - each section links the specs and ADRs that hold the decisions, instead of repeating them.

## Open questions (answered 2026-10-11)

- Audience of the architecture overview? → **Both**: how the app works, plus a "where in the code" part per section.
- Length? → **A longer guided tour** through each subsystem.
- Screenshots? → **Item 21's set**, plus **animated GIFs** of the main flows (user: "it would be nice to have some animated gifs").
- When? → **Now**: the README starts with the devenv path; items 16, 19, 33 and 34 each add their section as part of their acceptance.

## Remaining open questions

None.


## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-11 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user:

- [ ] Following the README from a fresh clone in the devenv shell, every command works and the app runs on the user's machine (user-run)
- [ ] The README states what the app does, the prerequisites of each way to run it, the configuration variables, where the data lives and how to back it up
- [ ] Every relative link and image in `README.md` and `docs/architecture.md` resolves
- [ ] Every file path, component, table, endpoint and event name the architecture overview mentions exists in the code
- [ ] The architecture overview's diagram matches the running system: each box is a real component, each arrow a real call or event
- [ ] The GIF recording spec runs against the real backend and produces the GIFs of the agreed flows; the README shows them and the screenshots
- [ ] The user reads the architecture overview and approves it as easy to understand

## Acceptance criteria

- The user follows the README from a fresh clone and runs the app.
- The user reads the architecture overview and approves it as easy to understand.
- All links resolve; all test-list items ticked; user approved the diff.

## Approval log

- 2026-10-11: drafted from the user's request.
- 2026-10-11: consultation answers: both audiences with "where in the code" parts; a longer guided tour; item 21's screenshots plus animated GIFs (so this item depends on 21); README now, extended by 16/19/33/34. Proposed test list (7 items). Remaining: the GIF flows; test list approval.
- 2026-10-11: the three GIF flows (upload and queue progress; transcript playback with follow mode; speaker rename and merge) approved; the GIFs are made small, the recording spec produces the source videos and the user creates the GIFs on their machine. Test list approved; status `spec-approved`.
