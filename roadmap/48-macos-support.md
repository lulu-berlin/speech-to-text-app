# 48 — macOS support

Status: in-review
Depends on: [05](../docs/specs/process-runner.md) (done), [16](../docs/specs/nix-flake-package.md) (done), [45](../docs/specs/device-selection.md) (done; macOS GPU surface must not conflict), [51](../docs/specs/ci-verification.md) (done; the macOS CI runners that verify this item), [50](../docs/specs/electron-desktop-app.md) (done; the Linux desktop app and its packaged specs, which the macOS packaged-app item extends)
Blocks: — [49](49-windows-support.md)

## Goal

The app runs, transcribes, diarizes, pauses and uses its GPU on macOS (Apple Silicon) —
through the same paths the Linux app has: the Nix flake package, the devenv development environment,
and the Gradle bootstrap. Process control is correct (the Linux signal numbers that swap pause and
resume on macOS are gone), Metal acceleration works, and the e2e journey is green. README no longer
needs the manual `brew install util-linux` workaround. Verification happens on CI macOS runners
(nobody on the team owns a Mac).

## Evidence

Filed 2026-10-13 from a portability pass over `main` (verified reads, nothing executed):

- **Pause/resume are swapped on macOS** — `ProcessTree.kt:75-79` hardcodes Linux numbers
  SIGSTOP=19/SIGCONT=18; macOS/BSD is SIGSTOP=17/SIGCONT=19, so `pause()` sends SIGCONT (resumes)
  and `resume()` sends SIGTSTP/SIGSTOP (stops). A genuine bug.
- **No `setsid` on macOS** — `ProcessRunner.kt:73` prepends `setsid` to every command; macOS has no
  setsid binary. README.md:130-135 documents a manual `brew install util-linux` + PATH workaround.
- **GPU discovery is Vulkan-only** — `DeviceProbe.kt:44-77` parses whisper-cli's `found GPU device`
  line, which only the Vulkan backend prints; the darwin whisper-cli prebuild is CoreML
  (`nix/versions.nix`), so Settings shows only cpu/auto even where Metal exists.
- **The flake is hardcoded to x86_64-linux** — `flake.nix` fixes `system = "x86_64-linux"` for
  packages/apps/checks; `nix build .` on a Mac resolves nothing.
- **The nix build is Vulkan-shaped** — `nix/whisper-cpp.nix` overrides `vulkanSupport = true`, no
  Metal.
- **devenv lists Linux-only packages** — `devenv.nix` pulls `pkgs.util-linux` (setsid) and
  `pkgs.vulkan-tools`.
- **The e2e harness reads /proc** — `e2e/lib/backend.mjs:178-227` finds the JVM via `/proc/../cmdline`
  and `/proc/../stat`; macOS has no /proc, so e2e stop/cleanup would fail and leave the daemon.
- **Portable already** — `versions.json` carries darwin-arm64/x64 whisper-cli (CoreML) and ffmpeg
  artifacts; sqlite-jdbc and onnxruntime use per-OS bundled natives; data paths are `Path.of`-based.
- **CI exists now**: item 51's workflow runs the Linux stack on every push and pull request; the macOS runners this item adds extend it ([51](../docs/specs/ci-verification.md)).

## Decided (consultation, 2026-10-13)

- **Apple Silicon only** (amended 2026-10-19, superseding "both architectures"): Apple discontinued
  Intel Macs (macOS 26 is the last supported release) and nixpkgs dropped x86_64-darwin in 26.11.
  The flake/devenv support aarch64-darwin; the Gradle bootstrap keeps its darwin-x64 artifact
  entries but they are unsupported and unverified; no Intel CI leg, one ARM DMG.
- **GPU support is in scope**: Metal/CoreML acceleration (whisper-cpp Metal build on darwin) and a
  Metal device probe; the Settings surface follows item 45's enumeration contract.
- **setsid on macOS**: direct-child kill fallback — whisper-cli and ffmpeg do not fork, so the
  tree-kill design degrades to direct-child kill on darwin (documented residual orphan risk if a
  spawned tool ever forks). Linux keeps process groups.
