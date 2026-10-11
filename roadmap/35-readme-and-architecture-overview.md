# 35 — README and architecture overview

Status: draft
Depends on: —

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
  - what the app does (upload many files, a pausable and reorderable queue, transcription with speaker diarization, history, playback, speaker rename/merge, the output formats) with one or two screenshots;
  - the ways to run it, each with its prerequisites: devenv (now), dev mode (item 34), and sections that items 16 (Nix), 19 (Docker) and 33 (Gradle without Nix) add as part of their acceptance;
  - configuration (`STT_*`), where data lives and how to back it up, licenses and attributions ([docs/attributions.md](../docs/attributions.md)), links to `AGENTS.md` and the architecture overview.
- **`docs/architecture.md`**:
  - one component diagram: browser UI ⇄ Ktor HTTP/SSE ⇄ queue and engine ⇄ SQLite; the engine driving ffmpeg, whisper-cli and the CAM++ model;
  - the life of a task: upload → queued → decoding and chunking → transcription → diarization → output, with pause, cancel, reorder and restart recovery;
  - the data model in a short table, the API and live updates (`docs/api/`, SSE events), the frontend structure (routes, state machines, design system);
  - each section links the specs and ADRs that hold the detail, instead of repeating them.

## Open questions

- Audience of the architecture overview: new contributors (code structure and where to change what) or interested users (how it works), or both with a short "where in the code" part per section?
- Length target: around two screens, or a longer guided tour?
- Screenshots in the README: reuse item 21's captured set, or none?
- Order: write the README now with the devenv path and let items 16/19/33/34 add their sections, or write it after those items so it is complete in one pass?

## Test list (written with the user; Canon TDD step 1)

To be written in the consultation (documentation checks: every command in the README works on the user's machine, every link resolves, every component, path and event name in the architecture overview exists in the code).

## Acceptance criteria

- The user follows the README from a fresh clone and runs the app.
- The user reads the architecture overview and approves it as easy to understand.
- All links resolve; all test-list items ticked; user approved the diff.

## Approval log

- 2026-10-11: drafted from the user's request.
