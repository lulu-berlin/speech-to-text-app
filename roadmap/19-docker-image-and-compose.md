# 19 — Docker image and docker-compose setup

Status: draft
Depends on: [16](16-nix-flake-package.md)

## Goal

Make the app easy to build and run with Docker: a container image of the packaged app (item 16) and a `docker-compose.yml` that starts it with persistent data on `http://localhost:8080`, with the GPU exposed the way Docker documents it.

## Already decided

- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): the Nix flake is the deliverable; the image packages it rather than replacing it.
- `AGENTS.md`: dependencies come only from `devenv.nix` and `flake.nix`, so the image's contents come from the flake; no `apt`/`apk` installs in a Dockerfile.
- [ADR 0017 — Database location and connection contract](../docs/decisions/0017-database-location-and-connection-contract.md): `STT_DATA_DIR` is required and has no default, so the compose file sets it to a path on a volume.
- [ADR 0020 — Process runner](../docs/decisions/0020-process-runner-jna-signals-process-groups.md): orphaned processes must be reaped; with the JVM as PID 1 and no init, cancelled process trees leave zombies (the runner then logs a WARN instead of waiting), so the container runs an init as PID 1.
- [ADR 0024 — Audio storage](../docs/decisions/0024-audio-storage-original-until-chunking-flac-chunks.md): the whole data directory (`stt.db` and `work/`) holds the only copy of the audio, so it lives on one persistent volume.
- **The GPU story (2026-10-09, corrected by the user)**: Docker exposes host GPUs to the container — `docker run --gpus all`, by device index, or by UUID — with the NVIDIA Container Toolkit ([Docker's GPU docs](https://docs.docker.com/engine/containers/gpu/)). The image's whisper-cli is the closure's Vulkan build, which runs on NVIDIA through the driver's Vulkan ICD and on Intel/AMD through Vulkan. This reinstates item 19 after a brief abort whose "GPU is impossible in a container" claim the user disproved; a plain-tools recipe that briefly replaced the item was dropped.
- User decisions: a **multi-stage Dockerfile** whose builder stage runs Nix (portable: only Docker needed on the host); **models baked in at build time**; **build locally only**; **`curl` for the healthcheck**.

## Proposal (pending approval)

- **Image** built from item 16's flake closure by a multi-stage Dockerfile:
  - builder stage on `nixos/nix`: `nix build .#stt-app` (item 16's package: wrapper, JRE, frontend, Vulkan whisper-cli, ffmpeg, `setsid`, both models);
  - runtime stage: the closure's files (the exact copy mechanism — store path copying with an env shim, or similar — is settled at the first checkpoint), a non-root user, PID 1 = an init (`tini` from the closure or `compose init: true`); `STT_DATA_DIR=/data`; port 8080.
  - The closure carries the Vulkan whisper-cli, `ffmpeg`, `util-linux`'s `setsid` and the models, so the image needs the host only for the GPU device and driver libs.
- **GPU** (the corrected matrix, recorded as decided):
  - **NVIDIA:** run with `--gpus all` (or `--gpus device=N`) plus the NVIDIA Container Toolkit on the host; the Vulkan build uses the driver's Vulkan ICD. Compose exposes this via a `gpu-nvidia` profile.
  - **Intel / AMD:** run with `--device /dev/dri`; compose `gpu-vulkan` profile.
  - **CPU:** the default profile; `STT_DEVICE=cpu`.
  - **macOS:** Docker Desktop runs the Linux image CPU-only (no GPU passthrough into Linux containers); documented.
  - **TPU:** out of scope — whisper.cpp has no TPU backend.
- **`docker-compose.yml`** at the repository root: one service `stt-app`; a named volume at `/data`; `8080:8080`; a healthcheck `curl -fs http://localhost:8080/api/health`; a restart policy; the two GPU profiles; `init: true` if no `tini`.
- **Documentation:** the README section on running the app gains the Docker commands (build, start, stop, where the data lives, how to back it up) and the GPU matrix above.
- Docker itself is not part of the dev environment: all checks of this item are run by the user on a machine with Docker; the agent cannot run Docker in its sandbox.

## Open questions (answered 2026-10-09)

- How is the image built? → **A multi-stage Dockerfile that runs Nix in the builder stage**, so a host with only Docker can build it.
- Models? → **Baked into the image at build time** (the closure includes them).
- GPU? → **`--gpus all` / `/dev/dri` passthrough**, as the user corrected: NVIDIA via the toolkit, Intel/AMD via Vulkan, CPU default, macOS CPU-only, TPU out of scope.
- Publish? → **Build locally only** (documented commands, no registry).
- Healthcheck? → **`curl` from the flake.**

## Remaining open questions

None.

## Test list (written with the user; Canon TDD step 1)

Seed — to be refined and approved with the user before any test is written. All items are verified by the user on a machine with Docker:

- [ ] From a fresh clone with only Docker installed, the documented command builds the image and starts the app on `http://localhost:8080`
- [ ] `GET /api/health` answers `{"status":"ok"}` and the compose healthcheck reports the service healthy
- [ ] Data survives `docker compose down` and `up`: a finished transcription is still in the history
- [ ] Uploading a short clip in the containerized app completes with a transcript
- [ ] A task interrupted by `docker compose stop` resumes after `docker compose start` (item 11's restart recovery)
- [ ] Cancelling running tasks leaves no zombie processes in the container (PID 1 init)
- [ ] The container runs as a non-root user and writes only to `/data`
- [ ] The image contains only the flake closure's files (plus the init)
- [ ] On a host with an NVIDIA GPU and the container toolkit, `docker compose --profile gpu-nvidia up` uses the GPU (`STT_DEVICE=auto`, `nvidia-smi`-visible); on an Intel/AMD host, the `gpu-vulkan` profile uses `/dev/dri`

## Acceptance criteria

- User-run: the documented commands build and start the app with Docker; a short clip is transcribed; data persists across container restarts.
- On the available hardware, at least one GPU profile is demonstrated (NVIDIA or Vulkan), with the others documented as unverified.
- All test-list items ticked and verified; user approved the diff.

## Approval log

- 2026-10-09: item restored after a brief abort. The abort claimed the GPU requirement could not be delivered through a Linux container; the user pointed at `docker run --gpus all` and the reversal stuck. A plain-tools recipe that had replaced the item was dropped. The GPU matrix above is decided with the user.