- **devenv on macOS is in scope**: `devenv up` and the dev shell work on darwin, with Linux-only
  packages (`setsid` util-linux, vulkan-tools) gated by system.
- **CI is the verification path** (no local Mac): macOS runners run the unit, Integration and
  e2e suites plus the Nix build per [51](../docs/specs/ci-verification.md).
- **DeviceArgs**: verify `--no-gpu`/`--device N` against the actually-pinned whisper-cli build as
  part of the GPU work (the `DeviceArgs.kt` 1.9.x-vs-1.8.4 mismatch row leaves the known-issues
  backlog for this item).

### Decided (checkpoint 1, 2026-10-19)

- **Metal device surface** (open question 1 answered): faithful to the probe. whisper-cli 1.8.4's
  darwin Metal log is `device N: MTL0 (type: 2)` / `GPU name: MTL0`; the human device name is not
  logged. On a Metal-capable build, Settings offers `gpu:0` with `gpuNames` `{"gpu:0":
  "Metal (MTL0)"}` (page: `GPU 0 — Metal (MTL0)`); `gpu:0` → `--device 0`, `cpu` → `--no-gpu`,
  `auto` → no flag, per item 45.
- **Which binary has Metal**: the pinned darwin prebuilds have no ggml GPU backend — arm64 ships
  `libwhisper.coreml.dylib` (encoder-only) but no `libggml-metal.dylib`, x64 has neither — so the
  Gradle/bootstrap path honestly reports cpu/auto only. Metal is the Nix-built whisper-cpp:
  `nix/whisper-cpp.nix` becomes Vulkan on Linux and Metal on aarch64-darwin (x86_64-darwin has no
  nixpkgs support at the pin, so no Metal build there; the Gradle x64 prebuild has no GPU backend).
  The desktop artifact is electron-builder only (two per-architecture DMGs); the flake's
  `.#desktop` stays Linux-only, so the packaged desktop app has the prebuild's CPU/CoreML path and
  Metal is the Nix server package and devenv on Apple Silicon.
- **Metal verification (CI only)**: the positive half is pinned by the 1.8.4 darwin lines in a unit
  test that runs in CI; the no-Metal half runs on the macOS CI leg. GitHub macOS runners are VMs;
  GPU passthrough was unavailable in 2020 and is unconfirmed since, so a real Metal run is not
  claimed (nobody on the team owns a Mac).
- **CI legs** (open question 2 answered, amended 2026-10-19): `macos-15` (ARM) runs the full Gradle
  tree (check, integration, e2e) plus the darwin Nix build, nightly and on this item's PRs. Intel
  macOS is out of scope (no `macos-15-intel` leg).
- **Tick rule**: macOS-CI items tick only on a reported CI run, never on a local proxy.

## Proposal

To be settled at the first test-list item, starting from the decisions above:

1. Process control: per-platform signal constants (Linux vs Darwin, by name or platform constants in
   LibC) — fixes the pause/resume swap; direct-child kill on darwin where setsid is absent; the e2e
   `/proc` lookup becomes a portable `ps`-based lookup.
2. Nix: declare `aarch64-darwin` (x86_64-darwin is unsupported by the pinned nixpkgs) in the flake;
   enable the whisper-cpp Metal build on darwin; gate Linux-only devenv packages by system; give the
   packaged app a macOS-idiomatic data-dir default (`~/Library/Application Support`).
3. GPU: Metal device probing so a Metal machine reports its device(s) per item 45's contract
   (CoreML stays an encoder path, not a ggml device); cpu/auto always present; never crash on
   non-Vulkan machines.
4. Docs/tests: README mac section (workaround gone, Metal documented); macOS CI runs of the suites
   and a smoke transcription.

## Open questions (answered 2026-10-19)

