# 16 — Nix flake package

Status: draft
Depends on: [02](02-repo-skeleton-and-conventions.md), [14](14-frontend-queue-ui.md), [15](15-frontend-history-ui.md)

## Goal
Deliver the app as a Nix flake derivation that runs backend, database, frontend, and whisper model together with one command (`nix run .`).

## Already decided
- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): the Nix flake package is the deliverable.
- [ADR 0002 — No Python, oracle only](../docs/decisions/0002-no-python-oracle-only.md): the package contains no Python runtime for the app.
- [ADR 0003 — Kotlin diarization with CAM++ ONNX](../docs/decisions/0003-kotlin-diarization-campplus-onnx.md): speaker model asset and hash.
- [ADR 0005 — Audio as SQLite BLOB](../docs/decisions/0005-audio-as-sqlite-blob.md): SQLite is the only data store.
- [ADR 0008 — Default whisper model](../docs/decisions/0008-default-whisper-model.md): whisper model asset and hash.

## Proposal (pending approval)
- `flake.nix` (minimal version created in item 02) gains `packages.x86_64-linux.default` = wrapper `stt-app` combining:
  - frontend built with `buildNpmPackage`;
  - backend built with Gradle, dependencies fetched via nixpkgs `gradle.fetchDeps` / mitm-cache lockfile — unverified, confirm against current nixpkgs docs;
  - `whisper-cpp` with `vulkanSupport = true`;
  - `ffmpeg`;
  - `coreutils` (for `kill -s STOP|CONT`);
  - models from `nix/models.nix` (whisper `large-v3-turbo-q5_0` and CAM++ speaker model).
- The wrapper sets all `STT_*` variables (`STT_STATIC_DIR` → built frontend, `STT_WHISPER_CLI`, `STT_FFMPEG`, `STT_WHISPER_MODEL`, `STT_SPEAKER_MODEL`, …), defaults `STT_DATA_DIR` to `${XDG_DATA_HOME:-$HOME/.local/share}/stt-app`, creates it, and starts the JVM.
- SQLite is embedded via the JDBC driver; there is no separate DB service.
- `apps.default` so `nix run .` starts the app on `http://localhost:8080`.

## Open questions for the user
- nixpkgs pin shared with devenv or separate?
- Target NixOS only, or also non-NixOS (Vulkan there needs nixGL)?
- Is a NixOS module / systemd service wanted?

## Test list (written with the user; Canon TDD step 1)
Seed — to be refined and approved with the user before any test is written:
- [ ] `nix build .` produces `result/bin/stt-app`
- [ ] Starting `stt-app` with no `STT_DATA_DIR` set creates the default data directory and the database in it
- [ ] `GET /api/health` on the started app returns `{"status":"ok"}`
- [ ] The started app serves the frontend at `/`
- [ ] The wrapper's `STT_*` variables point at existing files (whisper-cli, ffmpeg, both models, static dir)
- [ ] Uploading a short clip to the packaged app completes with a transcript

## Acceptance criteria
- User-run: `nix build . && ./result/bin/stt-app` serves the UI on `http://localhost:8080`; uploading a short clip completes with a transcript.
- `nix run .` starts the same app.
- The Gradle dependency-fetching approach used has been confirmed against current nixpkgs docs and is recorded (ADR if it is a lasting decision).
- All test-list items ticked and green; user approved the diff.

## Approval log
_Empty — add dated lines (YYYY-MM-DD: what was approved) on approval._
