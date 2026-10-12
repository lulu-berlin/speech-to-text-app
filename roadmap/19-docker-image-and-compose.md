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
  - The closure carries the Vulkan whisper-cli, `ffmpeg`, `util-linux`'s `setsid` and the models; the image additionally carries the GPU userspace the host cannot lend it (flake output `docker-gpu-runtime`, cycle 9): Mesa's Vulkan drivers for Intel/AMD, so there the host contributes only the GPU device (kernel driver, `/dev/dri`), and the libraries NVIDIA's driver links (X11 client libs, libglvnd, …), since for NVIDIA the Container Toolkit injects only the host's driver itself.
- **GPU** (the corrected matrix, recorded as decided):
  - **NVIDIA:** the GPUs plus the NVIDIA Container Toolkit on the host (`--gpus all` semantics; on CDI hosts the device `nvidia.com/gpu=all`); the Vulkan build uses the driver's Vulkan ICD. Compose exposes this via the `docker-compose.gpu-nvidia.yml` override (profiles until cycle 9, see Checkpoints).
  - **Intel / AMD:** `/dev/dri` passed to the container; the `docker-compose.gpu-vulkan.yml` override; the userspace driver (Mesa RADV/ANV) comes with the image.
  - **CPU:** the default (`docker-compose.yml` alone): no GPU is passed in, so the device setting (item 32's Settings page, seeded automatic) finds none and runs on the CPU. (Until the rebase onto item 32 the compose files set `STT_DEVICE`, which item 32 removed.)
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
  Second run (Vulkan override): one `stt-app` container, `/dev/dri/{card0,card1,renderD128,
  renderD129}` mounted, the app in group 303 (`render`) — but whisper-cli loaded only the
  CPU backend (`no GPU found`). A/B: the image has `libggml-vulkan.so` and **no ICD** at
  all; the same whisper-cpp store path natively on the host (NixOS drivers via
  `/run/opengl-driver`) found RADV (Ryzen 6900HS iGPU) and the RTX 3050 and ran on
  `Vulkan0`. So the build is fine and the image lacks a userspace Vulkan driver (`/dev/dri`
  is only the kernel node). Side finding for item 32: natively, `STT_DEVICE=auto` picks
  device 0 (the iGPU) over the dGPU. **User decision:** bundle stock nixpkgs Mesa in the
  image (binary-cached; ~265 MB for Mesa itself plus deps): flake output
  `docker-vulkan-drivers` = the radeon/intel/intel_hasvk ICD paths (no lavapipe), exported
  by the generated entrypoint as `VK_ADD_DRIVER_FILES` (additive, so a CDI-mounted NVIDIA
  ICD still loads); the native package stays without it. Bind-mounting host drivers was
  considered and rejected: on NixOS the drivers are symlinks into the host's `/nix/store`
  (mounting it would shadow the image's store), on other distros the `.so`s link against
  the host's `/usr/lib` and glibc (two glibcs in one process); bundling leaves the kernel's
  DRM API as the only host interface, so non-Nix hosts work the same way (unverified
  there — the demonstration host is NixOS). NVIDIA: `unresolvable CDI devices
  nvidia.com/gpu=all` — Docker has CDI enabled but no spec defines the name; host-side
  diagnosis pending. The discovered item was confirmed by the user and ticked on the observed
  runs (`ps` showed one `stt-app`; `down` removed the container created under an override).
  Third run (after `86d40e1`, image 3.34 GB with Mesa): **Vulkan half demonstrated.** In
  the container, whisper-cli with the entrypoint's export found `RADV REMBRANDT` and used
  `Vulkan0`; the JVM's environment carries `VK_ADD_DRIVER_FILES` (Mesa 26.2.4's radeon,
  intel, intel_hasvk ICDs) and `STT_DEVICE=auto`, so the app's own whisper-cli inherits it;
  a ~50 s clip uploaded through the API reached `COMPLETED` while the host's
  `gpu_busy_percent` for the AMD device read 86/100/100 %. Test 8 re-checked green with Mesa
  in the image (image store == builder's union closure). NVIDIA: `docker info` lists CDI
  dirs `/etc/cdi`, `/var/run/cdi`, neither exists; no `nvidia-ctk`, no generator unit — the
  toolkit is not active on the host. The user is enabling
  `hardware.nvidia-container-toolkit` to verify the NVIDIA half; test 9 stays open until then.
  Fourth run (toolkit enabled; `/var/run/cdi/nvidia-container-toolkit.json` lists
  `nvidia.com/gpu=0` and `=all`): the NVIDIA override starts (healthy), `/dev/nvidia*` are
  mounted, but whisper-cli stayed on CPU. `VK_LOADER_DEBUG` named the cause: the NVIDIA ICD
  (via the host's `/run/opengl-driver`, which the NixOS CDI spec mounts) resolves to the
  mounted `nvidia-x11-595.71.05` store path, and loading `libGLX_nvidia.so.0` fails with
  `libX11.so.6: cannot open shared object file` — the spec mounts the driver and the host
  glibc lib dirs, not the X11/libglvnd libraries on the driver's RUNPATH (the host's other
  `/run/opengl-driver` entries dangle the same way; the loader skips them). NVIDIA's
  container guidance expects the image to provide those libraries. Fix (`docker-gpu-runtime`
  replaces `docker-vulkan-drivers`): the image also carries nixpkgs' nvidia-x11 libPath set
  (`libdrm libxext libx11 libxv libxrandr libxcb zlib gcc-lib wayland libgbm libGL openssl
  dbus`), and the entrypoint exports it as `LD_LIBRARY_PATH` only when `/dev/nvidiactl`
  exists (the CPU and Vulkan variants are unaffected; a caller's `LD_LIBRARY_PATH` is kept
  after it). Unverifiable here: on non-NixOS hosts the toolkit's ICD may name
  `libGLX_nvidia.so.0` without a path, which the image's Nix loader would not search in
  `/usr/lib/x86_64-linux-gnu` — NVIDIA on non-NixOS stays documented as unverified.
  Fifth run (image `449dba8`): **NVIDIA half demonstrated.** In the container the loader
  now loads `libGLX_nvidia` (`ggml_vulkan: 0 = NVIDIA GeForce RTX 3050 Laptop GPU`, `using
  Vulkan0 backend`); the JVM carries the gated `LD_LIBRARY_PATH`; a ~20 s clip uploaded
  through the API ran as the app's own `whisper-cli` on GPU 0 — `nvidia-smi pmon` listed it
  (type C+G, ~815–894 MB framebuffer, SM bursts 61/39/6/8 %) until the task ended (1-second
  `utilization.gpu` samples had read 0 % on an earlier run — the decode phase is light; `pmon`
  per-process SM is the evidence). Regression on the same image: the Vulkan override still
  uses RADV with `LD_LIBRARY_PATH` unset (the gate holds); test 8's closure diff is still
  green. Then items 32 (settings page: `STT_DEVICE`/`STT_THREADS` removed, device stored,
  seeded `auto`) and 35 (README) were found merged on main; the user approved rebasing onto
  main, dropping the dead `STT_DEVICE` from the compose files and moving the Docker section
  into README.md, with one re-verification run on the rebased image.
  Rebase done (18 commits replayed, no conflicts — main had changed none of the branch's
  files). Then: `STT_DEVICE` dropped from all three compose files (the overrides now only add
  devices; the base file's CPU default comes from passing no GPU); the Docker section moved
  from this spec into README.md (`### Docker` replaces item 35's placeholder, the "Ways to
  run it" row says available, "Where your data lives" gains the `stt-data` volume and its
  backup/restore commands); `node docs/check-links.mjs README.md docs/architecture.md`
  passes. Re-verification on the rebased image is the next run.

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
- [ ] On a host with an NVIDIA GPU and the container toolkit (CDI), `docker compose -f docker-compose.yml -f docker-compose.gpu-nvidia.yml up` runs the app's whisper-cli on the GPU (`nvidia-smi`-visible); on an Intel/AMD host, the `docker-compose.gpu-vulkan.yml` override does the same through `/dev/dri`
- [x] (discovered) A GPU variant runs as the only app container (no CPU service beside it, one `stt-app` for `exec`/`logs`), and a plain `docker compose down` removes it

## Acceptance criteria

- User-run: the documented commands build and start the app with Docker; a short clip is transcribed; data persists across container restarts.
- On the available hardware, at least one GPU variant is demonstrated (NVIDIA or Vulkan), with the others documented as unverified.
- All test-list items ticked and verified; user approved the diff.

## Documentation

The Docker section lives in the root README.md (`### Docker`, plus the Docker row and the
volume backup under "Where your data lives"), written there after item 35 landed; it was
drafted in this spec until then.

## Approval log

- 2026-10-09: item restored after a brief abort. The abort claimed the GPU requirement could not be delivered through a Linux container; the user pointed at `docker run --gpus all` and the reversal stuck. A plain-tools recipe that had replaced the item was dropped. The GPU matrix above is decided with the user.
- 2026-10-09: test list (9 items) approved by the user; status `spec-approved`.