- The Metal device surface? → **Faithful to the probe**: `gpu:0` with `gpuNames` `{"gpu:0":
  "Metal (MTL0)"}` on a Metal machine; `gpu:0` → `--device 0`, `cpu` → `--no-gpu`, `auto` → no flag
  (item 45's contract).
- Does the Intel runner stay? → **No** (amended 2026-10-19): Apple Silicon + Linux only; the Intel
  macOS leg is dropped, matching Apple's and nixpkgs' x86_64-darwin deprecation.

## Test list (approved 2026-10-19; behaviors only)

(Each macOS-CI item ticks only on a reported CI run, never on a local proxy.)

Portability audit (2026-10-19): the macOS legs exercise every host-specific path — signals (item 2),
`setsid` and process groups (items 1, 4), `/proc` (items 4, 7), Nix (item 8), devenv (item 8),
desktop staging and per-user paths (item 10).

- [x] (CI, macOS ARM) The human Gradle bootstrap works: `./gradlew check` + `./gradlew integrationTest`
      are green on a macOS runner, downloading the darwin whisper-cli, ffmpeg and models (the
      Linux-only system-deps tasks no-op)
- [x] (CI, macOS) Pausing a running task actually pauses its process and resume continues it: the
      process tree sends the platform's numbers (darwin SIGSTOP 17/SIGCONT 19; Linux 19/18), pinned
      host-independently and run through the real process runner on the macOS leg; ticks only on that run
- [x] (CI, macOS) A full upload → transcribe → diarize run completes on macOS for a fixed fixture and
      its output matches the Linux run
- [x] (CI, macOS) Cancel and delete stop the whole run on the no-`setsid` direct-child path (no
      orphan whisper-cli/ffmpeg processes); the Linux process-group scenarios stay green
- [x] (CI, macOS, added 2026-10-19) The e2e harness finds and stops the backend without `/proc`
      (portable `ps` where `/proc` is absent), leaving no JVM/whisper-cli/ffmpeg; the Linux container
      path keeps `/proc`
- [x] (CI, macOS) On a Metal-capable build (the Nix package on darwin), Settings shows `gpu:0` named
      `Metal (MTL0)` per item 45; on the pinned prebuild (no ggml GPU backend) and a machine without
      Metal, cpu/auto only and no crash or phantom GPU. The positive half is pinned by the recorded
      1.8.4 darwin lines in a unit test; the cpu/auto half runs on the macOS leg
- [x] (CI, macOS, added 2026-10-19) On the Metal runner, choosing `gpu:0` makes the app transcribe
      the JFK clip through MTL0 (the captured backend log shows `using MTL0 backend`) and the
      transcript carries the known quote; the Settings option reads `GPU 0 — Metal (MTL0)`
- [x] (CI, macOS) DeviceArgs flags verified against the bundled whisper-cli version (the shipped
      binary, not 1.9.x assumptions)
- [x] (CI, macOS) `./gradlew e2e` (the journey: queue, history, settings, reorder, pause/cancel) is
      green on macOS
- [x] (CI, macOS) The flake declares `aarch64-darwin` (the pinned nixpkgs has no x86_64-darwin;
      Intel Macs use the Gradle bootstrap and its Intel leg above); `nix build`/`nix run` and the
      devenv shell (Linux-only packages gated) work on Apple Silicon, with the Metal whisper-cpp and
      a darwin ffmpeg; `nix flake check` passes
- [x] (CI, inherited from 51 on 2026-10-14) This item adds its macOS leg — `macos-15` (ARM) — to
      `.github/workflows/ci.yml`; it runs the full Gradle tree and the darwin Nix build on the
      nightly schedule and on this item's PRs
- [x] (docs, added 2026-10-19) README documents macOS without the `brew install util-linux`
      workaround: the Nix and `./gradlew` paths, the per-user paths, and Metal's actual source (the
      Nix whisper-cpp; the pinned prebuild is CPU/CoreML)
- [x] (e2e, CI macOS, moved from item 50 on 2026-10-15) electron-builder builds an unsigned `.dmg`
      for Apple Silicon with a darwin jlink JRE and the darwin whisper-cli/ffmpeg; the installed app
      runs with its per-user paths (`~/Library/Application Support/stt-app`,
      `~/Library/Caches/stt-app/models`), and item 50's packaged specs (journey, quit without orphan
      processes, first run with the model download and its persistence, download failure with Retry)
      pass against the installed DMG
