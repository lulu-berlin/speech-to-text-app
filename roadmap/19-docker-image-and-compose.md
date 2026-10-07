# 19 — Docker image and docker-compose setup

Status: draft
Depends on: [16](16-nix-flake-package.md)

## Goal
Make the app easy to build and run with Docker: a container image of the packaged app (item 16) and a `docker-compose.yml` that starts it with persistent data on `http://localhost:8080`.

## Already decided
- [ADR 0001 — Stack and monorepo](../docs/decisions/0001-stack-and-monorepo.md): the Nix flake is the deliverable; the image packages it rather than replacing it.
- `AGENTS.md`: dependencies come only from `devenv.nix` and `flake.nix`, so the image's contents come from the flake; no `apt`/`apk` installs in a Dockerfile.
- [ADR 0017 — Database location and connection contract](../docs/decisions/0017-database-location-and-connection-contract.md): `STT_DATA_DIR` is required and has no default, so the compose file sets it to a path on a volume.
- [ADR 0020 — Process runner](../docs/decisions/0020-process-runner-jna-signals-process-groups.md): orphaned processes must be reaped; with the JVM as PID 1 and no init, cancelled process trees leave zombies (the runner then logs a WARN instead of waiting), so the container runs an init as PID 1.
- [ADR 0024 — Audio storage](../docs/decisions/0024-audio-storage-original-until-chunking-flac-chunks.md): the whole data directory (`stt.db` and `work/`) holds the only copy of the audio, so it lives on one persistent volume.

## Proposal (pending approval)
- **Image** built from item 16's flake package: the app wrapper, JRE, frontend, `whisper-cli`, `ffmpeg`, `util-linux` (`setsid`) and both models, with no other packages. How it is built is an open question (Nix `dockerTools` output vs a multi-stage Dockerfile that runs Nix in its builder stage).
- **Runtime:** the container runs as a non-root user; an init (`tini`, or compose `init: true`) is PID 1; `STT_DATA_DIR=/data`; port 8080 exposed.
- **`docker-compose.yml`** at the repository root: one service `stt-app`; a named volume mounted at `/data`; port `8080:8080`; a healthcheck against `GET /api/health`; a restart policy.
- **Documentation:** the README section on running the app gains the Docker commands (build, start, stop, where the data lives, how to back it up).
- Docker itself is not part of the dev environment: all checks of this item are run by the user on a machine with Docker; the agent cannot run Docker in its sandbox.

## Open questions for the user
- How is the image built?
  - Nix `dockerTools.streamLayeredImage` as a flake output, loaded with `nix run .#docker-image | docker load`: reproducible and small, but the host needs Nix.
  - A multi-stage Dockerfile whose builder stage runs Nix (`nixos/nix`) on the flake and copies the closure into a minimal runtime stage: `docker compose build` then works with only Docker on the host.
- Models: baked into the image (about 600 MB more, works offline), or downloaded into the volume on first start?
- GPU: CPU-only image, or an optional compose profile passing a Vulkan GPU through (`/dev/dri`; NVIDIA needs the container toolkit)?
- Publish the image to a registry (e.g. GHCR), or build locally only?
- Healthcheck tooling: add `curl` to the image (from the flake), or check health another way?

## Test list (written with the user; Canon TDD step 1)
Seed — to be refined and approved with the user before any test is written. All items are verified by the user on a machine with Docker:
- [ ] From a fresh clone, the documented commands build the image and start the app on `http://localhost:8080`
- [ ] `GET /api/health` answers `{"status":"ok"}` and the compose healthcheck reports the service healthy
- [ ] Data survives `docker compose down` and `up` again: a finished transcription is still in the history
- [ ] Uploading a short clip in the containerized app completes with a transcript
- [ ] A task interrupted by `docker compose stop` resumes after `docker compose start` (item 11's restart recovery)
- [ ] Cancelling running tasks leaves no zombie processes in the container (init as PID 1)
- [ ] The container runs as a non-root user and writes only to `/data`
- [ ] The image contains nothing outside the flake package's closure (plus the init)

## Acceptance criteria
- User-run: the documented commands build and start the app with Docker; a short clip is transcribed; data persists across container restarts.
- All test-list items ticked and verified; user approved the diff.

## Approval log
_Empty — add dated lines (YYYY-MM-DD: what was approved) on approval._
