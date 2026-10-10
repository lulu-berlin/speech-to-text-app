# 21 — Frontend–backend end-to-end check

Status: spec-approved
Depends on: [12](../docs/specs/http-api-and-sse.md) (done), [13](../docs/specs/frontend-foundation.md) (done), [14](../docs/specs/frontend-queue-ui.md) (done), [15](../docs/specs/frontend-history-ui.md) (done), [22](../docs/specs/speaker-rename.md) (done), [25](../docs/specs/visual-polish.md) (done)

## Goal

Prove the whole product works together on a real machine — an uploaded clip is transcribed, diarized, read, downloaded, renamed, merged and deleted through the real browser UI against the real backend — and obtain the user's approval of the look. This is where the frontend's "against the running backend" criteria and item 25's visual acceptance land.

## Already decided

- [ADR 0029 — Contract-first frontend](../docs/decisions/0029-contract-first-frontend-shared-api-examples.md): items 14 and 15 were built and merged against item 12's contract with MSW mocks; this item is the real-backend check.
- [ADR 0010 — Development toolchain](../docs/decisions/0010-development-toolchain.md): a new development tool is declared in `devenv.nix` (and, for anything the packaged app needs, `flake.nix`), never installed ad hoc.
- [ADR 0021 — Integration tests](../docs/decisions/0021-integration-tests-kotest-tags-gradle-task.md): backend tests that spawn real processes are tagged and run separately.
- The committed LibriSpeech clips (items 08 and 09) and the devenv's tiny whisper model are available; item 09's Integration test already pins their speaker labels (1089 → 1188 → 1089).
- User decisions (2026-10-09): the check is **automated with Playwright**; it **also carries the visual acceptance** (moved from item 25); it depends on items 22 and 25 as well, so it checks the final build; item 16 depends on this item; it transcribes **the committed clips with the tiny model**.

## Proposal (approved 2026-10-09)

- **Layout: a separate top-level `e2e/` package** (its own `package.json` and lockfile), so the frontend package and its flake build are not polluted by a test-only browser stack. Playwright's browser comes from `devenv.nix` (`PLAYWRIGHT_BROWSERS_PATH`), the `@playwright/test` package from `e2e/`'s own lockfile. Point to confirm with the user at the first checkpoint.
  - `devenv.nix` gains Playwright's Chromium (dev only). The **packaged app must not include it**: the flake's backend/frontend derivations are unchanged, and the e2e suite is not part of `nix build` (ADR 0010 still applies to the devenv change, which the user verifies by re-entering the shell).
- **What it runs** (`pnpm --dir e2e test` or an equivalent documented command):
  1. start the backend with a fresh temporary `STT_DATA_DIR`, `STT_STATIC_DIR` pointing at the built frontend, the tiny whisper model and the speaker model from the environment;
  2. wait for `/api/health`, open the app in Chromium at two viewport sizes (phone ~390 px, desktop ~1280 px);
  3. **the journey**: upload the fixture clips (queue several, watch the queue order and both progress bars), wait for COMPLETED, open the transcript, check the turns and the speaker labels, follow the playback highlight and click-to-seek, download txt/srt/vtt and check their contents, rename a speaker, merge a group of speakers, reset all merges, delete a task, cancel a task and retry a failed one, and restart the backend to see the SSE stream reconnect and the state come back from a snapshot;
  4. **contract drift**: assert the responses the browser received match the `docs/api/` examples' structure, so this is also the backstop for the mocks items 14 and 15 were built against;
  5. **visual acceptance**: capture screenshots (light and dark, phone and desktop) of the Queue (a running task and an upload in flight), History, the transcript during playback, and one status page (a canceled task), and present them for the user's approval — this is item 25's acceptance criterion, moved here. Where Playwright cannot judge (is it *nice*?), the user decides from the screenshots.