- [x] (must not break) `./gradlew check`, `./gradlew integrationTest`, `pnpm test`, `pnpm build` and
      the Linux e2e stay green

## Acceptance criteria

- macOS (Apple Silicon) is a documented, CI-tested platform: the README brew/setsid workaround is
  gone; Nix and the devenv work on aarch64-darwin; the Gradle bootstrap works, pause/resume behave
  correctly, Metal works, and the e2e journey passes. GPU surface honors item 45's contract.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-13: filed from the macOS portability pass (signal swap, setsid, Vulkan-only probe, flake
  system, /proc e2e).
- 2026-10-13: consultation — both architectures; GPU (Metal) in scope; direct-child kill on darwin;
  devenv on macOS in scope; CI is the verification path (added dependency 51); DeviceArgs mismatch
  absorbed to be verified. Status: spec-approved.
- 2026-10-15: item 50's test item "The packaged app runs on macOS and Windows with their per-user
  paths" moved here (macOS half) by the user's decision, so item 50 could ship Linux first;
  dependency on 50 added, 50 removed from "Blocks".
- 2026-10-19: checkpoint 1 — test list approved (11 items) with the evidence that the pinned darwin
  prebuilds have no ggml GPU backend (arm64: CoreML only, no `libggml-metal.dylib`; x64: neither),
  so Metal is the Nix-built whisper-cpp and the prebuild path reports cpu/auto only. Answers: Metal
  surface faithful (`gpu:0` = `Metal (MTL0)`; `gpu:0` → `--device 0`, `cpu` → `--no-gpu`, `auto` →
  no flag); both CI architectures (`macos-15`, `macos-15-intel`), full tree nightly and on this
  item's PRs; Metal's positive half pinned by the 1.8.4 darwin lines (no real-Mac run), the no-Metal
  half on CI; first cycle = test item 2, ticking only on the macOS CI run. Status: in-progress
  (test list approved).
- 2026-10-19: test list amended after the user's coverage check (13 items): item 1 names the human
  `./gradlew` bootstrap and its darwin downloads; item 4 names the no-`setsid` direct-child path;
  item 5 and the Decided bullet drop the "user-verified on a real Mac" half — everything is verified
  in CI, the positive Metal half by the recorded-line unit test; item 8 makes the darwin Nix work
  (both systems, Metal whisper-cpp, darwin ffmpeg) CI-only; item 10 names two unsigned DMGs from
  electron-builder with darwin staging and `~/Library` paths (the flake `.#desktop` stays Linux-only);
  added the portable e2e `/proc` replacement and the README macOS section. Known portability fixes
  the macOS `integrationTest` run forces: `ProcessRunnerSpec`'s `seq`/`/proc`/`setsid` assumptions and
  `ProcessTreeSpec`'s hardcoded Linux `[18, 15, 9]` (the latter is cycle 1's test-list item 2).
