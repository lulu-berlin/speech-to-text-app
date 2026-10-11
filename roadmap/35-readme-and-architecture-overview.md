# 35 — README and architecture overview

Status: in-progress
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
- 2026-10-11 (workspace, cycle 1 = item 6, recording part): `e2e/tests/gifs-record.spec.ts` records the three flows against the real backend, one webm each under `e2e/screenshots/recordings/` (gitignored): `gif-upload-queue.webm` (desktop), `gif-playback-follow.webm` (1280×500 like the playback journey, so following has to scroll), `gif-speaker-rename-merge.webm` (desktop). Dark mode only (user, this cycle) from the first frame: the context prefers dark and the stored theme choice is seeded as dark. Runs standalone, `pnpm record:gifs` (`playwright.gifs.config.ts`, own fresh backend, so it reuses the journey's fixtures); `playwright.config.ts` ignores it. Reds: (1) Playwright's recordVideo needs the farm's ffmpeg, which `devenv.nix` excluded — user chose enabling it (`withFfmpeg = true`, own commit) over CDP frame capture; (2)–(4) spec mechanics: `video.path()` is async; at 800 px height the long clip's transcript does not overflow its 60vh region, so the playback flow uses the 500 px viewport; `MERGE_CLIP`'s pinned labelling gives Speaker 1 two turns. Item stays open until the user's GIFs are committed and the README shows them with the screenshots.
- 2026-10-11 (user, GIF review): rename/merge too fast and jumpy; playback opened on the top of the page with nothing happening, then jumpy; upload/queue a bit fast but bearable. Changed (workspace): the two transcript flows upload and transcribe in an unrecorded page, so their videos start on the transcript; pauses between steps (1.5 s beats, 0.7 s steps) and the speaker name typed letter by letter; playback frames the player and transcript, plays, clicks the last visible segment so following scrolls line by line (the app's `scrollIntoView({ block: 'nearest' })`) instead of 12 rapid seeks, and hand-scrolls slowly and no further than the region has scrolled (past its top the wheel scrolled the page to the header). Upload/queue unchanged. Recordings now: playback 22.8 s, rename/merge 13.1 s.
- 2026-10-11 (user, GIF review 2): playback opened with the player clipped to its progress bar, then following scrolled the page and the player out of sight. Cause: follow mode's `scrollIntoView({ block: 'nearest' })` scrolls the page too when the target is not wholly on screen, and "Back to playing position" appears right under the 60vh region (off screen, so Playwright scrolled the page to click it). Changed (workspace): the playback flow is 1280×720, framed so the player, the region and the button's spot all fit (measured: region 222 px below the player's top, transcript 490 px, button 42 px; the body gets 60 px bottom padding, below the frame, so the page can scroll that far). New checks: the player is on screen, the page does not scroll while following or after "Back to playing position", the button appears inside the frame. Playback recording 20.7 s.
- 2026-10-11 (user's machine, `pnpm record:gifs`): red — the playback flow's precondition failed (the ~132 s clip's transcript overflowed the 432 px region by under 50 px there; 58 px in the sandbox): the frame was sized to one machine's layout with no slack. Changed (workspace): the playback flow transcribes the ~220 s loop (`CANCEL_CLIP`), whose transcript overflows by hundreds of pixels (precondition now +200 px), at 1280×760 with a 24 px margin under the button's spot, so small layout differences between machines fit. Playback recording 19.4 s.
- 2026-10-11 (user, GIF review 3 → blocked): playback jumped 00:00 → 01:49 (the scripted click-to-seek, no cursor in a video) and "Back to playing position" appeared without scrolling back. Found while reworking it: **an app bug** — follow mode's own scroll is taken for a hand scroll when the scroll event arrives after the `setTimeout(0)` hold-off clears (a throwaway Chromium probe of the same pattern: 21 of 40 trials), so following stops by itself. User decision: fix the app first; a bug-fix item (draft "36 — Follow mode stops by itself") goes to the main-tree agent, and item 6's playback GIF waits for it. The reworked playback flow (setup cut from the video with ffmpeg; starts paused deep in the transcript just before a segment boundary; a 200 px hand scroll that takes the highlight out of view) stays uncommitted in the worktree until then.
- 2026-10-11 (item 36 merged on main; rebased onto it): the rework landed and passed. The rework: setup is cut from the video (ffmpeg re-encode after the first frame of the demo; the system ffmpeg, as in global setup); the shown part starts paused deep in the transcript, just before the deep segment's end (times from the turns API), so following scrolls soon after Play; the hand scroll is 150 px and takes the highlight clearly out of view. Reds while finishing: (1) the frontend dist had not been rebuilt after the rebase, so the recording ran the pre-fix app — the race reappeared; (2) on the rebuilt app the frame still had to change: at 760 px the player lands 16 px under the top, follow scrolls nudge the page up ~20 px, and the browser's focus-scroll on the next mouse click then snaps the page to the top — the frame is now 1280×860 (~56 px above the player), re-framed after the pause click, with the header's shrink transition settled; (3) the long clip's transcript overflows the 860 px frame by only 246 px, so the deep placement is 150 px below the visible part and the hand scroll 150 px. Playback recording 12.6 s.
- 2026-10-11 (user, GIF review 4): rename/merge and upload/queue opened with an instant white frame (flicker), and the playback GIF's hand-scroll + "Back to playing position" read as a glitch. Changed (workspace): the playback flow now ends after following (no hand scroll — user: "it's enough to show the follow-scroll"); the white flash was the app's pre-mount paint (the body gets its theme from styled-components, so before React mounts the canvas is white; measured: the white duration varies run to run, ~0.1–1 s+) — the recordings' init script now paints the root with the dark theme's background token (#14161a) so the boot is dark, flows 1 and 3 cut 600 ms / 1 s of the head, and a guard asserts every recording's first frame is dark; the remaining intermittent white frames came from the re-encode, not the page (output-side `-ss` after `-i` drops them). Two consecutive green runs. Playback 11.4 s, upload/queue ~5 s, rename/merge 11.7 s.
