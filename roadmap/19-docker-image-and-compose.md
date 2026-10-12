# 19 — Docker image and docker-compose setup

Status: in-progress
Depends on: [16](../docs/specs/nix-flake-package.md)

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

## Proposal (approved 2026-10-09)

- **Image** built from item 16's flake closure by a multi-stage Dockerfile:
  - builder stage on `nixos/nix`: `nix build .#stt-app` (item 16's package: wrapper, JRE, frontend, Vulkan whisper-cli, ffmpeg, `setsid`, both models);
  - runtime stage: the closure's files (the exact copy mechanism — store path copying with an env shim, or similar — is settled at the first checkpoint), a non-root user, PID 1 = an init (`tini` from the closure or `compose init: true`); `STT_DATA_DIR=/data`; port 8080.
  - The closure carries the Vulkan whisper-cli, `ffmpeg`, `util-linux`'s `setsid` and the models, so the image needs the host only for the GPU device and driver libs.
- **GPU** (the corrected matrix, recorded as decided):
  - **NVIDIA:** the GPUs plus the NVIDIA Container Toolkit on the host (`--gpus all` semantics; on CDI hosts the device `nvidia.com/gpu=all`); the Vulkan build uses the driver's Vulkan ICD. Compose exposes this via the `docker-compose.gpu-nvidia.yml` override (profiles until cycle 9, see Checkpoints).
  - **Intel / AMD:** `/dev/dri` passed to the container; the `docker-compose.gpu-vulkan.yml` override.
  - **CPU:** the default (`docker-compose.yml` alone); `STT_DEVICE=cpu`.
  - **macOS:** Docker Desktop runs the Linux image CPU-only (no GPU passthrough into Linux containers); documented.
  - **TPU:** out of scope — whisper.cpp has no TPU backend.
- **`docker-compose.yml`** at the repository root: one service `stt-app`; a named volume at `/data`; `8080:8080`; a healthcheck `curl -fs http://localhost:8080/api/health`; a restart policy; the two GPU override files beside it; `init: true` if no `tini`.
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

## Checkpoints

- 2026-10-11 (cycle 1, test 1 confirmed with the proposal): the copy mechanism is settled:
  a multi-stage Dockerfile — `nixos/nix:2.35.2` builder runs `nix build --option sandbox
  false .#stt-app` (sandbox off: Docker's default seccomp can block nix's namespace sandbox
  and the gradle build fetches inside the derivation; the `.#stt-app` attr is a new alias of
  `default` in flake.nix so the build is explicit), `nix-store -qR` writes the closure, and
  every closure store path is copied to its exact absolute `/nix/store` location into a
  staging rootfs; the runtime stage is `FROM scratch` with a single `COPY` of that rootfs.
  PID 1 is docker-init (compose `init: true`, i.e. tini as decided) so the image adds no
  init bytes and the "closure only" test stays clean; a generated `/entrypoint.sh` shim
  (store-hash bash shebang, `exec`s the wrapper's `bin/stt-app`) bridges the static-Dockerfile
  limitation; non-root `stt` (uid/gid 1000), `/data` (named volume `stt-data`, STT_DATA_DIR),
  `/tmp` 1777, `/bin/sh` and `/usr/local/bin/curl` as symlinks into the closure (curl was
  added to the package's PATH prefix in flake.nix — "curl from the flake"; bash was already
  in the closure as the wrapper's interpreter).

- 2026-10-11 (cycle 1, green, user-verified): `docker compose up -d --build` on a fresh clone
  built the image (first build ~11 min: nixpkgs, gradle deps, whisper-cpp 1.8.4 Vulkan, both
  models), `docker compose ps` showed `Up (healthy)` and `GET /` answered 200. Two build bugs
  found and fixed on the way: the closure copy needed `/rootfs` to exist first (`cp --parents`,
  commit `1d5d0b7`), and since the builder is `FROM nixos/nix`, its own packages share
  `/nix/store` with the fresh build — bash is now taken from the wrapper's own shebang (a
  closure reference) and curl from the closure list, never from the image's store. Rebuild
  mechanics observed: the `Dockerfile`/`.dockerignore` are part of the build context
  (`COPY . .`), so editing the Dockerfile re-runs the whole `nix build` inside Docker (another
  ~11 min); excluding them from the context is the refactor candidate.

- 2026-10-11 (cycle 2, green, user-verified): no code change — the health route exists since
  item 02, the compose healthcheck and the closure's curl were already in place; the red was
  the pre-test-1 state (nothing started). `GET /api/health` answered `{"status":"ok"}` and
  `docker compose ps` showed `Up (healthy)`. No refactoring suggested.

- 2026-10-11 (cycle 4, green, user-verified; test 3's prerequisite, so 4 ran before 3): no
  code change. `POST /api/tasks` (multipart `file` + `language=en`, the e2e JFK fixture) in
  the containerized app reached status `COMPLETED`; `GET /api/tasks/1/transcript?format=txt`
  answered `[Speaker 1]` and both JFK phrases (the item 16 cycle-6 expectation, whisper 1.8.4).
  The task list is `{"tasks": […]}` — the first handout's bare-array jq was wrong, corrected
  to `.tasks | map({id, filename, status})`. No refactoring suggested.

- 2026-10-11 (cycle 3, green, user-verified): no code change. `docker compose down` removed
  container and network but kept the volume `stt-data` (as designed — only `down -v` deletes
  it); `docker compose up -d` started a fresh container; task 1 was still `COMPLETED` and its
  transcript intact (the volume holds the only copy, ADR 0024). No refactoring suggested.

- 2026-10-11 (cycle 5, green, user-verified): no code change. A ~70 s clip (ffmpeg
  `-stream_loop 6`, distinct bytes from the test-4 fixture) was interrupted mid-transcription
  by `docker compose stop`; `docker compose start` resumed it (item 11's recovery: first poll
  `RUNNING`, completed after a few minutes of CPU transcription — an early "stuck forever"
  reading was just slowness, ruled out by an advancing poll) and the transcript carries the
  phrase once per loop, split across two speakers. The `docker compose ps` scare in between
  was a stopped container hiding from `ps` (no `-a`) — not a crash. No refactoring suggested.

- 2026-10-11 (cycle 6, green, user-verified): no code change. A ~60 s clip (distinct bytes —
  audio dedupes by sha256, 409 `duplicate-audio` on identical uploads) was cancelled mid-
  transcription (`POST /api/tasks/3/cancel`); then `/proc/<pid>/stat` scans inside the container
  (pure shell builtins — the image has no `ps`/`grep`/`find` on PATH, only `/bin/sh` and
  `/usr/local/bin/curl`; the first draft's `bash -c` was wrong, `sh -c` is the entry point)
  found no `Z`-state processes and no leftover `whisper-cli`/`ffmpeg` `cmdline`s: docker-init
  (PID 1) reaped the cancelled tree — ADR 0020's orphan case is covered in the container.
  No refactoring suggested.

- 2026-10-11 (cycle 7, red then green, user-verified): **test 7 was red** — `Config.User` was
  empty and `exec` ran `Uid: 0`: the runtime stage never got its `USER 1000:1000` directive
  (the design and the rootfs user existed, the directive was missing), so tests 1–6 ran as
  root. Commit `19a489f` adds the directive. The old volume held root-owned `stt.db` (created
  by the root-run), so verification used `docker compose down -v` (volume recreated fresh from
  the image's 1000-owned `/data`). Green: `Config.User` `1000:1000`, exec `Uid/Gid 1000
  1000`, writable only `/data`, `/tmp` (JVM scratch) and `/home/stt`, `/etc` `/usr` `/bin`
  `/nix/store` not writable, upload → `COMPLETED` with all state under `/data`
  (`stt.db`, `-wal`, `-shm`, `tmp/`, `work/`). The 682 s `nix build` on the rebuild was the
  one-time context-shape change from the cycle-1 refactor; Dockerfile-only edits are cached
  now. No refactoring suggested.

- 2026-10-12 (cycle 8, green, user-verified): no code change. The image store equals the
  **builder's own** `/out/closure.txt` (`docker build --target builder` then `cat`; `diff`
  silent — the image is the closure it was copied from, by construction). The first reference
  (a host-side `nix build .#stt-app`) was the wrong probe: the host evaluates a different
  derivation (`6df9qmh0…` vs the container's `gl4jz8wv…`) even though every dependency store
  path in the two closures matches, and each side reproduces its own path deterministically —
  a real eval drift between the host's nix and the container's `2.35.2`, worth one probe
  (`nix-store -q --deriver` on both paths + `nix derivation show` diff) but not an item-19
  acceptance failure: the image is reproducible in its own build. The root-level check
  (`docker run … echo /*` = `/bin /data /entrypoint.sh /etc /home /nix /tmp /usr`, plus the
  runtime mounts `/dev /proc /sys`) confirms the scaffolding is the known nine files.
  No refactoring suggested.

- 2026-10-12 (cycle 9, red, user-run on a host with an NVIDIA dGPU and an AMD iGPU):
  three findings. (1) Compose profiles cannot make the variants exclusive: a service
  without a profile always starts, so `--profile gpu-X up` started the CPU `stt-app` next
  to the GPU one, both on 8080; `down` without `--profile` left the profiled container
  running. (2) The handed-out checks ran `exec stt-app`, i.e. inside the CPU container, so
  their `/dev/dri/*` / `/dev/nvidia*` output was inconclusive. (3) `could not select device
  driver "nvidia"`: the host's Docker has no NVIDIA runtime; it is NixOS with
  `hardware.nvidia-container-toolkit`, which exposes GPUs through CDI. **User decisions:**
  the variants become override files on one service (`docker-compose.gpu-nvidia.yml`,
  `docker-compose.gpu-vulkan.yml`, layered with `-f`; compose deep-merges `environment`),
  replacing the spec's profiles; NVIDIA uses the CDI device `nvidia.com/gpu=all` (compose
  `devices` accepts CDI names) instead of `driver: nvidia`. Found while writing the Vulkan
  override: uid 1000 cannot open `/dev/dri/renderD*` (owned by the host's `render` group,
  id varies per host), so it gets `group_add: ${STT_RENDER_GID}`. Test 9's text changes
  accordingly.

## Test list (written with the user; Canon TDD step 1)

Approved 2026-10-09 with the spec. Behaviors only; discovered scenarios are appended as `(discovered)` and confirmed with the user. All items are verified by the user on a machine with Docker:

ADR 0046 (every app feature gets an e2e test) is exempt and why: this item changes packaging only — no app feature or UI; the app's features are covered by item 21's e2e suite booting the packaged app, and a container image cannot be exercised by the UI e2e suite. The image's verification is the user-run Docker scenarios in this list.

- [x] From a fresh clone with only Docker installed, the documented command builds the image and starts the app on `http://localhost:8080`
- [x] `GET /api/health` answers `{"status":"ok"}` and the compose healthcheck reports the service healthy
- [x] Data survives `docker compose down` and `up`: a finished transcription is still in the history
- [x] Uploading a short clip in the containerized app completes with a transcript
- [x] A task interrupted by `docker compose stop` resumes after `docker compose start` (item 11's restart recovery)
- [x] Cancelling running tasks leaves no zombie processes in the container (PID 1 init)
- [x] The container runs as a non-root user and writes only to `/data`
- [x] The image contains only the flake closure's files (plus the init)
- [ ] On a host with an NVIDIA GPU and the container toolkit (CDI), `docker compose -f docker-compose.yml -f docker-compose.gpu-nvidia.yml up` uses the GPU (`STT_DEVICE=auto`, `nvidia-smi`-visible); on an Intel/AMD host, the `docker-compose.gpu-vulkan.yml` override uses `/dev/dri`
- [ ] (discovered) A GPU variant runs as the only app container (no CPU service beside it, one `stt-app` for `exec`/`logs`), and a plain `docker compose down` removes it

## Acceptance criteria

- User-run: the documented commands build and start the app with Docker; a short clip is transcribed; data persists across container restarts.
- On the available hardware, at least one GPU variant is demonstrated (NVIDIA or Vulkan), with the others documented as unverified.
- All test-list items ticked and verified; user approved the diff.

## Documentation deliverable — the root README's Docker section

The root README.md is written by item 35 (parallel workspace). This item's documentation
goes into it when 35 lands; until then it lives here. Ready-to-paste text:

---

## Run with Docker

Requires Docker with Compose v2 on x86_64 Linux. The image is built locally — a host
with only Docker is enough, the build runs inside a `nixos/nix` container, and nothing is
pushed to a registry. macOS and Windows run this Linux image through Docker Engine
(Desktop/VM) CPU-only: Docker does not pass GPUs into Linux containers there.

- **Build and start:** `docker compose up -d --build`. The first build downloads the
  locked nixpkgs and both models (~600 MB) and runs the backend's unit tests inside the
  Nix build, so it takes a while. The app then serves http://localhost:8080.
- **Status and logs:** `docker compose ps` (a curl healthcheck against `GET /api/health`
  reports the service healthy), `docker compose logs -f stt-app`.
- **Stop/start/down:** `docker compose stop` / `docker compose start` (a transcription
  task interrupted by `stop` resumes after `start` — the runner's restart recovery),
  `docker compose down` (removes the container, keeps your data).

**Data.** Everything — the `stt.db` database and the `work/` uploads — lives in
`STT_DATA_DIR=/data`, the named volume `stt-data`; the container runs as a non-root user
and `/data` is its only writable path. `docker compose down` keeps the volume,
`docker compose down -v` deletes it. Back up (stop the app for a consistent snapshot):

```sh
docker compose stop
docker run --rm -v stt-data:/data:ro -v "$PWD":/backup alpine:3 tar czf /backup/stt-data.tar.gz -C /data .
docker compose start
```

Restore (files land owned by the container's uid 1000):

```sh
docker compose stop
docker run --rm --user 1000:1000 -v stt-data:/data -v "$PWD":/backup alpine:3 tar xzf /backup/stt-data.tar.gz -C /data
docker compose start
```

**GPU matrix.** Whisper runs on the GPU through Vulkan (the image ships the packaged
Vulkan whisper build); the host contributes the GPU device and its driver. A GPU variant
is an override file layered on `docker-compose.yml`: it is the same `stt-app` service, so
`docker compose down`, `exec stt-app` and `logs` work the same for every variant.

| Host | Command |
| --- | --- |
| CPU (any) | `docker compose up -d` — `STT_DEVICE=cpu` |
| NVIDIA (NVIDIA Container Toolkit with CDI: NixOS `hardware.nvidia-container-toolkit.enable`, elsewhere `nvidia-ctk cdi generate`; Docker with CDI enabled) | `docker compose -f docker-compose.yml -f docker-compose.gpu-nvidia.yml up -d` — CDI device `nvidia.com/gpu=all`; Vulkan through the driver's ICD, `STT_DEVICE=auto` |
| Intel / AMD (Vulkan driver) | `STT_RENDER_GID=$(getent group render \| cut -d: -f3) docker compose -f docker-compose.yml -f docker-compose.gpu-vulkan.yml up -d` — `/dev/dri` passed, the container user joins the host's `render` group; `STT_DEVICE=auto` |
| macOS / Windows | `docker compose up -d` — CPU-only (no GPU passthrough into Linux containers) |

TPU is out of scope (whisper.cpp has no TPU backend).

---

## Approval log

- 2026-10-09: item restored after a brief abort. The abort claimed the GPU requirement could not be delivered through a Linux container; the user pointed at `docker run --gpus all` and the reversal stuck. A plain-tools recipe that had replaced the item was dropped. The GPU matrix above is decided with the user.
- 2026-10-09: test list (9 items) approved by the user; status `spec-approved`.
