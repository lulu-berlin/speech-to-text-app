# 50 — Electron desktop app

Status: spec-approved
Depends on: [16](../docs/specs/nix-flake-package.md) (done; tool/artifact gathering). The Linux-first
cut does not wait for macOS/Windows: the macOS and Windows packaging inside this item follow
[48](48-macos-support.md)/[49](49-windows-support.md) as they land. (No dependency on them; they must
not block the first Electron build.)
Blocks: —

## Goal

The app is distributable as a desktop application, packaged with Electron and `electron-builder`:
one process starts the backend (bundled JRE), serves the bundled frontend, and opens the app window —
no browser, no Docker, no Nix, no manual tool installation. Quitting the app stops the backend
cleanly (item 26 semantics); data and models live in per-user locations; a first run on a clean
machine works. The first shipped cut is **Linux** (so the item is not blocked by macOS/Windows
access); macOS and Windows desktop builds are produced as items 48/49 land.

## Evidence

Filed 2026-10-13 during the review/portability work (verified reads):

- The app is a self-contained Ktor backend that already serves the built frontend from
  `STT_STATIC_DIR` (`frontend/build.gradle.kts` builds `dist/`), so the Electron shell can be thin:
  spawn the backend, point it at the bundled static dir, open `localhost:$STT_PORT`.
- Runtime needs: a JRE, whisper-cli, ffmpeg, and the onnxruntime/sqlite natives. All four have
  per-platform artifacts already or in planning: whisper/ffmpeg tables in `nix/versions.nix` (darwin
  present; windows-x64 added in item 49); ONNX Runtime and sqlite-jdbc ship per-OS JNIs via Maven; a
  JRE is produced per-OS with `jlink` (backend targets Java 21).
- Models are not bundled in the repo — downloaded at build time (`nix/models.nix`, whisper ~1.5 GB);
  the backend requires `STT_WHISPER_MODEL`/`STT_SPEAKER_MODEL` at runtime.
- The engine already handles clean shutdown (item 26) and the e2e harness already starts/stops the
  backend externally; Electron reuses that exit contract.
- No CI exists (item 51); packaged-app verification uses it where available; Linux e2e can run on
  any Linux runner.

## Decided (consultation, 2026-10-13)

- **Linux-first stepping stone**: the first cut ships for Linux so the item is never blocked by lack
  of macOS/Windows access; macOS/Windows builds come with 48/49.
- **Models**: first-run download into the per-user data dir (keeps the artifact ~1.5 GB smaller),
  reusing the server's existing "missing model" error UX.
- **Bundled JRE**: per-OS `jlink` runtime image included — the "no manual tool installation"
  promise.
- **Unsigned v1**: no code signing, no auto-update; the macOS Gatekeeper "unidentified developer"
  notice is accepted until asked otherwise.
- **Tooling**: `electron-builder` for packaging (cross-platform, standard).

## Proposal

1. Shape: an Electron main process that (a) resolves the per-user data dir (`%APPDATA%` /
   `~/Library/Application Support` / XDG), (b) starts the backend (bundled jlink JRE + fat jar) with
   the UI static dir and model paths set, (c) opens a BrowserWindow on the backend URL, (d) on quit
   asks the backend to stop and waits (no orphan JVM). Backend keeps owning all queue/engine logic;
   Electron is packaging + lifecycle, not a rewrite.
2. Packaging: `electron-builder` config per the decided OSes; per-OS zips; the pinned tool tables in
   versions.nix feed each OS bundle.
3. Models: first-run download into the data dir with the server's missing-model error surfaced.
4. Tests: packaged-app e2e (ADR 0046) on each shipped OS as it lands; runtime behavior unchanged.

## Open questions

- Data-dir + model-download paths for the Linux cut; whether the first-run download is a silent
  background task or a visible progress screen (decide at the first test item).
- Artifact naming/dir layout for the zip (decide at implementation).

## Test list (proposed; behaviors only)

- [ ] (e2e) Launching the packaged Linux app opens the window and the full journey — upload,
      transcribe, view history — completes without a separate browser or terminal
- [ ] Quitting the app stops the backend and its processes (no orphan JVM/whisper/ffmpeg)
- [ ] First run on a clean machine works: data dir + models are provisioned per the decided design,
      and data persists across restarts
- [ ] (as 48/49 land) The packaged app runs on macOS and Windows with their per-user paths
- [ ] (must not break) `gradle check`, `gradle integrationTest`, `pnpm test`, `pnpm build`, backend
      suites and the Linux e2e stay green
- [ ] README documents the desktop app and how it relates to the Docker/Nix paths

## Acceptance criteria

- A distributable Linux desktop build exists (macOS/Windows following 48/49): backend, UI, JRE,
  tools and natives bundled via electron-builder; clean start/stop lifecycle; per-user data; model
  first-run download; documented; verified by the packaged e2e.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-13: filed as the packaging follow-on to the macOS/Windows items.
- 2026-10-13: consultation — Linux-first stepping stone (dependency on 48/49 dropped); model
  first-run download; bundled jlink JRE; unsigned v1 without auto-update; electron-builder. Status:
  spec-approved.