- 2026-10-19: checkpoint 2 — item 8's flake evaluation hit an upstream blocker: the pinned nixpkgs
  (26.11 line) dropped x86_64-darwin ("due to Apple's deprecation of the platform and limited build
  infrastructure and developer time"; 26.05 keeps it until end 2026). The user chose: **Nix/devenv
  are aarch64-darwin only; Intel Macs keep the full Gradle path**. The flake's systems are
  `x86_64-linux` + `aarch64-darwin`; test items 8 and 9, the Decided bullets and the acceptance
  criteria are amended accordingly. Locally evaluated (no builds): `packages.aarch64-darwin`
  (`backend`, `default`, `frontend`), `apps.aarch64-darwin` (`default`, `update-versions`),
  `checks.aarch64-darwin` (`devenv-pin-sync`, `versions-sync`), and the Linux sets unchanged.
- 2026-10-19: item 1's local work (CI-pending): the bootstrap already maps darwin-x64/arm64 and its
  system-deps tasks are Linux-only; the integration-test suite is now portable (`awk` instead of
  `seq`, `/proc` on Linux / `ps` on darwin for the state check, the three process-group-creation
  scenarios gated to Linux), the bootstrap uses `~/Library/Application Support/stt-app` and UTF-8
  on darwin, and the e2e backend prefers `./gradlew` when `gradle` is not on PATH. Verified on
  Linux: `gradle check integrationTest` green (388 tests). The macOS green awaits CI.
- 2026-10-19: checkpoint 3 — the user dropped Intel macOS support ("if Apple themselves stopped
  supporting it"): the item is Apple Silicon + Linux only. The `macos-15-intel` leg, the second
  DMG and the "both architectures" decision are removed; the Gradle bootstrap's darwin-x64 entries
  stay as unsupported/unverified data. Items 1, 8, 9, 10 and the acceptance criteria amended.
- 2026-10-19: checkpoint 4 — CI run 39314114069: macOS check/frontend green; macOS integration
  failed because the pinned darwin prebuilds' binaries hard-load `libggml-metal`/`libggml-blas`
  dylibs that neither tarball ships (v1.8.4 and v1.8.4.1 are the same broken asset), so whisper-cli
  cannot start. The user chose the release-asset route: `.github/workflows/ci.yml` gains a manual
  `macos-whisper-asset` job (static 1.8.4 Metal/BLAS build, no `@rpath`, artifact + sha256); the
  user publishes it as the macOS asset of item 63's per-platform `desktop-whisper-cpp-v1` release
  (`whisper-cpp-1.8.4-aarch64-darwin.tar.gz`), and the Gradle darwin pin is updated. The Linux `nix
  flake check` failure was an evaluation-time IFD in `nix/desktop-bundle.nix` (cold store); fixed by
  reading `bwrapLibs` at build time. The macOS `actionlint` build failure awaits its log.
- 2026-10-19: the macOS whisper-cli asset is published: `desktop-whisper-cpp-v1` /
  `whisper-cpp-1.8.4-aarch64-darwin.tar.gz` (sha256
  `58ff90b3b693c58c28a3cf50c8dc9fcc6ad355101e9535f234d187bd9651078a`; static Metal build,
  `bin/whisper-cli`, only system frameworks — no `@rpath`/store libraries). `nix/versions.nix` and
  `versions.json` darwin-arm64 now point at it; the Gradle bootstrap extracts it. The upstream
  darwin pins (x64) remain unsupported data.
- 2026-10-19: macOS integration's three failures were the group-dependent runner tests (a
  background descendant killed after cancel/throw); they are gated to Linux, where process groups
  exist. On darwin the devenv/server package now use nixpkgs' cached `ffmpeg_8-headless` (the
  audio-only static recipe is Linux-shaped and its darwin configure fails); the Linux pinned asset
  and its source derivation are unchanged.
- 2026-10-19: the darwin `versions-sync` check is Linux-only: `versions.json` is a single
  Linux-rendered manifest, and darwin's nixpkgs JDK is `21.0.11` versus Linux's `21.0.12.1+1`.
  Linux CI still runs the check; the darwin flake check keeps `devenv-pin-sync` and its
  package/app checks.
- 2026-10-19: CI run [39336289612](https://github.com/lulu-berlin/speech-to-text-app/actions/runs/39336289612)
  at `eaff958a` is fully green (18 jobs; the two skipped are the manual asset build and the weekly
  model-host check). Ticked items 1–6, 8–10 and 13 in the current 13-item list: the macOS
  `./gradlew` check/integration, the real-runner pause/resume, upload/transcribe/diarize,
  cancel/delete, the no-`/proc` harness, the probe's cpu/auto half with the Metal parser pin, the
  e2e journey, the darwin Nix build/flake-check/devenv, the CI legs, and the Linux must-not-break
  tree. Item 10's first nightly is observed after merge (item 51's precedent). Remaining: item 7
  (DeviceArgs against the shipped binary), item 11 (README), item 12 (DMG).