- **Failure handling**: a failure caused by contract drift is fixed in whichever side deviates from `docs/api/`; a failure in the app is a bug in the item that owns it. The item's log records what was found and fixed.
- **Restarts, cancellation and retries** are exercised here for the first time against real processes; the cleanups item 11 owns (work directories, spooled uploads) are checked from the filesystem.

## Open questions (answered 2026-10-09)

- Automated or a manual checklist? → **Automated with Playwright** (`devenv.nix`), with screenshots for the visual judgement.
- Does it carry the visual acceptance? → **Yes**; item 25's visual approval moves here.
- Dependencies? → **12, 13, 14, 15, 22 and 25**, and **item 16 depends on this item**.
- What does it transcribe? → **The committed LibriSpeech clips with the devenv's tiny model.**

## Remaining open questions

None.

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-09 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user.

- [ ] The suite starts the backend on a temporary `STT_DATA_DIR` and the built frontend, and the app loads at phone and desktop widths
- [ ] The app shell shows the stenographer mascot; loading regions show it (labelled "Connecting…" / "Reconnecting…" as the connection changes), empty regions show it, and it rests while nothing runs (item 17)
- [ ] Uploading several fixture clips queues them in order; the queue shows the status, the phase and both progress bars advancing live
- [ ] Reordering a task with the row menu changes the queue order and survives a reload (item 14)
- [ ] A clip completes and its transcript opens with the expected turns and speaker labels
- [ ] The transcript's audio player is the joined `/audio`: the playing segment is highlighted and the highlight follows seeking and pausing; clicking a segment seeks and plays; follow mode keeps the highlight in view, a hand scroll stops it, and "Back to playing position" resumes — following does not stop by itself after the first follow scroll (the hold-off check; carried from item 15)
- [ ] The downloads (txt, srt, vtt) have the expected contents and content types
- [ ] A speaker is renamed and the transcript, and a fresh download, show the new name
- [ ] A group of speakers is merged: the transcript joins their turns, and "reset all merges" restores them
- [ ] Cancelling a task, and retrying a failed one, work through the UI
- [ ] Deleting a task removes it from History and frees its file for re-upload
- [ ] The History list shows finished tasks with status, finish date and duration, newest first; a FAILED card shows its error
- [ ] Restarting the backend reconnects the event stream and the state is restored from the snapshot
- [ ] The responses the browser received match the structure of their `docs/api/` examples
- [ ] Screenshots (light and dark, phone and desktop) are captured for the user's visual approval

## Acceptance criteria

- Every test-list item passes against the running backend and the real processes.
- The user approves the look from the captured screenshots; `docs/api/`, the backend and the frontend agree, and any drift found is fixed and recorded.
- `devenv.nix` declares Playwright and the user has verified the shell (re-entered) and that `nix build .` is unaffected.
- All test-list items ticked; user approved the diff.

## Approval log

- 2026-10-09: item drafted with the frontend chain, to hold the acceptance criteria of items 13–15 that need the running backend.
- 2026-10-09: consultation answers approved: automated with Playwright (a separate `e2e/` package, browsers from `devenv.nix`, absent from the packaged app); it also carries item 25's visual acceptance via screenshots; it depends on items 22 and 25, and item 16 depends on it; the committed LibriSpeech clips and the tiny model are the fixtures. Test list not yet approved.
- 2026-10-09: test list (11 items) approved by the user; status `spec-approved`.
- 2026-10-10: re-consultation after items 15 and 17 merged: the test list gains the acceptances those items deferred here — the playback checks carried from item 15 (highlight follows seeking and pausing; click-to-seek; follow mode and "Back to playing position", with the hold-off check that following must not stop on its own) and the mascot/loading-region states from item 17. Also added: the row-menu reorder check (item 14) and the History completeness check (status, finish date, duration; a FAILED card shows its error). The screenshot set is fixed: Queue (a running task and an upload in flight), History, the transcript during playback, and one status page (a canceled task), light and dark at phone and desktop widths. All additions approved by the user; status stays `spec-approved`.
