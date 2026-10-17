# 50 — Electron desktop app

Status: done (roadmap item 50)
Depends on: [16](nix-flake-package.md) (done; tool/artifact gathering). The item ships
Linux only; the macOS and Windows packaged apps are part of [48](../../roadmap/48-macos-support.md) and
[49](../../roadmap/49-windows-support.md) (moved there 2026-10-15), so it does not wait for them.
Blocks: [48](../../roadmap/48-macos-support.md), [49](../../roadmap/49-windows-support.md) (their packaged-app test items build
on this item's desktop app and specs)

## Goal

The app is distributable as a desktop application, packaged with Electron and `electron-builder`:
one process starts the backend (bundled JRE), serves the bundled frontend, and opens the app window —
no browser, no Docker, no Nix, no manual tool installation. Quitting the app stops the backend
cleanly (item 26 semantics); data and models live in per-user locations; a first run on a clean
machine works. The shipped cut is **Linux** (so the item is not blocked by macOS/Windows access);
the macOS and Windows desktop builds are produced by items 48/49. Linux means **any distribution**,
not one: the app ships as a `.deb`, as a Nix package, and as a single `nix bundle` executable for
machines with neither apt nor Nix (decided 2026-10-15). The `.deb` and the bundle need no Nix.

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

## Decided (first test item, 2026-10-14)

- **Linux paths (open question a)**: data dir `${XDG_DATA_HOME:-~/.local/share}/stt-app` — the same
  directory as the Nix package and `./gradlew run`, so history is shared between install methods;
  models in `${XDG_CACHE_HOME:-~/.cache}/stt-app/models` (re-downloadable, so backups skip them).
  Electron's `app.getPath('userData')` (`~/.config/…`) is not used for either. The app picks a free
  port instead of 8080.
- **First-run download (open question b)**: a visible progress screen in the app window, shown before
  the backend starts: per-file bytes/percent, sha256 checked against the `nix/models.nix` pins (via
  `versions.json`), Retry on failure. The backend starts only once both models are present and
  verified. Evidence that replaced "reuse the missing-model error UX": the backend has none —
  `Config.fromEnv` only rejects an unset `STT_*_MODEL`; a missing file would fail tasks later. A
  user-set `STT_WHISPER_MODEL`/`STT_SPEAKER_MODEL` wins over the download (the Nix wrapper's rule).
- **Backend bundle**: the backend's `installDist` `lib/` (every dependency jar, onnxruntime/sqlite
  natives included) run as `java -cp 'lib/*'`, instead of a fat jar: no new Gradle plugin, so no
  `backend/deps.json` regeneration.
- **JRE**: `jlink` from the devenv JDK over the `jmods` of the pinned Temurin JDK of the same version
  (21.0.12.1+1), so the runtime image is a generic Linux one (verified 2026-10-14: interpreter
  `/lib64/ld-linux-x86-64.so.2`); the Temurin pin lives with the other downloads in
  `nix/versions.nix`/`versions.json`.
- **Linux artifact**: a `.deb` from electron-builder (`sudo apt install ./stt-app_<ver>_amd64.deb`),
  replacing the spec's "zip" for Linux: Ubuntu 23.10+ blocks unprivileged user namespaces through
  AppArmor, so an unpacked archive cannot start Chromium's sandbox; the `.deb`'s root install makes
  `chrome-sandbox` setuid root and keeps the sandbox on. Its declared dependencies also pull the
  system libraries Electron and the prebuilt whisper-cli/ffmpeg need, so nothing is installed by hand.
- **Packaged e2e environment**: a stock `ubuntu:24.04` Docker container on the user's machine, as a
  non-root user: the `.deb` is installed with apt; the test tooling (node, the e2e package's
  Playwright, Xvfb) comes from the host's `/nix/store` mounted read-only, so nothing test-only is
  installed into the clean machine; the e2e uses the devenv's tiny whisper + speaker models through
  `STT_*_MODEL` (item 3 tests the download itself). The sandbox (no `/lib64` loader, no Docker daemon)
  builds the package and shows red; the green run is the user's.

## Decided (test item 3, 2026-10-14)

- **Pins source**: the packaged app reads the `models` section of the flake-rendered
  `versions.json`, which `stage.ts` copies into the package as `resources/versions.json`; the
  flake's `versions-sync` check keeps it true to `nix/models.nix`. A test-only `STT_MODELS_FILE`
  overrides the path — the packaged e2e points the app at fixture pins served from a local http
  server, so no test downloads the ~570 MB production model.
- **Progress screen**: `desktop/progress.html` plus a `preload.cts` `contextBridge` channel
  (`models:progress`, `models:error`, `models:retry`) — plain Electron, no server, no injected
  strings. The window loads it before the backend starts and navigates the same window to the
  loopback URL once both models are verified (item 1's final-URL assertion still holds).
- **Download test**: the spec runs a loopback http server serving the two store-file models the
  harness already passes, with fixture pins carrying the *real* sha256 from `versions.json`
  (`whisperTinyEn` for the whisper slot, `speaker` for the speaker slot): offline, deterministic,
  and it exercises the real download, the real hashing and the cache-reuse path.
- **Backend unchanged**: the app resolves the model paths (user-set `STT_*_MODEL` wins, else the
  downloaded cache file) and passes them to the backend it spawns, exactly as the Nix wrapper does.

## Decided (Linux distribution, 2026-10-15)

- **Three Linux artifacts, none exclusive** (the user: "a build only for Ubuntu/Debian does not
  meet the requirement of building for Linux"; "no harm in a `.deb` — I just don't want it to be
  exclusive"):
  - the **`.deb`** (unchanged): apt installs it; measured 2026-10-15, its floor is glibc 2.38 —
    Electron needs 2.25, the jlink JRE 2.15, bytedeco ffmpeg 2.35, the whisper-cli prebuild and its
    `libggml-base`/`libggml-vulkan` 2.38 — so Ubuntu 23.10+ and Debian 13+ only;
  - a **flake package** (`nix run .#desktop`): any distribution with Nix, NixOS included; the
    closure carries its own glibc, so there is no floor; it reuses the flake's backend, UI,
    whisper-cpp 1.8.4 (Vulkan) and `ffmpeg_8`, the same builds `nix run .` ships;
  - a **`nix bundle`** of that package: one self-contained executable for Linux machines that are
    neither Debian-based nor have Nix.
- **Models: first-run download in all three** — the bundle would otherwise be ~600 MB larger — so
  the flake package does **not** reference the models (no model in its closure). **The Nix store
  is a download cache** (the user's design): the models are `fetchurl` fixed-output derivations,
  whose store path is a function of the file name and sha256 alone, both of which `versions.json`
  carries. The app computes each model's store path and, when that file exists, uses it at once —
  the download screen shows it complete immediately, nothing is fetched or re-hashed (store paths
  are content-addressed). When it does not exist (no Nix, the server package never built, the
  store garbage-collected, or a bundle whose own store hides the host's), the normal download runs;
  a miss is never an error. Verified 2026-10-15 in plain Node against a real store: the computed
  paths of `whisper`, `speaker` and `whisperTinyEn` equal the store paths the devenv exports. It
  applies to every artifact, so the `.deb` on a machine with Nix hits the cache too.
- **CI: two Linux legs** — the existing `desktop-e2e` job on a plain runner (only the `.deb`, no
  Nix), and a runner **with Nix** that builds `.#desktop` and runs the packaged specs against it.
  "Any Linux" rests on the Nix closure, not on a distribution matrix (the user's choice).
- **One pinned Electron, identical in every build** (the user, 2026-10-15: "it must be pinned and
  identical across all builds"): the `.deb`'s Electron 44.7.1 (`desktop/package.json` /
  `pnpm-lock.yaml`) is the version; the flake package takes the same upstream release as a
  fixed-output derivation pinned in `nix/versions.nix`/`versions.json`, not nixpkgs' `electron`, and
  a check fails when the two pins differ.
- **Bundler chosen by evidence** in the first bundle cycle: `nix bundle`'s default first, on the
  no-Nix runner (Ubuntu 24.04, AppArmor); if it is blocked there, an AppImage bundler with
  extract-and-run. The result comes back to the user before one is committed to.
- **Bundle size is reported before it is accepted** (the user: is it viable as a download?): the
  first bundle cycle measures the download (compressed) and unpacked size and the user decides.
  Reference measured 2026-10-15 — the `.deb`'s payload (`desktop/dist/linux-unpacked`): 628 MB
  unpacked (Electron ~281 MB, tools 149 MB, backend jars 102 MB, jlink JRE 95 MB, UI 0.6 MB),
  233 MB as `tar | xz -6`. That is the `.deb`'s *payload* only: it leaves glibc, GTK3, NSS,
  ALSA/Pulse, X11/xcb, libgbm/libdrm, libstdc++ and the Vulkan loader to apt, while the bundle must
  carry them, so the fair comparison is the payload plus those apt dependencies. **The bundle cycle
  slims its closure by evidence** (the user, 2026-10-15: "can't we slim down the other dependencies as
  well?"): `nix path-info -rsh` lists the biggest store paths, and the candidates are the same jlink
  JRE modules the `.deb` uses instead of a full JDK, the slim ffmpeg (see Open questions), `libgbm`
  without Mesa's drivers (Electron has a software renderer), and glibc without locales. The cuts
  and the before/after sizes go to the user. Electron's GTK/NSS/X11 stack and glibc stay.
- Recorded as [ADR 0053](../decisions/0053-linux-desktop-deb-flake-package-bundle.md). It
  supersedes the drafted "desktop packaging stays outside the flake" and the drafted follow-on item
  for a flake-built artifact (both deleted; neither was ever on `main`).

## Proposal

1. Shape: an Electron main process that (a) resolves the per-user data dir (`%APPDATA%` /
   `~/Library/Application Support` / XDG), (b) starts the backend (bundled jlink JRE + fat jar) with
   the UI static dir and model paths set, (c) opens a BrowserWindow on the backend URL, (d) on quit
   asks the backend to stop and waits (no orphan JVM). Backend keeps owning all queue/engine logic;
   Electron is packaging + lifecycle, not a rewrite.
2. Packaging: `electron-builder` builds the `.deb` from the pinned prebuilt tools and a jlink JRE
   (`desktop/stage.ts`); the flake builds `packages.x86_64-linux.desktop` from its own backend,
   UI, whisper-cpp, `ffmpeg_8`, a JRE and the pinned upstream Electron 44.7.1, laid out like the
   `.deb`'s resources so the compiled `src/` (`main.ts`, `backend.ts`, `models.ts`) is shared;
   `nix bundle` wraps it.
3. Models: first-run download (progress, sha256, Retry) into the per-user cache; a model already in
   the Nix store at its computed fixed-output path is used instead (see the 2026-10-15 decision).
4. Tests: packaged-app e2e (ADR 0046) for every Linux artifact (CI: a plain runner for the `.deb`, a
   runner with Nix for the flake package); macOS/Windows with 48/49.

## Open questions

- (settled 2026-10-15: Electron — one pinned upstream 44.7.1 everywhere; the bundler — by evidence
  in the first bundle cycle; see "Decided (Linux distribution, 2026-10-15)")
- **Chromium's sandbox for the flake package and the bundle on Ubuntu 23.10+** (unverified): a
  store path cannot hold the setuid `chrome-sandbox` the `.deb` relies on, and AppArmor blocks the
  user-namespace sandbox for unconfined programs. To be settled when the Nix CI leg shows the
  actual behavior (an AppArmor profile documented for the user vs a `--no-sandbox` fallback —
  the window only ever loads the app's own loopback UI).
- **A custom slim ffmpeg — dropped by measurement; further closure cuts remain open.** The flake
  closure holds `ffmpeg-8.1.2-lib` (34.2 MiB, ours) and `ffmpeg-headless-9.0.1-lib` (34.4 MiB,
  Electron's group's — referenced by `pipewire`, `chromaprint`, `ffmpeg-headless-bin`). Our ffmpeg
  carries unused video codecs (`libavcodec` 57 MB unpacked in the `.deb`, `x265` 23.8 MiB in the
  closure), but it is about 58 MiB of 1598.5 MiB (3.6%); a custom build, its own format tests and
  a third build across all three artifacts are not worth that saving. `ffprobe` and bytedeco's
  `libjni*.so` (~3 MB) in the `.deb` are unused. This does **not** settle pruning other dependencies:
  python3 and gtk4 may still have removable roots (the user, 2026-10-15, asked to revisit). The
  referrers of `sdl2-compat` have not yet been queried, so the last failed SDL2 hypothesis is not a
  proof that the GStreamer/GTK-4 group is unavoidable.
- (settled 2026-10-14, see "Decided (first test item)")

## Test list (approved; behaviors only)

- [x] (e2e) Launching the packaged Linux app opens the window and the full journey — upload,
      transcribe, view history — completes without a separate browser or terminal
- [x] Quitting the app stops the backend and its processes (no orphan JVM/whisper/ffmpeg)
- [x] First run on a clean machine works: data dir + models are provisioned per the decided design,
      and data persists across restarts
- (moved 2026-10-15 to [48](../../roadmap/48-macos-support.md) and [49](../../roadmap/49-windows-support.md), not an item here)
  The packaged app runs on macOS and Windows with their per-user paths
- [x] (must not break) `gradle check`, `gradle integrationTest`, `pnpm test`, `pnpm build`, backend
      suites and the Linux e2e stay green (see the cycle-4 log line: only the documented item-47
      flake failed, and it is tracked there)
- [x] README documents the desktop app's three Linux ways (`.deb`, Nix package, bundle) and how they
      relate to the Docker/Nix server paths (reopened 2026-10-15: the cycle-6 README covers the
      `.deb` only)
- [x] (discovered) The desktop backend listens only on the loopback interface, not the LAN
      (`stt.MainKt` calls `embeddedServer(Netty, port = …)` with no host, so it binds
      0.0.0.0 — the desktop app would expose its API to the network and pick a random port
      each launch, so a LAN scan or firewall prompt would hit it) — implemented in cycle 1
      (`STT_HOST=127.0.0.1` in the Electron-spawned backend) and asserted by item 1.
- [x] (discovered) A download that fails its sha256 check (or the network) shows the reason on the
      download screen and its Retry re-runs provisioning instead of handing a corrupt model to the
      backend; a failed download leaves no partial file in the cache (surfaced while implementing
      item 3; covered by `e2e/desktop-tests/04-model-download-failure.spec.ts`, the failure path and
      its Retry) (confirmed 2026-10-14: keep as an item, test it in a later cycle)
- [x] (added 2026-10-15) (e2e, CI with Nix) `nix build .#desktop` builds the desktop app from the
      flake, and the packaged specs — journey, quit, first run with the download, download failure
      with Retry — pass against it
- [x] (added 2026-10-15) The flake package's closure contains no model, so the bundle made from
      it stays small
- [x] (added 2026-10-15) First run with the pinned models already in the Nix store: no model is
      requested from the network, the download screen shows both complete at once, and the backend
      transcribes with the store files; with only one of them in the store, only the other is
      downloaded
- [x] (added 2026-10-15) (e2e) The `nix bundle` executable runs on a Linux machine without Nix
      and without the `.deb`'s system packages, and the packaged specs pass against it
- [x] (discovered, the user 2026-10-15) (e2e, bundle) The bundle's app sees the whole machine like
      any installed app, with the user's own permissions, except `/nix`, which is its own
      read-only store (a host's `/nix` is hidden)
- [x] (added 2026-10-15) (must not break) The `.deb` keeps passing its packaged specs on the
      plain runner; `nix build .`, `nix run .` and `nix flake check` stay green
- [x] (discovered, the user 2026-10-15, after trying `nix run .#desktop`) (e2e) The app window has
      no menu bar: no File/Edit/View/Window/Help menu above the UI, in every package

- [x] (approved 2026-10-15, the user's question) **The app's window cannot reach the network.**
      Requests from the window go to the loopback backend and to the progress page's own file
      only; anything else is refused (a request to the same fixture server on a *non*-loopback
      address fails while the loopback one succeeds, so the test distinguishes policy from an
      absent network), permission requests are denied, and `window.open`/external navigation
      produce no window. Provisioning keeps its own path: the model download runs in the main
      process, and that path is limited to GitHub model hosts as specified below.
- [x] (the user 2026-10-15: **a one-time manual check, not a test item, not in CI**) The `.deb`'s
      whisper-cli, in a stock Ubuntu container with the host's `/dev/dri` and the distribution's
      `mesa-vulkan-drivers`, finds the AMD GPU through the system's Vulkan loader and transcribes
      the JFK clip on it — recorded once in the Approval log (the bundle's GPU paths are the two
      approved items below)
- [x] (approved 2026-10-15) (e2e, bundle, AMD — the user's machine) In an Ubuntu container with
      only the host's `/dev/dri` passed in (no Mesa installed there), the bundle's device probe
      finds the AMD GPU (RADV, shipped in the bundle), and a transcription with that GPU chosen
      completes without the CPU fallback
- [x] (approved 2026-10-15) (e2e, bundle, NVIDIA's own driver — the user's machine) In an Ubuntu
      container with the host's `/dev/nvidia*` devices and NVIDIA's user-space libraries of the
      host's driver version installed (what an Ubuntu host with that driver has), the bundle's
      device probe finds the NVIDIA GPU (the host's driver, borrowed), and a transcription with that
      GPU chosen completes without the CPU fallback. NVK (NVIDIA on `nouveau`) and ANV (Intel) ship
      in the bundle untested: no such setup is available
- [x] (approved 2026-10-15; the user chose GitHub-only, CAM++ remains on k2-fsa) **Provisioning
      may reach only GitHub model hosts**: the whisper weights will be mirrored at this repository's
      GitHub Release (planned versioned tag `desktop-models-v1`, asset
      `ggml-large-v3-turbo-q5_0.bin`; the user uploads the release asset — agents never publish to
      GitHub). The pin moved to that mirror once the asset existed and was verified live
      (2026-10-15). The CAM++ model stays at the `k2-fsa/sherpa-onnx` GitHub Release. Both resolve
      via `github.com` to
      `release-assets.githubusercontent.com`. Loopback remains allowed for a local mirror and the
      deterministic fixture. A pin to any other host, or a redirect hop outside the allowlist, is
      refused before a request with the refused host named, and leaves no partial/cache file; the
      existing SHA256 verification remains authoritative.
- [x] (approved 2026-10-15, weekly) **Both pinned model URLs still resolve only through the
      allowlist**: a CI schedule job follows their redirect chains with ranged GETs, checks every
      hop is `github.com` or `release-assets.githubusercontent.com`, and fails naming the moved
      host. It reads `versions.json.models` only (not build-time tool URLs) and transfers no model
      body; a CDN move is caught before a user's first run.
- [x] (discovered, review 2026-10-15) (e2e) When the backend has started but never answers, the
      download screen shows the error only after that backend is gone; after Retry, at most one
      backend is ever running, and quitting leaves none
- [x] (discovered, review 2026-10-15) (e2e) A second quit request (SIGTERM) while the app waits for
      a backend that is slow to stop still waits for that stop: once the app has exited, no
      `stt.MainKt`, `whisper-cli` or `ffmpeg` is left
- [x] (discovered, review 2026-10-15) (e2e) Provisioning accepts exactly `github.com` and
      `release-assets.githubusercontent.com` (plus loopback): a pin, or a redirect hop, to any other
      githubusercontent.com host (e.g. `raw.githubusercontent.com`) is refused before a request,
      naming the host, and leaves no cache or partial file
- [x] (discovered, review 2026-10-15) (packaged `.deb`) The `.deb` declares its glibc floor: apt
      refuses it on a distribution older than glibc 2.38 (Ubuntu 22.04, glibc 2.35), naming
      `libc6`; on Ubuntu 24.04 it still installs and passes the packaged specs

## Acceptance criteria

- Distributable Linux desktop builds exist for any distribution (macOS/Windows are produced by
  48/49): the `.deb` (electron-builder: backend, UI, jlink JRE, prebuilt tools), the flake package
  `.#desktop` and its `nix bundle`; clean start/stop lifecycle; per-user data; model first-run
  download with the Nix store as a cache; documented; each verified by the packaged e2e.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-13: filed as the packaging follow-on to the macOS/Windows items.
- 2026-10-13: consultation — Linux-first stepping stone (dependency on 48/49 dropped); model
  first-run download; bundled jlink JRE; unsigned v1 without auto-update; electron-builder. Status:
  spec-approved.
- 2026-10-14: first test item (item 1, the packaged e2e) — user settled the open questions and the
  implementation choices recorded under "Decided (first test item, 2026-10-14)".

- 2026-10-14 (workspace, cycle 1 = test item 1): green by the user's run of the packaged e2e in a
  stock `ubuntu:24.04` container — `1 passed (6.7s)`: the installed `/usr/bin/stt-app` opened its
  window on `http://127.0.0.1:<random>/queue`, the API answered on no other interface, the upload
  of `jfk.wav` completed, History showed the transcript with the JFK quote, one window, clean
  close. On the way the run surfaced six defects, all fixed in-cycle: the backend resources dir,
  the java classpath and the shadowed `process` in `desktop/backend.mjs`; the harness's missing
  `-v` and `--cap-add=SYS_ADMIN`; the deb's `libasound2` (virtual on noble → the OSS4 shim);
  ffmpeg/ffprobe staged non-executable (zip drops modes); and the test's own `/` landing-route
  assumption (`App.tsx` redirects to `/queue`).

- 2026-10-14 (workspace, cycle 2 = test item 2): green by the user's run — `2 passed (10.8s)`, the
  journey (item 1) still green after the three approved refactors. The new test
  (`e2e/desktop-tests/02-quit.spec.ts`) launches the installed app, waits for "Live", uploads
  `jfk.wav`, quits with `app.close()`, then reads `/proc` for 15 s: no `stt.MainKt`, `whisper-cli`
  or `ffmpeg` process survives the app item 26's shutdown semantics, driven from Electron. Red
  recorded in the sandbox before it (`spawn /usr/bin/stt-app ENOENT`). No defects surfaced in this
  cycle; the refactors (`68d3cbc3`, `e9c8859d`, `4227fe92`) are behavior-preserving and were
  verified in the sandbox (a plain Node script drives `backend.mjs` now).

- 2026-10-14 (workspace, cycle 3 = test item 3): green by the user's run — the first-run spec passes
  with items 1 and 2. `03-first-run.spec.ts` launches the app with the model overrides removed and
  fixture pins (`STT_MODELS_FILE`) at a loopback server holding the responses until `release()`: it
  asserts the download screen (`progress.html`) before any backend exists, then the loopback
  `/queue` URL, the database in `~/.local/share/stt-app`, both verified files in
  `~/.cache/stt-app/models`, the upload→transcript journey, and finally a restart with the same
  HOME that adds **no** model request and still shows the transcript. One defect fixed in-cycle: the
  spec read the repository's versions.json, which is not mounted into the container — the pinned
  sha256 values now travel from the harness as `TEST_{WHISPER_TINY_EN,SPEAKER}_SHA256` (`ee5d84c2`).
- 2026-10-14: the `(discovered)` loopback item is ticked: cycle 1 set `STT_HOST=127.0.0.1` for the
  backend the Electron main spawns, and item 1's test asserts that no other interface of the
  machine answers on the app's port.

- 2026-10-14 (item 50, CI — user request): the desktop app is in CI. A `desktop-e2e` job on
  `ubuntu-24.04` builds the `.deb` with electron-builder (which fetches its own fpm and Electron;
  `USE_SYSTEM_FPM` stays a devenv-only workaround), installs it with apt the way a user does (its
  declared Depends pull the runtime libraries), exports the pinned fixtures and models from the
  Gradle cache, and runs the three packaged specs under Xvfb; the required `ci` job now waits for
  it. CI drives the specs directly on the runner instead of through
  `desktop/run-in-docker.mjs`, whose `/nix/store` contract only a Nix host satisfies. The job's
  fixture-cache paths and `actionlint` were verified locally; its apt/Xvfb half first runs on
  GitHub, and that run is the evidence for it.
- 2026-10-14: rebased onto `main` after the item-51 PR merge (main's history was rewritten, so the
  branch was rebased with `--onto main 9aa7acb4`, replaying only its own 35 commits; the two
  conflicts were additive — `devenv.nix`'s package list, resolved by keeping both `actionlint`
  (item 51) and `fpm`/`xvfb-run` (item 50)).

- 2026-10-14 (workspace, cycle 4 = test item 5): the must-not-break suites, run on this branch.
  Frontend `pnpm test` 173 tests + prettier ✓ and `pnpm build` (tsc + vite) ✓; backend
  `gradle integrationTest` ✓; `gradle check` 274 tests with the only failure the **documented
  item-47 flake** (`ProcessingEngineAtomicTransitionsSpec`, `expected:<[1L, 2L]> but was:<[1L]>` —
  already tracked, not item 50's); the main Linux e2e suite **21 passed (6.0 m)**. Two notes on the
  e2e run: it ran in the agent sandbox (no `/bin/sh`, so the CLIs were invoked directly and the
  backend as `E2E_BACKEND_CMD="java -cp …lib/* stt.MainKt"` with the tiny model), and that command
  needs `STT_STATIC_DIR` in the environment — the harness's `E2E_BACKEND_CMD` branch leaves it to
  the command, which is right for the packaged wrapper it normally points at. The packaged desktop
  specs are green by the user's runs and run in CI. Item 5 ticked.

- 2026-10-14 (workspace, cycle 5 = test item 8): green by the user's run — `4 passed`, items 1–3
  unchanged by the cycle's three refactors. `04-model-download-failure.spec.ts` corrupts the first
  response of the whisper fixture (one flipped byte, the pins carry the real sha256): the download
  screen reports the failure and shows Retry, no cached file and no `.part` survives, and after the
  click the retry reaches the loopback `/queue` URL with both cache files matching the pins. First
  run failed in the spec's own setup — the fixture server holds responses until `release()` (item 3
  needs that hold) and item 8 never released it, so the download hung and no error appeared; fixed in
  `7d161581` by releasing right after the launch. The app needed no change: the failure path landed
  with item 3.

- 2026-10-14 (workspace, cycle 6 = test item 6): the README documents the desktop app — a row in
  "Ways to run it", a "Desktop app (Linux `.deb`)" section (build, install with apt, the first-start
  model download with Retry, the shared data directory, loopback-only, quitting stops every
  process, platforms: tested on Ubuntu 24.04, the bundled whisper.cpp needs glibc 2.38+), its row in
  "Where your data lives", and the new `STT_HOST` variable in "Configuration". The docs checks pass:
  `check-links` (README + architecture, incl. the new anchor), `check-architecture-names` (170
  names) and `check-architecture-diagram`. Before it, two approved refactors: the fixture server's
  response hold is opt-in, and the two model specs share `writeFixturePins()`.
- 2026-10-14 (workspace): rebased onto main `2790b70e` (item 53, the Nix store cache) — one
  conflict in `ci.yml` (main's new Nix-jobs comment where `desktop-e2e` is inserted; both kept,
  actionlint clean); `gradle check` green on the rebased tree. The user's packaged run after the
  two refactors and the rebase: green (all four desktop specs).
- 2026-10-15: the user decided to hand item 50 over for review without waiting for 48/49: test
  item 4 (the macOS and Windows packaged runs) moves into 48's and 49's test lists, which ship
  those platforms; here it stays as an unchecked note, not a test item, and "Depends on"/"Blocks"
  and the acceptance criteria say so. Every remaining test item is ticked and green. Status:
  in-review.
- 2026-10-15: **reopened** before review. The user: a build only for Ubuntu/Debian (or a container
  of one) does not meet "build for Linux". Measured: the `.deb`'s floor is glibc 2.38 (the
  whisper-cli prebuild), so Ubuntu 23.10+/Debian 13+ only. Decided (see "Decided (Linux
  distribution, 2026-10-15)"): keep the `.deb`, add the flake package `.#desktop` and its
  `nix bundle`; first-run download in all three, with the Nix store as a cache (the user's design;
  the fixed-output path computation verified against a real store); CI gets a second Linux leg with
  Nix. No Flatpak (the user). Item 6 unticked (the README must cover all three). Five test items
  and three open questions proposed — awaiting approval. Status: in-progress.
- 2026-10-15: the five proposed test items **approved as written**. Decided: one pinned Electron
  (44.7.1), identical across the `.deb`, the flake package and the bundle; the bundler by evidence
  in the first bundle cycle; the bundle's size is measured and reported before it is accepted
  (reference: the `.deb`'s payload is 628 MB unpacked, 233 MB xz-compressed). The Chromium-sandbox
  question stays open until the Nix CI leg shows the behavior. Status: in-progress.
- 2026-10-15: slim ffmpeg raised by the user; decided by measurement of the Nix closure first (see
  Open questions).
- 2026-10-15 (workspace, cycle 7 = the Nix-store cache item): red by the user's packaged run —
  `e2e/desktop-tests/05-model-nix-store-cache.spec.ts` failed at `expect(server.requestCount())
  .toBe(0)`, `Received: 2` (the app downloaded both models); the four other specs passed. Fix:
  `desktop/models.mjs` computes each pin's flat sha256 fixed-output store path from its file name
  and sha256 and, when that file exists, uses it before the per-user cache (no request, no copy, no
  re-hash; the screen shows it at 100%). Smoke in the sandbox with plain Node against the real
  store: both in the store → 0 requests, nothing cached; speaker only → 1 request, the whisper file
  cached; the store basenames specs 03/04 pin → a miss and a download. Green by the user's run
  after rebuilding the `.deb`: `5 passed (38.4s)`. The spec skips itself where the models are not
  store paths (the plain CI runner).
- 2026-10-15 (cycle 7 checkpoint): two approved refactors — `locateModel()` in
  `desktop/models.mjs` decides where a pinned model comes from (store, verified cache, download;
  sandbox smoke of all three branches incl. cache reuse on restart), and the desktop specs share one
  `/proc` scan (`processes()` in `helpers/app.ts`). A symlink from the cache into the store was
  considered and not taken (the user asked): the computed store path cannot go stale, while a link
  is state, is re-hashed like any cached file, and would change what lands in the cache.
- 2026-10-15: **ADR [0054](../decisions/0054-no-hand-written-javascript.md)** (the user: "no
  javascript code is written directly by us, only as a compilation output of typescript"): shipped
  code compiled with `tsc`; Node-run scripts as `.ts` run directly (type stripping, erasable syntax
  only, compiled instead if that ever makes them ugly), guarded by `tsc --noEmit` in `pnpm test`;
  configuration as data. Item 50 converts its own JavaScript now, as a refactor before the flake
  package item; the older scripts go to draft item
  [54](../../roadmap/54-typescript-for-the-remaining-scripts.md). Next test item (accepted): the flake package.
- 2026-10-15 (refactor, ADR 0054): item 50's JavaScript is TypeScript. `desktop/src/`
  (`main.ts`, `backend.ts`, `models.ts`, `preload.cts` → `preload.cjs`, `progress.ts` — the download
  screen's former inline script —, `bridge.d.ts`) is compiled by `tsc` into the ignored `build/`,
  which the `.deb` ships (`main` = `build/main.js`); `stage.ts`, `fetch-jdk.ts`,
  `verified-download.ts` and `e2e/desktop/run-in-docker.ts` run directly with Node 24's type
  stripping; `electron-builder.yml` replaces the `.cjs` config. `pnpm test` in `desktop/`
  (prettier + three `tsc` projects) and in `e2e/` (+ `tsc -p desktop/tsconfig.json`) type-check
  them, and CI's `desktop-e2e` job now runs both. The guard found one real type hole on the way:
  the specs' launch env could carry `undefined` values (now filtered). Sandbox: all type checks
  clean (a planted error is reported), `tsc` builds, `node stage.ts` stages, electron-builder packs
  an asar of exactly `build/*.js`, `build/preload.cjs`, `progress.html`, `package.json` (fpm, as
  always, only on the user's machine); the compiled `models.js` hits the real store; the harness
  runs as `.ts`. Proof of no behavior change: the user's packaged run — **all green** (2026-10-15:
  `pnpm test` in `desktop/` with prettier and the three type checks, the `.deb` rebuilt from the
  TypeScript build, the five packaged specs passing).
- 2026-10-15 (workspace, cycle 8 = the flake package item): red by the user's run of the new
  `pnpm --dir e2e test:desktop:nix` — `flake 'git+file:///home/lulu/code/speech-to-text-app' does
  not provide attribute 'packages.x86_64-linux.desktop'`. The harness (`e2e/desktop/run-in-docker.ts
  --nix`) runs `nix build .#desktop` on the host and the same five specs in the stock
  `ubuntu:24.04` container, which installs nothing: the app runs from the read-only `/nix/store`
  mount (`STT_DESKTOP_APP`, default the `.deb`'s `/usr/bin/stt-app`). Fix: `nix/desktop.nix` +
  `nix/electron.nix` (Electron 44.7.1, the release's sha256; evaluation fails when it differs from
  `desktop/package.json`) — nixpkgs' own prebuilt-Electron recipe from the locked rev (`binary/
  generic.nix`, rpath over Electron's libraries, GTK wrapping) called with our pin; `desktop/src`
  built by the same `pnpm build` in a `fetchPnpmDeps` derivation (store hash from the user's first
  build); resources laid out like the `.deb`'s (the flake's backend jars, UI, whisper-cpp 1.8.4,
  `ffmpeg_8`, `pkgs.jre`, `versions.json`), no model referenced; `packages`/`apps` `desktop`. Green
  by the user's run: `5 passed (39.2s)` — Electron started with Chromium's sandbox on and no setuid
  helper (user namespaces) in a container on the user's host. CI: a `desktop-nix-e2e` job (Nix
  toolchain, devenv CLI, the same command in the devenv shell) on `ubuntu-24.04`, required by `ci`;
  actionlint clean; its first GitHub run is also the evidence for the sandbox question under
  Ubuntu's AppArmor user-namespace restriction.
- 2026-10-15 (workspace, cycle 9 = the model-free closure item): `checks.x86_64-linux.desktop-model-free`
  (refactors first: the specs say "packaged", the harness's `userdel` is quiet; the menu-bar item
  appended as discovered, confirmed by the user's request after trying `nix run .#desktop`, which
  "works great"). The check fails when a closure path ends in a pinned model's file name and writes
  the closure's size report. No red: the package is model-free by design, so the check passed on
  its first build (the user's run); that the pattern catches a model was shown against the three
  real model store paths in the sandbox. **The measurement** (`du -sb` per store path): **2152.9
  MiB, 340 store paths**. Largest: openjdk-21.0.12.1+1 571.8 (`pkgs.jre` is the full JDK),
  electron-44.7.1 281.3, python3-3.14.7 135.8, stt-backend 101.4, whisper-cpp-1.8.4 79.1,
  systemd-261.3 62.8, flite-2.2-lib 60.4, gtk+3 41.9, icu4c 39.7, ffmpeg-headless-9.0.1-lib 34.4,
  ffmpeg-8.1.2-lib 34.2, glibc 33.7, freepats 31.8, gtk4 30.6, x265 23.8, iso-codes 22.3,
  systemd-minimal 22.1, gst-plugins-bad 17.2, pipewire 16.8, librsvg 15.6, glib 15.4,
  util-linux-lib 14.0, libajantv2 13.4, mbedtls 12.8, file 10.5. The ffmpeg question's answer
  belongs with the bundle cycle's slimming (decided 2026-10-15: cuts by evidence, sizes to the
  user): the JDK (a jlink image like the `.deb`'s) and Electron's optional runtime libraries
  (speech synthesis — flite, freepats, python? —, PipeWire, gtk4, GStreamer) are larger levers than
  ffmpeg_8's own 34 MiB plus its codec libraries.
- 2026-10-15 (workspace, cycle 10 = the discovered menu-bar item): red by the user's run of the new
  `06-no-menu-bar.spec.ts` against the flake package — Electron's default menu is set
  (`applicationMenu: true`) and shown (`menuBarsVisible: [true]`), the other five specs passing.
  Fix: `Menu.setApplicationMenu(null)` in `desktop/src/main.ts` before the window is created (the
  compiled `build/main.js` calls it at line 16, the window is created at 17). Green by the user:
  both modes, `6 passed`, and by hand — `nix run .#desktop` shows no menu bar ("all green").
- 2026-10-15 (workspace, cycle 11 = the bundle item, slimming by evidence): the closure went
  **2152.9 → 1679.0 MiB** (`du -sb` over 339 paths). The JRE cut did all of it: `pkgs.jre` (the
  full JDK, 571.8 MiB) → `pkgs.jre_minimal` over the shared `desktop/jlink-modules.txt`
  (97.9 MiB) — the same module list the `.deb`'s jlink image uses, now written once and read by
  both `desktop/stage.ts` and `nix/desktop.nix`. Switching off four of Electron's recipe
  arguments (speech synthesis, screen capture, GTK 4, printing) **saved one store path** (339 →
  338) and broke the app: Electron's binary has `libcups.so.2` as a hard `DT_NEEDED`
  (`error while loading shared libraries: libcups.so.2`, exit 127, all six specs). Reverted; the
  recipe keeps its whole library set. Referrers inside the closure (`nix-store -q --referrers`
  filtered to it) say why the arguments did not help: `python3` ← `gstreamer`, `flite` ←
  `gst-plugins-bad`, `freepats` ← `wildmidi.cfg`, `ffmpeg-headless-9.0.1-lib` ← `pipewire`,
  `chromaprint`, `ffmpeg-headless-bin`, `gtk4` ← electron, `libadwaita`, `zenity`. The roughly
  330 MiB GStreamer/PipeWire group therefore hangs off something else, to be found with the next
  round of referrers. Green by the user: `6 passed (42.1s)` against the cut package.
- 2026-10-15 (workspace, cycle 11, continued): one argument replaced (not removed): the recipe's
  `systemd` → `pkgs.udev` (= `systemdLibs`; the recipe only wants libudev) — **1679.0 → 1598.5 MiB**,
  339 → 321 store paths, `6 passed` by the user's run, so Electron needs no more of systemd than
  libudev. Total cut so far **2152.9 → 1598.5 MiB (−554 MiB, −26 %)**. The referrers of what is
  left (`nix-store -q --references` on Electron itself, then `-q --referrers` filtered to the
  closure) give the tree: Electron references `cups-lib`, `gtk4`, `systemd-minimal-libs` (the new
  udev) and **`pipewire`**; `pipewire` reaches `gstreamer` → `gst-plugins-bad` → `flite`,
  `freepats`, `chromaprint` and `python3`, and directly `ffmpeg-headless`; `gtk4` reaches
  `gstreamer` again. Other referrers keep those alive: `pipewire` ← `sdl3-lib`, `openal-soft`;
  `gtk4` ← `libadwaita` ← `zenity` ← `sdl3-lib`; `gstreamer` ← `libnice`, `libcanberra`,
  `libcamera`, `gst-plugins-base`. So the ~330 MiB group is one tree rooted at Electron's
  `pipewire` and `gtk4` references (with `sdl3`, `zenity`, `openal-soft`, `libcamera` as the
  intermediate keepers).
- 2026-10-15 (workspace, cycle 11, **measurement checkpoint; closure investigation reopened by the
  user**): the `pipewire`/`gtk4` override and the ffmpeg `withSdl2 = false`, `buildFfplay = false`
  experiment each left the closure effectively unchanged (1598.5 MiB; SDL saved 0.3 MiB). The
  observed chains explain some alternate refs, but the referrer of `sdl2-compat` was not requested;
  no conclusion that python3/gtk4 are uncuttable follows. Keep full Electron optional libraries
  and normal ffmpeg meanwhile. The proven cuts remain JRE (−474 MiB) and libudev-only systemd
  (−80 MiB), **2152.9 → 1598.5 MiB (−554 MiB, −26%)**; the user confirmed the flake app works.
- 2026-10-15 (workspace, cycle 12 = the bundle): the default `nix bundle` bundler (`nix-bundle`,
  which `NixOS/bundlers#toArx` also uses) failed with `attribute 'pname' missing` — `.#desktop`
  resolved to the **app** attr (it has `program`, no `pname`) rather than the package. The
  explicit `.#packages.x86_64-linux.desktop` is the fix — and with it the bundle builds:
  `/nix/store/l98vwlgs…-stt-desktop-arx`, **635 MB** (measured by the user, 2026-10-15; `stat -L`,
  since `nix bundle`'s out-link is a symlink into the store). That is the download a user faces, on
  any distribution, with no apt dependencies — for scale, the `.deb` is 233 MB compressed plus the
  apt packages it declares. The format is the arx one from `nix-community/nix-bundle`:
  a self-extracting **bzip2** tarball plus a launcher, so the target machine needs `bzip2` (a
  minimal `ubuntu:24.04` has `tar` but not `bzip2`, and the first container run died there:
  `tar (grandchild): bzip2: Cannot exec: No such file or directory`) and, for the store view, user
  namespaces — AppArmor is still untested. The user's run mounted the bundle at `/bundle` and
  launched it with Docker's default working directory `/`; it produced no app output and failed at
  `../run: 1: ./nix/store/sjpir6dq…-startup: not found`. The invocation could not find a generated
  relative startup path; **why is not established**. This is before Electron starts, so it proves
  neither a `nix-bundle` defect nor that the bundle works. The `nix-bundle` README says the archive
  extracts a `./nix` folder and starts nix-user-chroot against that relative path; test from the
  expected writable working directory before deciding. No AppImage pivot: the `NixOS/bundlers`
  README calls nix2appimage experimental and says it does not work out of the box on NixOS.
- 2026-10-15: the user asked to revisit two conclusions made in passing. **Closure slimming is not
  closed**: although the two attempted cuts saved no size, the referrer of `sdl2-compat` was not
  queried, so the possible roots of python3/gtk4 have not been fully tested. The bundle is **not
  yet a dead end**: the 635 MB arx file failed before Electron ran, with `./nix/store/...-startup`
  unresolved when launched from Docker's default `/`; the CWD hypothesis has not been tested. No
  AppImage switch is decided (NixOS/bundlers' own README calls its AppImage output experimental and
  says it does not work out of the box on NixOS). Next evidence: query the `sdl2-compat` referrer;
  rerun arx from its expected writable working directory. No more cuts or bundler switch until then.
- 2026-10-15 (reopened investigation, the user's two diagnostics): **the root is whisper-cpp.**
  `nix why-depends .#desktop <sdl2-compat>` gives
  `stt-desktop → stt-desktop-tools → whisper-cpp-1.8.4 → sdl2-compat-2.32.72`: nixpkgs' recipe
  takes `withSDL ? true` and builds whisper.cpp's SDL example, which alone pulls SDL2 → sdl3 →
  zenity → libadwaita → gtk4 → gstreamer → gst-plugins-bad → flite, freepats, chromaprint,
  python3, openal-soft → pipewire (~330 MiB, plus `ffmpeg-headless` through pipewire/chromaprint).
  Fix in our own `nix/whisper-cpp.nix`: `withSDL = false` — only `whisper-cli` is used (ADR 0047)
  and it is not the SDL example. That reverts my "slimming is closed" conclusion: it was never
  tested until this query, and every earlier hypothesis (Electron's args, ffmpeg's SDL) was wrong
  because the chain starts at whisper-cpp.
- 2026-10-15: the arx CWD hypothesis is **disproven**: run from a writable working directory
  (`-w /work`), the bundle prints nothing at all for 30 s and exits 124 (timeout), with no
  `startup` error this time. So the earlier relative-path error was not simply the working
  directory; extracting a 635 MB bzip2 archive may just be slow (bzip2 on ~1.6 GB) or the launcher
  is waiting on something. Next: identify the bundle's actual format/launcher (first bytes) and
  check whether an extracted `nix/` tree appears, before judging the bundler.
- 2026-10-15: `withSDL = false` measured: `whisper-cpp` 79.1 → 73.5 MiB and the closure 1598.5 →
  1592.8 MiB, **with `sdl2-compat`, `gtk4`, `python3`, `flite` and `pipewire` all still present** —
  so whisper-cpp was one entry point into that cluster, not the only one. The earlier
  `why-depends` gave only the single shortest chain; `--all` (or the direct referrers) is needed to
  see every path before concluding anything. `6 passed` with the cut, so it stays.
- 2026-10-15 (**the arx bundle is not broken — it needs unprivileged user namespaces**): the file's
  first bytes are `nix-bundle`'s arx launcher (`#!/bin/sh`, `tmpdir=/$HOME/.cache`, `shared=true`,
  `hash=48c4ed35d091eb72`). Extraction works — `/root/.cache/tmpx-48c4ed35d091eb72` holds **1.7 GB**
  — so the earlier "silence" was a 635 MB bzip2 unpack against a 30 s timeout. It then stops with
  `Run the following to enable unprivileged namespace use: sudo bash -c "sysctl -w
  kernel.unprivileged_userns_clone=1 ; …"`: `nix-user-chroot` cannot create the namespace inside
  the container. That answers the Open question for containers and for Ubuntu 23.10+/24.04 (AppArmor
  restricts unprivileged user namespaces for unconfined programs); NixOS normally allows them, so
  the bundle should run there. Note for the README and the acceptance: every launch extracts ~1.7 GB
  into `$HOME/.cache/tmpx-<hash>` (`shared=true` reuses it when present).
- 2026-10-15: **the user-namespace sandbox question remains open.** I previously called a run with
  `unshare --user --map-current-user` proof, but the user's actual command used
  `--map-root-user`: Electron correctly rejected that process as root. That run says nothing about
  the sandbox for an ordinary uid. The bundle's ordinary run found a non-setuid SUID helper; forcing
  `--disable-setuid-sandbox` inside arx gave `No usable sandbox!`, which is evidence the namespace
  sandbox failed *in that invocation*, but not that it cannot work with a corrected namespace setup.
  Re-test the flake package with `--map-current-user` and explicitly force the namespace sandbox
  before deciding whether any artifact needs `--no-sandbox`.
- 2026-10-15 (**the arx bundle runs with `--no-sandbox`, but its UI is broken on a NixOS host**):
  the window opened, the backend started (`Responding at http://127.0.0.1:43565`, 7 migrations
  validated, `Device probe: 0 GPU(s)`), and then:
  `MESA-LOADER: failed to open dri: /run/opengl-driver/lib/gbm/dri_gbm.so` (no GPU path in the
  bundle), `Fontconfig error: Cannot load default config file: File not found`, and a Chromium
  FATAL `SkFontMgr_FontConfigInterface.cpp:163 Not implemented`. Cause: the bundle's chroot binds
  the host's `/etc`, whose NixOS `fonts.conf` is a symlink into the *host* store, which is not the
  bundle's store — so this specific breakage is host-side (a non-Nix distribution has a real
  `/etc/fonts/fonts.conf`), but it is the second real usability finding for arx, after the sandbox.
  Candidate fix if the bundle is kept: set `FONTCONFIG_FILE` in the launcher to the package's own
  fontconfig config.
- 2026-10-15: the AppImage bundler builds (`NixOS/bundlers#toAppImage`, no `.desktop` objection):
  **671 MB**, i.e. *larger* than arx's 635 MB, so no compression win; whether it runs (and whether
  Chromium sandboxes itself there, since no namespaces are involved) is untested.
- 2026-10-15 (lesson, recorded because it misled twice): checking a package's presence with
  `grep -E -- "-$name(-[0-9.]+)?$"` silently misses names like `flite-2.2-lib` or `gtk4-4.22.4`.
  Both "gone" lists I derived that way were partly wrong; `nix why-depends --all` is the reliable
  instrument, and a cut must be re-measured before any conclusion.
- 2026-10-15 (workspace, cycle 11, the cut that worked): with the two SDL entry points already
  cut, switching off Electron's `gtk4` and `pipewire` arguments removed the whole cluster —
  `gtk4`, `gst-plugins-bad`, `gstreamer`, `gst-plugins-base`, `libnice`, `openal-soft`, `flite`,
  `chromaprint`, `python3`, `pipewire`, `ffmpeg-headless` all absent. **Closure 1592.8 →
  **1133.7 MiB, 321 → 198 store paths**, `6 passed`** by the user's run. Full journey:
  **2152.9 → 1133.7 MiB (−1019.1 MiB, −47 %)** from, in order: the jlink JRE (−474),
  libudev-only systemd (−80), whisper-cpp without its SDL example (−5.7), ffmpeg without
  SDL2/ffplay, and Electron without `gtk4`/`pipewire` (−431). The lesson worth keeping: the same
  three cuts individually saved nothing and only work in sequence, so each had to be re-measured
  after the previous one; the earlier "slimming is closed" and "python3/gtk4 are unavoidable"
  conclusions were both wrong.
- 2026-10-15: the AppImage bundler reaches a second namespace requirement: with no
  `CAP_SYS_ADMIN` the AppImage's own `AppRun` prints
  `cannot unshare: Operation not permitted` (NixOS/bundlers' AppImage provides the store view with
  unshare too, but *without* a chroot, so no `/etc` symlink breakage and Chromium should keep its
  sandbox). Retest with the container's `--cap-add=SYS_ADMIN`, which the e2e harness already uses.
- 2026-10-15 (bundle measurements at the cut closure): **arx 445 MB, AppImage 478 MB** (from
  635/671 before the closure work). With `--cap-add=SYS_ADMIN` the AppImage's `unshare` succeeds,
  but Electron reports `The SUID sandbox helper binary was found, but is not configured correctly`.
  This shows the setuid path is unusable in the store; it does **not** show that the namespace
  sandbox is unusable. For arx, `--disable-setuid-sandbox` reported `No usable sandbox!`, but the
  only control compared against it used the erroneous `--map-root-user` (fixed in the entry above);
  the correct ordinary-uid namespace control and a forced namespace run in AppImage remain
  untested. **No decision yet** to ship with `--no-sandbox` or drop either bundler. Other measured
  differences remain: arx needs `bzip2`, extracts 1.7 GB at launch and broke NixOS fontconfig;
  AppImage needed `--appimage-extract-and-run` without FUSE and had no fontconfig error in that
  container. Both still require their AppRun/Arx unshare to work (Ubuntu AppArmor remains a test).
- 2026-10-15: bwrap diagnostic using `--unshare-user --unshare-pid --dev /dev --proc /proc`,
  bind-mounted `/nix`, `/etc`, `/tmp`, `$HOME`, and launched the flake app with
  `--disable-setuid-sandbox`: **9 Electron processes, no sandbox/font error**. (The run had no
  display/Wayland sockets, so it logged the expected display errors; it was a sandbox/process
  diagnostic, not a UI test.) This is strong evidence Chromium's namespace sandbox works when the
  app runs in a bubblewrap root. It does not yet explain arx: bwrap used a PID namespace, while
  nix-user-chroot unshares only user+mount. **Answer (the user's runs): both bwrap variants work,
  window and all** — without `--unshare-pid`, and with the host's `/proc` bound instead of a fresh
  one (`/run` had to be bound too, or Ozone cannot reach the Wayland socket and Electron exits with
  `The platform failed to initialize`). So the sandbox question is **closed: Chromium's namespace
  sandbox survives a bwrap-built root**, and the upstream bundlers' launchers are what lose it.
- 2026-10-15 (upstream survey, before proposing custom work): `NixOS/bundlers` provides `toArx`
  (nix-bundle), `toAppImage` (`ralismark/nix-appimage`), `toRPM`/`toDEB` (nix-utils, Debian/RPM
  only — the `.deb` already covers that ground), `toDockerImage`, `toBuildDerivation`, `toReport`,
  `identity`. `nix-appimage`'s only AppRun is `userns-chroot` (nix-user-chroot-like), and its README
  records two further limits: plain files in `/` are invisible to the bundled app, and OpenGL apps
  are a known problem on non-NixOS hosts (nixGL suggested). **No upstream bundler uses bwrap**, so a
  sandboxed bundle has to be ours — either a bundler in this flake (`nix bundle --bundler
  .#bundlers.…`, keeping the item's wording) or a plain bundle derivation.
- 2026-10-15: **GPU passthrough into a container of another distribution works** (the user's run):
  `docker run --cap-add=SYS_ADMIN --device /dev/dri -v /run/opengl-driver:/run/opengl-driver:ro
  -v /nix/store:/nix/store:ro … ubuntu:24.04 <jre>/bin/java -cp '<lib>/*' stt.MainKt` printed
  `Device probe: 1 GPU(s) [GpuDevice(index=0, name=AMD Ryzen 9 6900HS Creator Edition (RADV
  REMBRANDT))]` — the host's Vulkan ICDs and libraries reach the loader through
  `/run/opengl-driver`, and the device nodes through `--device /dev/dri`. This is also the recipe
  the bundle's launcher needs (`--dev-bind-try /dev/dri`, `--ro-bind-try` on the ICD directories),
  and it explains arx's `0 GPU(s)`: the bundle had neither.
- 2026-10-15 (workspace, cycle 13 = the network-policy item): red by the user's run — spec 07
  failed at `expect(outside).toContain('refused')` with `Received: "reachable"` (the window could
  reach the machine's own non-loopback address), the other six specs passing. Fix in
  `desktop/src/main.ts` (**ADR 0055**): `webRequest.onBeforeRequest` cancels every request outside
  `file:`/`data:`/`blob:` and `http(s)` to `127.0.0.1`/`[::1]`/`localhost`; both permission
  handlers deny (so a request *and* a `navigator.permissions.query` agree); `setWindowOpenHandler`
  denies popups; a `will-navigate` guard refuses navigation away; spell check off (its dictionaries
  are a fetch). The model download is untouched — it is a main-process `fetch`, not a request of
  that session. The user's re-run passed four of the five assertions; the last one
  (`toHaveURL`) proved unobservable, because Playwright reports an empty URL while a prevented
  navigation is in flight — the spec now asserts what matters (the app is still usable and the
  window is not on the external site) instead of that string.
- 2026-10-15: **GitHub-only model sources, chosen by the user.** Mirror OpenAI's MIT-licensed
  whisper weights in this repository's GitHub Release (versioned tag `desktop-models-v1`, asset
  `ggml-large-v3-turbo-q5_0.bin`; the user uploads the release asset — agents never publish to
  GitHub). Keep CAM++ at its existing `k2-fsa/sherpa-onnx` GitHub Release; the ModelScope source
  card identifies the original `speech_campplus_sv_en_voxceleb_16k` as Apache-2.0 (ADR 0003).
  Both model URLs therefore start at `github.com` and redirect to `release-assets.githubusercontent.com`.
  The allowlist and weekly smoke test approved above cover only those two model pins, not build-time
  tool downloads in `versions.json.downloads`. Before green, the user must upload the whisper asset
  to the stated release/tag; weekly smoke then verifies the real asset and every redirect hop.
- 2026-10-15 (workspace, cycle 13 closed): **green — `7 passed`** by the user's run, the window
  policy item ticked. On the way the spec's navigation assertion needed two fixes, both on the
  test side: a temporary diagnostic spec (spec 99, deleted again) proved the app was already
  correct — after a refused external navigation the frame stays on `/queue` with `loading=false`,
  the renderer alive, the UI rendered (`did-start-navigation` → `will-frame-navigate` → refused →
  `did-stop-loading` back on the app) — while Playwright's locators and `toHaveURL` wait forever
  for a *cancelled* navigation to settle. The spec now polls the main process for `isLoading()` and
  reads the URL there, plus `document.body.innerText` in the renderer. The app-side change that
  made it correct is `will-frame-navigate` (Electron 44's event; `will-navigate`'s extra arguments
  are deprecated) with a `did-fail-load` safety net that reloads the app's URL.
- 2026-10-15: **refactor checkpoint (cycle 13):** one suggestion, optional — `windowMayReach` and
  the window half of the policy now live in `main.ts` next to unrelated lifecycle code (backend
  start, quit wiring, `startApp`). Moving the policy into its own module (e.g.
  `desktop/src/window-policy.ts`, exporting an `applyWindowPolicy(session, contents)` plus the
  pure `windowMayReach`) would put ADR 0055's rules in one place and keep `main.ts` about lifecycle.
  Nothing else looks duplicated.
- 2026-10-15 (cycle 13 refactor, approved): `desktop/src/window-policy.ts` now holds the policy —
  the pure `windowMayReach(url)`, `applySessionPolicy(session)` (request filter + both permission
  handlers) and `guardWindow(contents, appUrl)` (window-open denial, `will-frame-navigate` guard,
  `did-fail-load` self-heal) — and `main.ts` only imports and calls them, so ADR 0055's rules read
  as one unit and `main.ts` stays lifecycle. Behaviour unchanged.
- 2026-10-15: **the whisper mirror is up and verified** (the user created the release; agents never
  publish to GitHub, ADR 0050). Measured from the sandbox: the pin URL
  `https://github.com/lulu-berlin/speech-to-text-app/releases/download/desktop-models-v1/ggml-large-v3-turbo-q5_0.bin`
  answers `302` from `github.com` and `206` from `release-assets.githubusercontent.com` with
  `content-range: bytes 0-0/574041195` — the same size as the pinned upstream file, so the bytes
  are the ones we verify against `versions.json`'s sha256. Switching the pin to this URL is part of
  the allowlist cycle (the flake's `versions-sync` check keeps `versions.json` and `nix/models.nix`
  in step; the store path stays the same, so caches keep hitting).
- 2026-10-15 (workspace, cycle 14 = the model-host allowlist): red by the user's run — spec 08
  failed at the request count, `expected 0, received 1`, proving the app reached the container's
  non-loopback address (`172.17.0.3`, the machine's own LAN IP). Fix: `desktop/src/download-hosts.ts`
  (`github.com`, `*.githubusercontent.com` on a label boundary, loopback) and `models.ts` following
  redirects **by hand** (`redirect: 'manual'`, up to 5 hops) so every hop is checked before the body
  streams; a refused host is named, and the sha256 check is untouched. Verified in the sandbox with
  plain Node against the compiled `models.js`: a loopback pin with a 302 hop downloads and verifies;
  a pin on the sandbox's LAN address is refused with **`requests=0`** and no cached file. Also
  measured first that Node's manual redirect exposes the `302` and its `Location` (against the real
  release URL), since a hidden one would have broken provisioning invisibly. Coverage: the fixture
  server gained an opt-in `redirectFirst`, and spec 03 now serves the whisper file through one
  redirect hop, so the shipped suite exercises the redirect following. One test fix after the first
  green attempt: the assertion pinned the cache *directory*'s absence, but the app creates that
  directory before downloading — the item's wording is "no partial/cache file", so it now asserts an
  empty directory. **Green: `8 passed`** by the user's run.
- 2026-10-15: **refactor checkpoint (cycle 14):** one suggestion, optional — move `openPinned` from
  `models.ts` into `download-hosts.ts`, so that module owns both "which hosts" and "how a pinned
  download is fetched" (as `window-policy.ts` owns the window's rules), leaving the streaming and
  hashing in `models.ts`. Nothing else looks duplicated.
- 2026-10-15 (cycle 14, refactor + pin switch): the approved refactor is in (`download-hosts.ts`
  owns the hosts *and* `openPinnedDownload`, following redirects by hand; `models.ts` keeps the
  streaming, hashing and cache), re-verified with the same two sandbox cases. Then the whisper pin
  moved to this repository's mirror — `versions.json` and `nix/models.nix` both now point at
  `…/releases/download/desktop-models-v1/ggml-large-v3-turbo-q5_0.bin`; the sha256 and size are
  unchanged, so the Nix store path and every existing cache still hit, and `versions-sync` sees
  identical values in both files. The **tiny** fixture model stays on Hugging Face (it never travels
  the app's download path: the specs pass it as a store path or serve it over loopback). ADR 0008
  gained a dated update naming the upstream source for provenance and correcting its old "the app
  never downloads models at runtime" line for the desktop app (ADR 0053/0055); ADR 0055's
  provisioning bullet now describes the mirror rather than a plan, and the ADR index notes it.
- 2026-10-15 (cycle 14, the weekly smoke job): `desktop/check-model-hosts.ts` walks both
  provisioning pins' redirect chains with ranged GETs (no model body), importing the *same* allowlist
  the app uses (`download-hosts.ts`), and fails naming the hop or the non-2xx status. Verified in the
  sandbox: `whisper: ok — 302 github.com/…desktop-models-v1/ggml-large-v3-turbo-q5_0.bin -> 206
  release-assets.githubusercontent.com (bytes 0-0/574041195)`, `speaker: ok` likewise; a pin moved
  to a host outside the list is refused by name, and one that is allowed but served `400` fails too —
  both exit 1. Wired as `pnpm --dir desktop check:model-hosts`, added to `tsconfig.scripts.json`
  (it is a Node-run script per ADR 0054), and CI gained a Monday cron (`0 4 * * 1`,
  Europe/Berlin) plus a `model-hosts` job gated on that exact cron string — deliberately not in the
  required `ci` aggregator, so a weekly network check never gates a pull request. `actionlint`
  clean; **the first scheduled run is the evidence for the schedule wiring**, which only exists on
  `main` after the merge.
- 2026-10-15: **refactor checkpoint (cycle 14, smoke job):** no refactoring suggested — the script is
  linear, its one helper (`modelUrls`) validates the generated `versions.json` it reads, and it shares
  the allowlist rather than copying it.
- 2026-10-15 (bundle, first build attempt + the shebang rule): the bundle is `nix/desktop-bundle.nix`
  (a zstd tar of the closure appended to a launcher; `packages.desktop-bundle`,
  `apps.desktop-bundle` and `bundlers.x86_64-linux.desktop` = the same derivation, so
  `nix bundle --bundler .#desktop …` produces it too). Verified in the sandbox: the Nix parses, the
  loader trick runs bubblewrap (`--library-path … bwrap --version`), the flag set exists in bwrap
  0.8/0.12, and the launcher's self-extraction + exec was simulated end-to-end with a stand-in
  payload (second run skips extraction). **The user caught a real defect on review**: the launcher
  used `pkgs.writeShellScript`, whose shebang is the store's bash — on a machine without Nix the
  bundle would not even start ✗. Fixed (`sed -i '1c #!/usr/bin/env sh'`) and recorded as
  **ADR 0056**: `env` shebangs only for the artefact that must run where Nix is absent (POSIX `sh`
  syntax, currently just this launcher), pinned store interpreters for everything Nix executes, the
  repo's dev scripts as they are, and tool-generated files (`gradlew`) as their tool wrote them.
- 2026-10-15 (bundle fonts + build fix): the user's first `nix build .#desktop-bundle` failed with
  `Permission denied` appending the payload — `cp` kept the launcher's store mode 0555; now
  `install -m 755`. **The user chose bundled fonts over host fonts**: IBM Plex only (not DejaVu,
  my placeholder), limited to the families the language list needs, size measured. Coverage was
  checked per language against the fonts' cmaps (all 100 codes of `docs/api/languages.json`): Plex
  Sans covers the 65 Latin/Cyrillic/Greek languages, plus Arabic, Devanagari, Hebrew, Thai, SC,
  TC (SC lacks 21 common Traditional characters such as 見 門 馬), JP, KR; 15 scripts have no Plex
  font, and Hausa/Lingala miss a few letters (ADR 0053 lists them). Trimmed to TTF in the UI's three
  weights: +32.9 MB bundle / +69.4 MB unpacked (whole families: +179 / +329 MB). The rendered
  `fontsConf` (`nix-instantiate --eval`) was checked with fontconfig 2.18 against the 27 selected
  files: only they are visible, `sans-serif`/`sans`/`system-ui` -> IBM Plex Sans, and each covered
  script resolves to its family. The launcher no longer binds the host's `/usr/share/fonts`.
- 2026-10-15 (bundle, first successful build — the user's run): `nix build .#desktop-bundle` built in
  1m43s; **the bundle is 387 MB** (fonts included). In `alpine:3.20` (no glibc, no fonts) the launcher
  ran under busybox `sh` and started unpacking, then `bwrap: pivot_root: Operation not permitted` —
  Docker's default seccomp profile has no `pivot_root` even with `CAP_SYS_ADMIN` (Chromium's sandbox
  in the `.deb` harness only needs `chroot`, so it never hit this). A test-container restriction,
  not a host one: the next run uses `--security-opt seccomp=unconfined` (plus
  `systempaths=unconfined`, because Docker's masked `/proc` paths would refuse bwrap's `--proc`).
  **The user decided** to keep the 15 scripts without a font for now and file them as a known issue
  (outside item 50: another font or a fallback font later); filed in `docs/known-issues.md` with the
  Japanese-in-SC-design finding.
- 2026-10-15 (bundle in Alpine, the user's run): **the bundle runs on a musl, font-less Alpine 3.20
  with nothing but `zstd`/`tar` added** — unpacked, backend up (`Responding at
  http://127.0.0.1:39535`, migrations applied, `0 GPU(s)`), window shown on the user's desktop
  through XWayland, text in the bundled IBM Plex. Two test-container conditions, neither a host
  one: Docker's seccomp lacks `pivot_root` and masks `/proc` (`--security-opt seccomp=unconfined
  --security-opt systempaths=unconfined`), and Chromium refuses uid 0 without `--no-sandbox` (run as
  a user with the host's uid, like `e2e/desktop/run-in-docker.ts`). Seen in the run: (1) the UI drew
  at 1x on the user's HiDPI screen — an X11 client under XWayland gets no output scale; the run had
  only `DISPLAY`, so Electron could not choose Wayland; (2) GTK's file chooser found no icon theme
  (`Could not find the icon 'user-home-symbolic-ltr'. The 'hicolor' theme was not found`): the
  namespace has no `/usr`, so the host's themes are invisible on every host, not only in Alpine.
  GPU-process (no GL in the container) and D-Bus errors are expected there.
- 2026-10-15 (bundle in Alpine over Wayland, the user's run): **worked** — window at the screen's
  scale (the user's report; the pasted log was the earlier XWayland run). Electron 44's default
  `--ozone-platform-hint=auto` picks Wayland only when `XDG_SESSION_TYPE=wayland` (Chromium
  `ui/linux/display_server_utils.cc`, commit `fdc28de`; `WAYLAND_DISPLAY` alone counts only for an
  explicit `wayland` hint), so the first Wayland attempt — without that variable — fell back to X11
  and exited `Missing X server or $DISPLAY`. A real Wayland session sets it and the launcher passes
  the environment through, so the bundle needs no platform switch; the Wayland socket is reached
  through the read-only `/run` bind.
- 2026-10-15 (bundle icon themes): the user saw the placeholder icons in the file chooser and chose
  to bundle GTK's icon themes, like the fonts: Adwaita + hicolor in the closure, the launcher sets
  `XDG_DATA_DIRS` to their `share` directories (Electron's `wrapGAppsHook3` wrapper prefixes its
  schemas onto it). +0.56 MB bundle / +13 MB unpacked, measured on adwaita-icon-theme 50.0 — the
  pinned nixpkgs' release. Verified in the sandbox: `alejandra`, the evaluated search path, and that
  both `share/` trees hold `icons/Adwaita` and `icons/hicolor`; the dialog itself awaits the user's
  run.
- 2026-10-15 (bundle icon themes verified, runtime-dir fix): the user's run (Wayland, Alpine) shows the
  file chooser with proper icons and no `hicolor` warning. It also flooded the log with `dconf-CRITICAL
  … unable to create directory '/run/user/1000/dconf': Read-only file system` — **a launcher defect on
  every host**: `$XDG_RUNTIME_DIR` sits under the read-only `/run` bind, while on a real host it is the
  user's writable runtime directory (dconf, the session bus, the Wayland socket). The launcher now
  binds it writable on top of `/run` when it is set and exists (checked under `dash -eu` for unset,
  existing and missing values). The remaining noise in the container is expected there: no session
  or system bus (`dconf … transport "disabled"`, D-Bus errors) and no `/dev/dri` (DRM/EGL errors,
  CPU rendering).
- 2026-10-15 (runtime-dir fix verified, the user's run): with `/run/user/<uid>` owned by the app user
  in the container (as on a real host), the `dconf-CRITICAL … Read-only file system` lines are gone;
  only the container's expected noise remains (no session/system bus, no `/dev/dri`).
- 2026-10-15 (bundle e2e, first red run — the user's): the suite hung in spec 01. A process snapshot
  showed the bundle unpacked in ~3 s (1.2 GB per fresh HOME; the predicted 30 s launch timeout did
  not happen) and the app fully up — Electron, renderer, the JVM — but no `whisper-cli`/`ffmpeg`
  after ~3 minutes: the specs pass the fixture models as `/nix/store/…` paths, and inside the bundle
  `/nix` is the bundle's own unpacked store, so those files do not exist for the app and the task
  cannot run (spec 01 waits its 5 minutes for "jfk.wav transcribed"). Test-side fix: in bundle mode,
  root copies the two models to `/tmp/stt-fixtures/` (bound into the namespace) under their store
  basenames and points the specs there (checked under busybox `sh`: the copies and the exported
  paths reach a child shell). **Product finding raised with the user**: the bundle's app sees only
  `$HOME`, `/tmp`, `/etc` and `/run` (read-only) of the host, so a user file anywhere else — a model
  set with `STT_WHISPER_MODEL`, an `STT_DATA_DIR`, an upload from `/mnt` or `/media` — is invisible
  to it.
- 2026-10-15 (bundle e2e green — the user's run): `pnpm --dir e2e test:desktop:bundle` in
  `alpine:3.20` (musl, busybox, only `zstd`/`tar` added) against
  `/nix/store/9cld61blr1va7jg50dr3in7yqj7ilhsx-stt-app-bundle`: **7 passed, 1 skipped (1.0m)** — 05
  skipped by design (the bundle's own `/nix` hides the host store, ADR 0053). Item ticked. The
  per-launch unpack (fresh HOME per spec) costs ~3 s and 1.2 GB each; no shared-cache knob was
  needed.
- 2026-10-15 (bundle visibility, the user's decision): the bundle's app sees the **whole machine**
  with the user's own permissions, except `/nix` (its own store, read-only) — instead of only
  `$HOME`, `/tmp`, `/etc`, `/run`. The user first asked for everything read-only except the
  downloads folder, then **withdrew** it: the app must write its data, model cache and Electron
  profile anyway, and a read-only machine would break saving a transcript wherever the user picks
  in the Save dialog — too much UX for a guarantee the bundle does not need. The Save dialog stays.
  Discovered test item added; spec `09-bundle-filesystem.spec.ts` (bundle only) reads the backend's
  namespace from `/proc`: host `/usr` visible, `/` writable, `/nix` read-only and the container's
  store hidden. Refactor approved and done first: `nixBuild(target)` in the harness.
- 2026-10-15 (bundle visibility green — the user's run): red first (spec 09 failed at
  `existsSync(<root>/usr/bin)`: `Expected: true, Received: false`, 01–08 unchanged), then the
  launcher binds every top-level entry of `/` except `/dev`, `/nix`, `/proc` onto bubblewrap's
  scratch root, `--dev-bind /dev /dev`, a fresh `/proc`, and the bundle's store `--ro-bind` at
  `/nix` (binding `/` itself would make bubblewrap `mkdir /nix` on a non-Nix host's real root and
  fail — the container has a `/nix`, so the suite would not have caught it). The runtime-directory
  and GPU/Vulkan special cases are gone with it. While going green, spec 09's `/` writable
  assertion was replaced by `/usr` writable: the namespace's `/` is bubblewrap's scratch tmpfs,
  writable before and after, so it could not tell the two apart. Run against
  `/nix/store/asncsr1i1zwl2vz7dl60ppyfmhhldmll-stt-app-bundle`: **8 passed, 1 skipped (59.8s)**.
  Item ticked. Refactor checkpoint: no refactoring suggested — the launcher's binds are one loop,
  and spec 09's mount-table read has a single use.
- 2026-10-15 (bundle on the user's NixOS desktop, run from the devenv shell): the app started and
  worked, but (1) every transcription failed with `whisper_init_from_file_with_params_no_state:
  failed to open '/nix/store/…-ggml-large-v3-turbo-q5_0.bin'` — the devenv shell exports
  `STT_WHISPER_MODEL`/`STT_SPEAKER_MODEL` as host store paths and `STT_DATA_DIR` as the repo's dev
  state, and the bundle hides the host's `/nix` by design (the "GPU 1" without description is a
  device saved in that dev database); (2) **no GPU**: `Device probe: 0 GPU(s)` and Chromium's
  `MESA-LOADER: failed to open dri: /run/opengl-driver/lib/gbm/dri_gbm.so` — on NixOS the drivers
  under `/run/opengl-driver` are links into the host store, hidden too. (3) Found while answering,
  a launcher defect on **every** host: nixpkgs' Vulkan loader (`-DSYSCONFDIR=/run/opengl-driver/share`)
  finds a distribution's drivers through `$XDG_DATA_DIRS/vulkan/icd.d` (default
  `/usr/local/share:/usr/share`), and the launcher's `XDG_DATA_DIRS` (icon themes only) removes
  `/usr/share` from that search — whisper-cli could not find a GPU on Ubuntu or Fedora either.
  Chromium's own GL (nixpkgs Mesa's GBM looks only under `/run/opengl-driver`) renders the window on
  the CPU on non-NixOS hosts; the UI does not need a GPU.
- 2026-10-15 (bundle GPU, the user's decisions): the rerun outside the devenv variables
  (`env -u STT_WHISPER_MODEL -u STT_SPEAKER_MODEL -u STT_DATA_DIR "$bundle"`) worked, CPU only. The
  agreed `XDG_DATA_DIRS` fix was found insufficient before it was written: nixpkgs' glibc ignores
  `/etc/ld.so.cache` and searches only its own lib dirs (`ld.so --list-diagnostics`:
  `path.system_dirs` = glibc and libgcc), so a distribution's Vulkan driver could be found but not
  loaded. Options weighed: stock nixpkgs Mesa (closure 1.07 GiB unpacked — LLVM 550 MiB, mesa
  274 MiB, python3 137 MiB; GPU for transcription *and* the window), a Vulkan-only Mesa, or no GPU.
  **The user chose the Vulkan-only Mesa** (transcription only; no OpenGL, no LLVM), with **AMD
  (RADV), NVIDIA and Intel (ANV)**. NVIDIA, settled in conversation: NVK ships for `nouveau`
  machines, and on machines running NVIDIA's own kernel driver the launcher **borrows the host's
  NVIDIA Vulkan library** (it must match the kernel module's version), with the X11/libglvnd
  libraries it links bundled and on the library path only when `/dev/nvidiactl` exists — item 19's
  verified recipe. **NixOS gets no GPU in the bundle** (the user: no need to bundle under NixOS;
  `nix run .#desktop` uses both of the user's GPUs, confirmed again). The Mesa build is not in
  `cache.nixos.org`: filed as a known issue (the user's plan: publish uncached outputs as GitHub
  release assets, outside item 50). The user's laptop: Radeon 680M (`amdgpu`) and RTX 3050 Mobile
  (`nvidia`), so the AMD and the borrowed-NVIDIA paths are testable there; NVK and ANV are not.
  Also done: the download screen says "Preparing the speech analysis models…" (the user's wording).
- 2026-10-15 (bundle AMD GPU green — the user's runs): red first (`pnpm --dir e2e
  test:desktop:bundle:amd`: spec 10 `no RADV device among the probe's GPUs: {}`, the rest green in
  Ubuntu too). Then `vulkanMesa` in `nix/desktop-bundle.nix` (stock nixpkgs Mesa 26.2.4 overridden:
  no Gallium drivers, Vulkan drivers amd/intel/nouveau, no layers; glvnd, gbm, teflon, rusticl,
  OpenGL, GLES, EGL, GLX off; `amd-use-llvm=false`; `tools` dropped) and the launcher's
  `VK_ADD_DRIVER_FILES=<vulkanMesa>/share/vulkan/icd.d`. Three builds to get there: (1) nixpkgs'
  Meson hook forces `auto_features=enabled` — "Feature gallium-va cannot be disabled", fixed with
  `mesonAutoFeatures = "auto"`; (2) compiled (~6 min) but "failed to produce output path for output
  'spirv2dxil'" — the override declares only `out` and `cross_tools`; (3) caught before building: the
  no-LLVM `disallowedRequisites` applied to every output, `cross_tools`' mesa_clc links LLVM — it is
  now `outputChecks.out` only. Result: `intel_icd`, `nouveau_icd`, `radeon_icd` manifests; the Mesa
  closure **158.8 MiB** (`nix path-info -S`; stock Mesa 1.07 GiB) with no LLVM (the build enforces
  it); **the bundle 401 MB** (388 MB before, so +13 MB — much of the closure is shared with
  Electron's). The suite in the GPU container: **9 passed, 1 skipped (1.2m)**, spec 10 green — the
  probe found the Radeon 680M through the bundled RADV, and the transcription on it finished with
  no CPU fallback. Item ticked. Refactor checkpoint: no refactoring suggested now — the NVIDIA spec
  will repeat spec 10's "choose the GPU and transcribe" steps; a shared helper is decided when it
  exists, not before.
- 2026-10-15 (bundle NVIDIA GPU green — the user's runs): red first (`pnpm --dir e2e
  test:desktop:bundle:nvidia`: the harness reads the host's driver version from
  `/proc/driver/nvidia/version`, downloads NVIDIA's own 595.71.05 installer once into
  `~/.cache/stt-e2e/nvidia/` and installs its libraries and manifests where Ubuntu's driver
  packages put them; spec 11 `no NVIDIA device among the probe's GPUs: {"gpu:0": "… (RADV
  REMBRANDT)"}`). On the way the harness assumed an `nvidia_icd.json.template`; the 595.71.05
  installer ships a finished `nvidia_icd.json` naming `libGLX_nvidia.so.0` (checked by unpacking
  it in the sandbox). The launcher then borrows the driver when `/dev/nvidiactl` exists and the
  manifest and `libGLX_nvidia.so.0` are in the distribution paths: a private directory in the
  bundle cache links only NVIDIA's libraries, on `LD_LIBRARY_PATH` ahead of the bundle's copies
  of what they link (`nix/nvidia-driver-libs.nix`, item 19's list), and the host manifest joins
  `VK_ADD_DRIVER_FILES` (block checked under `dash`/busybox against a fake root). Still red; the
  user's diagnostic (the bundle's whisper-cli in the same container with `VK_LOADER_DEBUG`) showed
  the library loading and `Could not get 'vkCreateInstance' via 'vk_icdGetInstanceProcAddr' for ICD
  libGLX_nvidia.so.0` — NVIDIA's documented need for its EGL part (container-toolkit issues #191,
  #1472). Fix: the launcher links `libEGL_nvidia` too (the bundle's libglvnd finds it through the
  host's `/usr/share/glvnd/egl_vendor.d`, a default search directory), and the test container
  installs the installer's `10_nvidia.json` there, as Ubuntu's package does (item 19 worked
  because NixOS's `/run/opengl-driver` brought that manifest). Green: **9 passed, 2 skipped
  (2.0m)** — spec 11 found the RTX 3050 through the borrowed driver and transcribed on it with no
  CPU fallback. Item ticked. Observation, not a defect: specs 01 and 03 (device `auto`) ran about
  twice as long as in the AMD container (23.1 vs 9.5 s, 26.6 vs 14.0 s) — plausibly the laptop's
  dGPU waking, unmeasured.
- 2026-10-15 (cycle 15 checkpoint, the user's go): two approved refactors — `flake.nix`'s Docker
  output imports `nix/nvidia-driver-libs.nix` instead of its own copy (proof: the library-path
  string from the old inline list and from the file evaluate identical, so the entrypoint and its
  store path are unchanged), and specs 10/11 share `transcribeOnGpu(window, /RADV|NVIDIA/)` in
  `helpers/app.ts` (types and spec loading checked; the GPU runs re-verify it). `flake.nix` was
  already not alejandra-clean before (five spots, `{ }` spacing and the one-line bundle import);
  left as is — the formatting gate covers Kotlin and TypeScript. CI: a `desktop-bundle-e2e` job
  (`test:desktop:bundle` on Alpine, Nix toolchain + devenv), required by `ci`; it frees the
  runner's unused toolchains (~10 GB of unpacked bundles per run) and lifts Ubuntu 24.04's AppArmor
  user-namespace restriction when present — the bundle's documented host requirement; actionlint
  clean, first run on GitHub. The GPU suites stay manual (no runner has a GPU). **README**: the
  cycle-6 `.deb` section was not in the history any more, so the "Desktop app" section was written
  for all three forms — a table (form, distribution, build command, host needs), the shared
  behavior (first-start download from GitHub only with Retry, the shared data directory,
  loopback-only, the window without network, quitting stops everything), how it relates to the
  Nix package and Docker (one user's window app vs a server at :8080), one subsection per form
  (the `.deb` build commands and glibc 2.38 floor; `nix run .#desktop`; the bundle's file, unpack
  cache, whole-machine view except `/nix`, host requirements incl. Ubuntu 24.04's restriction,
  fonts, and the GPU table — AMD and NVIDIA tested, NVK and ANV not), and the e2e commands; plus
  `STT_HOST` in Configuration, the desktop row in "Where your data lives", and the licenses (the
  whisper mirror; the bundle's IBM Plex and Mesa). `check-links`, `check-architecture-names` and
  `check-architecture-diagram` pass. Item ticked.
- 2026-10-15 (reruns after the refactors, the user's runs at `1aeb214b`): bundle on Alpine **8
  passed, 3 skipped**; bundle + AMD **9 passed, 2 skipped** (spec 10 green through
  `transcribeOnGpu`); bundle + NVIDIA **9 passed, 2 skipped** (spec 11 green through it); one more
  suite with spec 05 passing and 09–11 skipped, **8 passed, 3 skipped (47.2s)** — the Nix package
  or the `.deb`, the paste does not say which. `nix build .` and `nix flake check` succeeded, and
  `nix run .` started (`Device probe: 2 GPU(s)` — RADV REMBRANDT and the RTX 3050; `Responding at
  http://0.0.0.0:8080`). The must-not-break item waits for the other of the two desktop suites.
- 2026-10-15 (must-not-break green — the user's runs at `12afe7b0`): `test:desktop:nix` **8 passed, 3
  skipped (47.2s)** and the `.deb` rebuilt from this branch (`pnpm --dir desktop dist`, the new
  download-screen wording) **8 passed, 3 skipped (47.5s)** in the stock Ubuntu container — specs
  01–08 incl. 05 (the Nix store cache), 09–11 bundle-only. With `nix build .`, `nix flake check` and
  `nix run .` above, the item is ticked on local evidence; the CI legs (`desktop-e2e` on the plain
  runner, `desktop-nix-e2e`, `desktop-bundle-e2e`) first run when the user pushes. The `.deb`
  build's two electron-builder warnings — the default Electron icon and no `desktopName` — are
  filed in `docs/known-issues.md` (LOW); neither Nix output has a `.desktop` entry either.
- 2026-10-15 (the `.deb` GPU question, the user's decision): an automated test for the `.deb` on a
  distribution's GPU driver would add little — the `.deb` declares `libvulkan1` and uses the
  system's Vulkan driver like any installed app, and no CI runner has a GPU — so it is a one-time
  manual check, recorded here, not a test item and not a CI job.
- 2026-10-15 (the `.deb` GPU manual check — the user's run): the `.deb` built from this branch,
  installed with apt in a stock `ubuntu:24.04` container together with Ubuntu's own
  `mesa-vulkan-drivers` and the host's `/dev/dri`; its `whisper-cli` (run as the app runs it, with
  `resources/tools/bin` on `LD_LIBRARY_PATH`) printed `ggml_vulkan: 0 = AMD Ryzen 9 6900HS Creator
  Edition (RADV REMBRANDT) (radv)`, `whisper_backend_init_gpu: using Vulkan0 backend` and the JFK
  transcript (`And so my fellow Americans ask not what your country can`). Ticked. **Every line of
  the test list is now checked; Status: in-review.** Still outstanding outside the list: the first
  GitHub runs of `desktop-e2e`, `desktop-nix-e2e` and `desktop-bundle-e2e` (when the user pushes).
- 2026-10-15 (workspace, the user's request): rebased onto `main` `f23b1330` (item 47, the
  atomic-transitions fix, #7) — 169 commits replayed. Two additive conflicts: `roadmap/README.md`
  (main marks 47 done with its spec link; this branch adds 50 to 48's and 49's dependencies — both
  kept) and `docs/known-issues.md` (main's new `check-links` row and this branch's bundle-font rows
  — both kept). After it: no conflict markers; e2e and desktop prettier + type checks (main changed
  `e2e/tests/helpers/ui.ts`, which the desktop specs import), the docs checks (now including
  `known-issues.md`, whose links main fixed) and actionlint all clean.
- 2026-10-15 (review cleanup: `HANDOFF.md` folded in and removed, so the squash brings no
  session notes to `main`). What the handoff held that this log did not — the defects the user's
  first packaged runs on 2026-10-14 surfaced, each fixed then:
  - the harness's `docker run` argv lacked the `-v` before `/nix/store:/nix/store:ro` (docker read
    it as the image: `invalid reference format`, exit 125) and the approved `--cap-add=SYS_ADMIN`;
  - in a packed app `app.getAppPath()` is `resources/app.asar`, so its directory is the resources
    directory (an extra `..` gave "no bundled JRE at /opt/Speech-to-Text/jre"); java expands the
    `-cp` wildcard against the cwd, so the classpath is the absolute `<backend/lib>/*`
    (`ClassNotFoundException: stt.MainKt` otherwise); and `const process = spawn(…)` shadowed the
    global `process`, so `process.env` threw on every launch (now `child`);
  - on Ubuntu 24.04 `libasound2` is a virtual package provided only by the `liboss4-salsa-asound2`
    shim, whose `libasound.so.2` lacks `snd_device_name_get_hint` (Electron died with a symbol
    lookup error, exit 127): the `.deb` depends on `libasound2t64 | libasound2`;
  - the bytedeco jar is a zip, which keeps no file modes, so the staged `ffmpeg`/`ffprobe` were
    `644` and every task would have failed with `EACCES`: staging marks both `755`;
  - the frontend redirects `/` to `/queue`, so the journey's landing assertion pins
    `http://127.0.0.1:<port>/queue` (the app was right, the test was not).
- 2026-10-15 (main-tree review, changes requested): Status back to in-progress. Two cleanups done
  without a cycle (the handoff folded in and removed; the unread `temurinJdk` table deleted and
  stale records corrected). Four findings became `(discovered, review 2026-10-15)` items; the user
  confirmed their wording, the order (backend left running after Retry → second quit during
  shutdown → exact model hosts → glibc floor) and the triggers:
  - both lifecycle items simulate a hung backend by pausing the JVM (`SIGSTOP`) the moment it
    appears — a backend that answers health, or exits on SIGTERM, leaves no orphan even with the
    defects. For the Retry item the user chose this over a test-only start-timeout setting (no
    app-code hook), accepting the app's 120 s start timeout in the spec. The second-quit spec
    sends the main process two real SIGTERMs a second apart; the bundle cannot show its red (its
    PID namespace dies with Electron and takes the JVM along), so that red comes from the `.deb`
    or `:nix` run;
  - the host item maps `raw.githubusercontent.com` to loopback (the harness's `--add-host`, an
    `/etc/hosts` line in the `.deb` CI job) so a loopback fixture's redirect to it is a counted
    request, like spec 08; the spec fails, not skips, when the name does not resolve to loopback;
  - the glibc item is a harness mode that runs `apt-get install` of the `.deb` in `ubuntu:22.04`
    and expects apt to refuse it naming `libc6`, also a step of `desktop-e2e`; the 24.04 half is
    the existing `.deb` run.
- 2026-10-15 (review item "backend left running after Retry", red — the user's `test:desktop:nix`
  at `081337cf`): spec `12-retry-after-hung-backend.spec.ts` failed after 2.2 min at "the backend
  that never answered must be gone once the error shows": expected `[]`, received
  `3247: …/stt-desktop-0.1.0/share/stt-app/resources/jre/bin/java -cp …/backend/lib/* stt.MainKt`
  — the paused first JVM still running when Retry appeared. The rest unchanged: 8 passed, 3
  skipped. Fix: `startApp`'s error path awaits the spawned backend's stop (SIGTERM, SIGKILL after
  10 s) and forgets its handle before it reports the error.
- 2026-10-15 (review item "backend left running after Retry", green — the user's
  `test:desktop:nix` at `22da8b49`): **9 passed, 3 skipped (3.0m)**; spec 12 passed in 2.2 min
  (the error appears after the 120 s start timeout and the 10 s forced stop; Retry reaches Live with
  exactly one backend; quitting leaves none). Ticked.
- 2026-10-15 (refactor checkpoint after the Retry backend item, the user's decisions): refactor
  approved and done — the survivor polling of specs 02 and 12 (`survivors(pattern, timeoutMs)`,
  `delay`) moved into `helpers/app.ts`. The discovered scenario "after a failed start, the window
  is never sent back to the stopped backend's address" (the app keeps the stopped backend's URL
  when the page load fails, and the window guard's safety net may reload it) is added to the test
  list. Next: the second quit request during shutdown.
- 2026-10-15 (review item "second quit during shutdown", red — the user's `test:desktop:nix` at
  `58ce0b5e`): spec `13-second-quit-during-shutdown.spec.ts` failed after 8.8 s at "nothing the
  app owns outlives it": expected `[]`, received
  `3522: …/stt-desktop-0.1.0/share/stt-app/resources/jre/bin/java -cp …/backend/lib/* stt.MainKt`
  — the second SIGTERM let the app exit, and the paused JVM's SIGKILL timer died with it. The
  rest: 9 passed (spec 12 included, after the survivor-polling refactor), 3 skipped. Fix:
  `quitWhenStopped` tracks the backend as running → stopping → stopped; only the quit issued once
  the stop has finished takes the default path, and a quit request mid-stop is held back and
  starts no second stop.
- 2026-10-15 (review item "second quit during shutdown", green — the user's `test:desktop:nix` at
  `a9d9062e`): **10 passed, 3 skipped (3.2m)**; spec 13 passed in 12.8 s (the app held the second
  SIGTERM back, waited for the paused JVM's SIGKILL, and nothing it owns was left). Ticked.
- 2026-10-15 (review item "exact model hosts", red — the user's `test:desktop:nix` at `3697fea1`):
  spec `14-model-host-exact.spec.ts` failed at "the redirect to raw.githubusercontent.com must be
  refused before a request": expected `0`, received `1` — the `githubusercontent.com` suffix rule
  let the hop through. The rest: 10 passed (12 and 13 after the pattern refactor `c4595946`), 3
  skipped. Fix: `download-hosts.ts` allows exactly `github.com`,
  `release-assets.githubusercontent.com` and loopback; the suffix rule is gone, so the weekly
  `check-model-hosts` job (same function) fails on a move to `raw.`/`objects.` too. ADR 0055
  unchanged (it already names exactly these hosts). Sandbox checks: the rule refuses `raw.`,
  `objects.`, the bare `githubusercontent.com` and `evil-githubusercontent.com`; `node
  check-model-hosts.ts` against the live pins: both `302 github.com → 206
  release-assets.githubusercontent.com`, exit 0.
- 2026-10-15 (review item "exact model hosts", green — the user's `test:desktop:nix` at
  `5058f4c5`): **11 passed, 3 skipped (3.3m)**; spec 14 passed in 0.5 s (the loopback hop
  followed, the redirect to `raw.githubusercontent.com` refused before a request and named, no
  cache file). Ticked.
- 2026-10-15 (refactor checkpoint after the exact-host item, the user's decision): specs 08 and 14
  write their pins with `writeFixturePins` (`54b02026`).
- 2026-10-15 (review item "glibc floor", red — the user's run of the new `pnpm --dir e2e
  test:desktop:deb-floor` at `a6d62dd6`, against a freshly built `.deb`): `apt installed
  stt-app_0.1.0_amd64.deb on ubuntu:22.04, below the glibc floor` — `Setting up stt-app (0.1.0)`,
  `Processing triggers for libc-bin (2.35-0ubuntu3.15)`, exit 1. Fix: `deb.depends` in
  `desktop/electron-builder.yml` gains `libc6 (>= 2.38)` (electron-builder hands each entry to fpm
  verbatim; js-yaml parses it as that string).
- 2026-10-15 (review item "glibc floor", green — the user's runs at `c65c3bf8` with the `.deb`
  rebuilt by `pnpm --dir desktop dist`): `test:desktop:deb-floor` — `stt-app : Depends: libc6 (>=
  2.38) but 2.35-0ubuntu3.15 is to be installed`, `run-in-docker: apt refused
  stt-app_0.1.0_amd64.deb on ubuntu:22.04, naming libc6`; `test:desktop` (the same `.deb` on
  ubuntu:24.04, libc-bin 2.39) **11 passed, 3 skipped (3.2m)**, specs 12–14 included — the `.deb`
  half of the must-not-break run with every review change in. Ticked.
- 2026-10-15 (the discovered stale-address item, the user's decision): no black-box trigger exists
  — the page load fails only if the backend dies between its `/api/health` answer and the load,
  which the app starts at once (a paused JVM makes the load hang, not fail), and the stale URL is
  used only after a later failed main-frame load on the download screen, which navigates nowhere.
  Of a test-only hook, filing it, or dropping it, the user chose to file it: removed from the test
  list and added to `docs/known-issues.md` (LOW), not fixed in item 50.
- 2026-10-15 (workspace, the user's request): rebased onto `main` `5f7223ef` (item 44, the audio
  in the database, #8) — 190 commits replayed; one conflict, the ADR index (both sides' rows kept).
  **ADR numbers collided**: item 44 took 0051 and 0052 on `main`, so this item's ADRs are
  renumbered — 0051 → **0053** (Linux desktop forms), 0052 → **0054** (no hand-written
  JavaScript), 0053 → **0055** (the window's network policy), 0054 → **0056** (launcher shebang);
  every reference in this item's files, ADR 0008 and roadmap 54 follows, while item 44's references
  to its own 0051/0052 (backend, audio specs, `docs/architecture.md`, README's upgrade note, ADRs
  0024/0045) are untouched — every rewritten line audited. Earlier entries of this log now read in
  the new numbers. Sandbox checks after it: no conflict markers; links (README, architecture,
  known-issues, both indexes, the four ADRs, ADR 0008, roadmaps 50/54), the architecture name and
  diagram checks, e2e and desktop prettier + type checks, Nix parsing of `flake.nix` and
  `nix/desktop*.nix`/`electron.nix`, actionlint — all clean. Status: in-review, at the user's
  call. Item 44 changes the backend all three packages bundle, so the packaged runs on this tree
  (`.deb`, `:nix`, `:bundle`), `nix build .` and `nix flake check` are the user's to run and log.
- 2026-10-15 (must-not-break on the rebased tree — the user's runs at `b06837c3`, item 44's backend
  inside every package): the `.deb` rebuilt (`pnpm --dir desktop dist`); `test:desktop:deb-floor`
  — apt refused it on ubuntu:22.04 (`Depends: libc6 (>= 2.38) but 2.35-0ubuntu3.15 is to be
  installed`); `test:desktop` on ubuntu:24.04 **11 passed, 3 skipped (3.2m)**; `test:desktop:nix`
  **11 passed, 3 skipped (3.2m)**; `test:desktop:bundle` on alpine:3.20 **11 passed, 3 skipped
  (3.8m)** — 09 ran, 05 skipped by design (the bundle's `/nix` hides the host store), 10/11 need a
  GPU; `nix build .` and `nix flake check` succeeded (so `versions-sync` holds without the removed
  `temurinJdk` table). Every review item done; the first GitHub runs of `desktop-e2e`,
  `desktop-nix-e2e` and `desktop-bundle-e2e` come with the user's push.
- 2026-10-15: main-tree re-review approved by the user; squashed to one commit on `main` `5f7223ef`
  (ADR 0050) with the spec moved here from `roadmap/50-electron-app.md`, `Status: done`, and the
  roadmap index and items 48, 49 and 54 relinked. The ADRs are 0053–0056 (item 44 landed 0051–0052
  first). Outstanding on the PR: the first GitHub runs of `desktop-e2e`, `desktop-nix-e2e` and
  `desktop-bundle-e2e`; whether `desktop-bundle-e2e` (Mesa built from source) stays required is the
  user's call once its duration is known.
- 2026-10-16 (the PR's first GitHub runs — run 38825990732 on PR #9, head `81b49b09`): the `.deb`
  job **passed in 6m12s**, running the specs on the runner itself, so the specs and the packaged
  app are fine on a clean machine. `desktop-nix-e2e` **failed in 24m53s** and
  `desktop-bundle-e2e` **failed in 11m55s**; every other job passed, `CI (all checks)` with them.
  Both failures were diagnosed and are recorded here:
  - `desktop-nix-e2e` — a bug in this item's harness, `e2e/desktop/run-in-docker.ts`: the container
    exited 6 with no output, 1.4 s after the image pull. The removal of the image's own user was
    guarded on a command substitution's status, which is its pipeline's last command (`cut`,
    always 0), so on a host whose uid the image does not have — the runner's 1001 — the script ran
    `userdel -r ''`, which exits 6; under `set -eu` the container stopped before anything printed.
    Locally it was invisible only because the local uid is 1000, which `ubuntu:24.04` has (as
    `ubuntu`). Fixed: whether to remove that user is decided by the name, and that removal's stderr
    is kept, so a removal that really fails can never be silent again. Verified here by running
    that script text — extracted from the file and from `git show HEAD:` — under dash with stub
    `getent`/`userdel`/`groupadd`/`useradd`/`setpriv` and `HOST_UID=1001`: before, exit 6 and no
    output; after, the group and the user are created and the exec is reached. The container itself
    needs Docker, so its real proof is the next run.
  - `desktop-bundle-e2e` — not this item's code: `nix build .#desktop-bundle` builds the backend
    with its own `gradle check` (`flake.nix:122`, `doCheck = true`, deliberate — nixpkgs' gradle
    hook would otherwise run only `test`), and the still-racy item-47 pin failed inside it: the
    same spec that reddened `main`'s `nix build` in #38650675971, now at a different line
    (`ProcessingEngineAtomicTransitionsSpec.kt:277` — the wait for the second writer-lock call
    within its 10 s probe; 1 of 280 tests). Filed in `docs/known-issues.md` (MED); it needs a
    follow-up roadmap item, 47 being merged.
  The two container jobs were the only paths CI had never run.
- 2026-10-16 (the user's decision after the runs above): `desktop-bundle-e2e` stays in the workflow
  and runs on every push, but leaves the `CI (all checks)` aggregator's `needs` — it must not gate
  merges while the pin it builds can flake (roadmap 55, drafted the same day: the item exists only
  as a draft in the item-50 workspace; the main tree does not carry it yet) and until the
  Vulkan-only Mesa it compiles from source comes from a binary cache (the `docs/known-issues.md`
  row with that decided approach — still not a roadmap item, unlike 53, which caches the store
  restore rather than the uncached output). Branch protection needs nothing: it requires the single
both conditions are recorded in the workflow itself (the job's comment and the
  aggregator's), and adding the job back to `needs` is the one-line change that re-requires it, once
  55 is fixed and the run's duration is known.
- 2026-10-17: the user approved the combined 50+57 diff; PR #9 is prepared as a single squashed
  commit carrying this item's and item 57's finished states (ADR 0050 / AGENTS.md step 8). The
  desktop CI's three jobs and all other gates were green on the branch head (`d2facb59`,
  run `38985043592` attempt 2); item 57's spec records the first attempt's one flake and its
  follow-up (item 58).