- 2026-10-19: item 7 — the shipped 1.8.4 whisper-cli (the Gradle bootstrap's managed tool) lists
  and accepts `--device 0` and `--no-gpu`; the new Integration spec `WhisperCliFlagsSpec` checks
  `--help` plus both flags on the tiny JFK run. Green on arrival (no code change): the 1.8.4
  binary already has the flags `DeviceArgs` emits, so the spec pins it. Local `integrationTest`
  green (2 tests); the macOS leg runs it on the next CI run.
- 2026-10-19: item 11 — README documents Apple Silicon macOS: the Nix/devenv and Gradle paths,
  per-user data/cache paths, Metal from the Nix whisper-cpp and the pinned static Metal bootstrap
  build, no util-linux workaround; Intel macOS is documented as unsupported. The architecture
  overview's pause wording no longer names the removed SIGSTOP/SIGCONT constants. Docs checks green
  (links, names, diagram).
- 2026-10-19: item 12 — the macOS DMG path is implemented (CI-pending): platform-aware desktop
  staging (darwin pins/extraction in `verified-download.ts`/`fetch-jdk.ts`, the static whisper
  asset, bytedeco's `macosx-arm64-gpl` ffmpeg), `dist:mac` + electron-builder `dmg` (unsigned,
  arm64), macOS per-user data/cache paths, `ps`-based desktop process checks, and a `macos-dmg`
  CI job that builds the DMG, mounts/installs it and runs item 50's four packaged specs (01–04).
  The Temurin macOS JDK pin is added to `nix/versions.nix`/`versions.json`. Green only after CI.
- 2026-10-19: CI run [39339610540](https://github.com/lulu-berlin/speech-to-text-app/actions/runs/39339610540)
  at `d4c6cb90` — the macOS DMG job is green: electron-builder built the unsigned arm64 DMG, it was
  mounted/installed, and item 50's four packaged specs passed. Item 12 ticked. The same run's Linux
  `.deb` packaged e2e failed in the spec step (build, floor check and install were green); its log
  is needed to tell a flake from a staging regression.
- 2026-10-19: the `.deb` spec failure was a real regression from the staging change, not a flake:
  `cpSync` turned the Linux whisper libs' relative symlinks (`libwhisper.so.1 -> …1.8.4`) into
  absolute links into the deleted temp dir, so `whisper-cli` could not start. Linux uses `tar`'s
  extraction (relative symlinks preserved) again; only the macOS static tar is flattened with
  `findFile`/`cpSync`. Desktop formatting/types re-checked.
- 2026-10-19: added the app-level Metal e2e (the user's request; it makes the separate CLI/settings
  tests redundant): the backend logs whisper-cli's selected backend line; the e2e harness appends
  the backend output to `e2e/.e2e-backend.log`; `e2e/tests/18-macos-metal.spec.ts` selects `gpu:0`,
  transcribes a unique JFK loop and checks the quote, and the macOS e2e job greps the log for
  `using MTL0 backend`.- 2026-10-20: rebased onto `main` (item 63's pinned prebuilt artifact machinery) and resolved the
  conflicts: `nix/whisper-cpp.nix` keeps the prebuilt structure with the darwin Metal flags,
  devenv/README unioned, and the flake folds the new whisper-cpp checks and asset-pin apps into the
  per-system structure (`whisper-cpp-closure-slim`/`whisper-cpp-behaves` also run on darwin; the
  pin-dependent checks stay Linux-only — no darwin asset pin yet). The closure-size check's bounds
  grow by item 48's measured 4462-byte platform layer (default and docker-root; the bundle stayed
  within its item-62 bound), and the check now measures every closure before asserting so one
  failing run names each closure that moved.
- 2026-10-20: all test-list items green on CI run 39357501229 (commit `73677550`): every macOS leg
  (gradle check, integrationTest, e2e with `using MTL0 backend`, DMG + packaged e2e, Nix build +
  devenv) and every Linux leg passed. Status: in-review.
