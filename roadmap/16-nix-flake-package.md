# 16 — Nix flake package

Status: in-progress
Depends on: [02](../docs/specs/repo-skeleton-and-conventions.md) (done), [14](../docs/specs/frontend-queue-ui.md) (done), [15](../docs/specs/frontend-history-ui.md) (done), [17](../docs/specs/branding-stenographer-animation.md) (done), [21](../docs/specs/frontend-backend-end-to-end.md)

## Goal

Deliver the app as a Nix flake derivation that runs backend, database, frontend and the whisper model together with one command (`nix run .`), on Linux. The end-to-end check (item 21) is part of its acceptance: the packaged app is what item 21 boots.

## Already decided

- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): the Nix flake package is the deliverable.
- [ADR 0002 — No Python, oracle only](../docs/decisions/0002-no-python-oracle-only.md): no Python runtime in the package.
- [ADR 0003 — Kotlin diarization with CAM++ ONNX](../docs/decisions/0003-kotlin-diarization-campplus-onnx.md) and [ADR 0008 — Default whisper model](../docs/decisions/0008-default-whisper-model.md): the model assets and hashes.
- [ADR 0024 — Audio storage](../docs/decisions/0024-audio-storage-original-until-chunking-flac-chunks.md): SQLite holds chunk audio and metadata; the data directory is the backup unit.
- [ADR 0012 — pnpm](../docs/decisions/0012-frontend-dependency-management-pnpm.md), [ADR 0014 — Build tooling](../docs/decisions/0014-build-tooling-gradle-catalog-no-wrapper.md), [ADR 0016 — App wrapper](../docs/decisions/0016-flake-scope-correction-app-wrapper.md): how the two halves build.
- [ADR 0020 — Process runner](../docs/decisions/0020-process-runner-jna-signals-process-groups.md): the package ships `util-linux` for `setsid` (the runner's process groups).
- **One shared nixpkgs pin** ([ADR 0036](../docs/decisions/0036-shared-nixpkgs-pin.md), 2026-10-09): the flake's locked nixpkgs input is the single pin; devenv uses the same input, so dev and build cannot drift.
- **Linux only** (2026-10-09): the package targets Linux. macOS was "ideally" but needs a darwin output, a non-Vulkan whisper build and a `setsid` shim, all untestable here; it is recorded as a future draft item.
- **Docker was considered, briefly aborted, and reinstated** (2026-10-09): a first abort claimed the GPU requirement ("works with any kind of GPU, as if running directly on a Linux machine") was not deliverable through a Linux container; the user corrected that by pointing at `docker run --gpus all` ([Docker's GPU docs](https://docs.docker.com/engine/containers/gpu/)). Item 19 returns as the Docker image, built from this item's flake closure, with the GPU matrix decided there: NVIDIA `--gpus all` + the NVIDIA Container Toolkit (the closure's Vulkan whisper-cli uses the driver's Vulkan ICD), Intel/AMD via `/dev/dri`, CPU by default, macOS CPU-only (no passthrough from Linux containers), TPU out of scope (whisper.cpp has no backend). A plain-tools recipe that briefly replaced it was dropped when Docker returned.

## Proposal (approved 2026-10-09)

Starting point (delivered by item 02): `flake.nix` builds `packages.x86_64-linux.{backend,frontend,default}`; `default` (`stt-app`) provides `bin/speech-to-text-app` (a `makeWrapper` around `java` with `CLASSPATH` + `STT_STATIC_DIR=$out/share/stt-app/static`); the Nix build runs `gradle check`; nixpkgs is pinned in `flake.lock`.

Item 16 extends the `default` package with:
- `whisper-cpp` with `vulkanSupport = true` (the engine item 07 drives; the direct-GPU path on a Linux host with a Vulkan driver);
- `ffmpeg`;
- `coreutils` and `util-linux` (for `kill -s STOP|CONT` and `setsid`, ADR 0020);
- the models from `nix/models.nix` (whisper `large-v3-turbo-q5_0` and the CAM++ speaker model);
- the wrapper additionally sets the remaining `STT_*` variables (`STT_WHISPER_CLI`, `STT_FFMPEG`, `STT_WHISPER_MODEL`, `STT_SPEAKER_MODEL`, …), defaults `STT_DATA_DIR` to `${XDG_DATA_HOME:-$HOME/.local/share}/stt-app`, creates it, and starts the JVM;
- `apps.default` so `nix run .` starts the app on `http://localhost:8080`.
- **Shared pin (ADR 0036):** devenv takes the flake's locked nixpkgs input; the exact wiring is settled at the first checkpoint (devenv is itself a flake input of the repo).
- SQLite is embedded via the JDBC driver; there is no separate DB service.

**Not in scope:** a NixOS module or systemd service (2026-10-09) and a darwin output (future item). Docker is a separate delivery on top of this item (item 19), not part of the package itself.

## Open questions (answered 2026-10-09)

- nixpkgs pin shared with devenv or separate? → **Shared** ([ADR 0036](../docs/decisions/0036-shared-nixpkgs-pin.md)).
- Target NixOS only, or also non-NixOS? → **Any Linux**; `nix run .` works outside NixOS (Vulkan if the driver is present). No NixOS module.
- macOS? → **Out of scope here**; a future draft item covers a darwin output and a `setsid` shim.
- Is a NixOS module / systemd service wanted? → **No.**
- Docker? → **Reinstated as item 19** (a Docker image built from this item's closure; the GPU matrix is decided in that item).
- The GPU path? → The **native build is Vulkan** on a Linux host; the Docker image passes the host's GPUs through the same way (`--gpus all` for NVIDIA, `/dev/dri` for Intel/AMD) — see item 19.

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-09 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user. Nix-level items are user-verified (ADR 0010):

- [x] `nix build .` produces `result/bin/stt-app`
- [ ] Starting `stt-app` with no `STT_DATA_DIR` set creates the default data directory and the database in it
- [ ] `GET /api/health` on the started app returns `{"status":"ok"}`
- [ ] The started app serves the frontend at `/`
- [ ] The wrapper's `STT_*` variables point at existing files (whisper-cli, ffmpeg, both models, static dir)
- [ ] Uploading a short clip to the packaged app completes with a transcript
- [ ] `nix run .` starts the same app
- [ ] The devenv resolves its tools from the same locked nixpkgs (ADR 0036)

## Acceptance criteria

- User-run: `nix build . && ./result/bin/stt-app` serves the UI on `http://localhost:8080`; uploading a short clip completes with a transcript.
- `nix run .` starts the same app.
- The packaged app passes item 21's end-to-end boot (item 21 depends on this).
- The Gradle dependency-fetching approach has been confirmed against current nixpkgs docs and is recorded (ADR if it is a lasting decision).
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-09: consultation answers approved: one shared nixpkgs pin (ADR 0036); any Linux, no NixOS module; macOS out of scope (future draft item); Docker aborted (item 19 removed) because the GPU requirement cannot be delivered through a container. Test list not yet approved.
- 2026-10-09: test list (8 items) approved by the user; status `spec-approved`.
- 2026-10-09: Docker reinstated as item 19 after the user corrected the GPU claim (`docker run --gpus all`); a recipe that briefly replaced it was dropped. The GPU matrix lives in item 19.
- 2026-10-11 (workspace, cycle 1 = item 1): first checkpoint — ADR 0036 wiring settled: `devenv.yaml` rev-pins the flake's locked nixpkgs (`github:NixOS/nixpkgs/494ce7f…`), and a `nix flake check` must guard the sync (lands with test 8). Wrapper renamed `bin/speech-to-text-app` → `bin/stt-app` (commit `edf2674`, no behavior change). User-verified green: `nix build . && test -x result/bin/stt-app`.
