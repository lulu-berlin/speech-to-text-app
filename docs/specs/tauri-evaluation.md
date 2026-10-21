# 64 — Evaluate Tauri as the desktop shell

Status: done (roadmap item 64, approved 2026-10-20)
Depends on: [50](electron-desktop-app.md) (done; the Electron desktop app, its three
artifacts and their e2e paths)
Blocks: —

## Goal

A measured go/no-go on replacing Electron with Tauri 2 as the desktop shell on Linux, across all
three artifacts (the `.deb`, the flake package and the self-contained bundle), with a working
prototype of the existing UI and backend and a packaged e2e path. The outcome is an ADR with the
numbers; a migration, if chosen, becomes its own roadmap item.

## Evidence

- The `.deb`'s payload is 628 MB unpacked — Electron ~281 MB, tools 149 MB, backend jars 102 MB,
  jlink JRE 95 MB, UI 0.6 MB — and 233 MB as `tar | xz -6`
  (`docs/specs/electron-desktop-app.md:138-139`). The UI is 0.1% of it; Electron is 45%.
- The `.deb` leaves glibc, GTK3, NSS, ALSA/Pulse, X11/xcb, libgbm/libdrm, libstdc++ and the Vulkan
  loader to apt; the self-contained `nix bundle` carries them, and its closure is ~1598.5 MiB
  (`docs/specs/electron-desktop-app.md:140-142`, `:182`).
- ADR 0053's invariant is one pinned upstream Electron 44.7.1 in every build (`.deb`, flake package,
  bundle); the e2e jobs for the three artifacts drive it with Playwright.
- ADR 0055: the app window has no network path — it loads the backend over loopback, the main process
  fetches the pinned models, and no request leaves the machine. A shell change must preserve it.
- Tauri 2 on Linux renders with `webkit2gtk-4.1` (WebKitGTK): the `.deb` would depend on it instead of
  shipping Chromium, while the flake package and the bundle would have to carry its closure to stay
  self-contained. WebKitGTK's version and feature set differ per distribution, unlike one pinned
  Chromium.
- Frontend (this consultation, 2026-10-18): the UI is 0.6 MB of the payload and its built JS is 581.5
  kB raw / 186.6 kB gzip; Astro/Svelte or SSR would buy ~0.1 MB and add a Node server to the `.deb`,
  the Docker image and the bundle, while invalidating 173 frontend tests, 22 e2e specs and ADR 0033's
  design system. **The frontend stays React**; no item is filed for a rewrite.

## Decided (consultation, 2026-10-18)

- **Evaluate before migrating** (the user): this item is a spike with measurements and a go/no-go,
  not a migration; a chosen migration becomes its own item.
- **Frontend stays React** (the user): no rewrite and no SSR; the measured gain is negligible against
  the artifact sizes and the rewrite's cost.
- **The evaluation must keep all three artifacts and a packaged e2e** (the user): the `.deb` in a
  stock `ubuntu:24.04` container, the flake package, the self-contained bundle on a machine without
  Nix and without host WebKitGTK, each with its packaged e2e; a result that cannot keep one of them
  is reported as such and weighs against migrating.
- **Sequencing** (the user): the desktop spike is independent of the deferred frontend question; the
  frontend is revisited only after the Tauri numbers exist (Tauri changes the webview the UI must run
  on).

## Proposal

1. A throwaway prototype on its own branch/worktree (nothing merges): a Tauri 2 shell in Rust that
   starts the same bundled JVM backend and loads the loopback URL; the model-download path of ADR
   0055 runs from the Rust side or the existing mechanism, whichever keeps the no-network-path test
   green.
2. Build the three artifacts with the Tauri shell: the `.deb` (system WebKitGTK as a declared
   dependency), the flake package, and the `nix bundle` (WebKitGTK carried in the closure).
3. Measure and record, next to Electron's numbers: `.deb` payload and compressed size; flake
   package closure; bundle executable/closure; glibc and WebKitGTK floors (`readelf -V`); startup
   time and resident memory; the oldest supported distribution the `.deb` reaches.
4. Establish the packaged e2e path for Tauri (tauri-driver with WebKitWebDriver, or an equivalent
   that drives the real window in the stock container) and run one journey through it; keep the
   existing Playwright journey as the UI regression test.
5. Record the outcome as an ADR: keep Electron, or migrate, with the measured numbers and reasons.
   On "migrate", file the migration item.

## Open questions

- Tauri and Rust pins in `devenv.nix` (Tauri 2.x), and the extra build time they add to CI.
- The `.deb`'s dependency set under Tauri, and whether the bundle carries WebKitGTK or uses an
  AppImage bundler (ADR 0053 left the bundler choice to evidence).
- Tauri's capability configuration for a loopback-only window, and whether any of its IPC surface has
  to be denied to keep ADR 0055's model.
- How a container drives the Tauri window (WebKitWebDriver under xvfb?) and whether that path is
  stable enough to be a required CI job.
- Whether the prototype is kept in the repository on its branch as evidence or discarded after the
  ADR records the numbers.

## Test list (approved 2026-10-18; behaviors and measurements)

- [x] A Tauri 2 shell starts the bundled backend and shows the real built UI at the loopback URL;
      upload → completed → transcript works through it, including the first-run model download path
      (ADR 0055)
- [x] (discovered) Under `tauri-driver` the backend was still running 30 s after the window closed.
      Recorded in ADR 0059; no follow-up because Electron stays (item 50's quit semantics are
      unchanged).
- [x] (discovered) The Queue upload control opens the native GTK file chooser, and selecting an audio
      file uploads and transcribes it through the Tauri window; playback works too. Confirmed by the
      user's manual run on 2026-10-19 (item 1's WebDriver `setInputFiles` bypasses the chooser).
- [x] (discovered) The GTK file chooser follows the system's dark preference. Confirmed 2026-10-19:
      the portal reports `prefer-dark`; the shell asks it before GTK initializes and sets
      `GTK_THEME=Adwaita:dark` (the launch-time variable GTK reads at init — `prefer-dark-theme`
      alone changed nothing here), and the chooser renders dark.
- [x] (discovered) WebKitGTK 4.1 renders `<select>` popups from the page's CSS `color-scheme`, so
      they stay light under a dark GTK theme; recorded in ADR 0059; no follow-up because Electron
      stays (its popups follow the system).
- [x] The `.deb` built with the Tauri shell installs and runs in a stock `ubuntu:24.04` container
      with only its declared dependencies; its payload and compressed size are measured and recorded
      next to Electron's 628 MB / 233 MB. Measured 2026-10-19: payload 479.2 MB, `.deb` 220.4 MB,
      payload as `tar | xz -6` 159.4 MB (Electron: 628 MB payload, 233 MB xz). Against that
      reference the savings are **148.8 MB (23.7%)** unpacked and **73.6 MB (31.6%)** compressed,
      but both are **cross-date** comparisons (Electron 2026-10-15, Tauri 2026-10-19) with different
      tool sets, so neither is like-for-like. The `.deb` file itself is 12.6 MB smaller than the
      Electron xz archive, but those are different archive formats and are not a like-for-like
      compression comparison. The container has no model fixtures beyond the store's two; apt
      installs only the declared dependencies (`libwebkit2gtk-4.1-0` 2.52.6,
      `libgtk-3-0t64`, `libsoup-3.0-0`, `libjavascriptcoregtk-4.1-0`, ALSA/Pulse, Vulkan, gomp, udev,
      xcb). The `.deb` carries no Electron (281 MB of Electron's payload), and its WebKitGTK comes
      from apt rather than the package — the flake package and bundle (item 3) must carry it and are
      where the comparison turns.
      Payload split (2026-10-19): binary 13.8 MB, backend 101.5 MB, jre 94.4 MB, tools 268.9 MB,
      ui 0.6 MB. Electron's reference is **not** a like-for-like baseline: its 149 MB tools figure
      predates item 62's audio-only static ffmpeg, so the same staged tools now weigh 269 MB; a
      current Electron `.deb` with these tools would be ~748 MB payload (inferred: 628 − 149 + 269),
      not 628 MB. On that updated-but-inferred baseline, Tauri's payload is 268.8 MB (35.9%) smaller.
      The ADR must state the reference date and distinguish measured from inferred comparisons.
- [x] The flake package and the self-contained bundle run on a machine without Nix and without host
      WebKitGTK; their closures and the bundle executable's size are measured and recorded next to
      ~1598.5 MiB. Measured 2026-10-19: `.#desktop-tauri` closure **1542.8 MiB**, **55.7 MiB
      (3.5%) smaller** than Electron's ~1598.5 MiB closure, and the bundle executable **490.9 MiB**
      (no Electron bundle size was recorded in item 50). The flake package ran in a stock
      `ubuntu:24.04` with only the read-only `/nix/store` mount (no apt WebKitGTK) and answered on
      loopback; the bundle ran in a stock `ubuntu:24.04` with **no** store mount, unpacking itself
      and starting through bubblewrap. `FONTCONFIG_FILE`/`XDG_DATA_DIRS` now point at the bundle's
      own Plex and Adwaita/hicolor themes, as in the Electron bundle — the first run's `Fontconfig
      error: Cannot load default config file` is gone.
- [x] The Tauri window reaches only the loopback backend: the ADR 0055 test (no request leaves the
      machine, no navigation to another origin) is re-run under Tauri's capabilities. **Measured
      2026-10-19: ADR 0055 is not expressible under Tauri 2** (a non-loopback fetch returned 200,
      `permissions: prompt`); recorded in ADR 0059. `spike/tauri/check-network.ts`:
      loopback control 200; a `fetch` from the page to the machine's own address
      (`192.168.178.20:<port>`) returned **status 200** — Tauri 2's public API has no subresource
      request filter (`on_web_resource_request` covers only `tauri://` assets, unlike Electron's
      `webRequest.onBeforeRequest`); `navigator.permissions.query` reports **prompt**, not denied
      (no permission handler is set); `window.open` is denied and a navigation away is refused, so
      the window's *guard* works. Remedies, both outside this spike: a CSP served by the backend
      (shared code, so a change to `main`), or accepting a weaker guarantee; the permission query
      can be denied from the shell via `with_webview` and WebKit's `permission-request` signal.
      Disposition (checkpoint 0, 2026-10-20): the expected outcome *is* this measurement — ADR 0055
      is not expressible under Tauri 2 — so the item is met and ticked; ADR 0059 carries it.
- [x] A packaged e2e path drives the Tauri window in the stock container through one journey
      (upload → completed → transcript), and the existing Playwright journey stays green on the
      served UI. **Green, both halves.** The Tauri half (2026-10-19):
      `spike/tauri/check-packaged.ts` started `ubuntu:24.04` with the `.deb` installed (apt),
      `webkit2gtk-driver` 2.52.6 and the store's `xvfb-run`, tauri-driver from the store on host
      networking, the fixture models from the read-only store mount, and drove the installed
      `/usr/bin/stt-tauri-spike`: window at `http://127.0.0.1:<port>/queue`, `Live`, upload
      `/fixtures/jfk.wav`, `transcribed`, History, transcript text — `transcript verified`. The
      Playwright clause (2026-10-20): `./gradlew e2e` in a stock `ubuntu:24.04` container (the
      README's supported path, `README.md:130,221-225`; `./gradlew runtimeSystemDeps` and
      `e2eSystemDeps` installed the whisper-cli/ffmpeg and Chromium libraries as root, as CI does)
      built the frontend and ran the whole item-21 suite on the served UI —
      **40 passed (7.0m), BUILD SUCCESSFUL**. The earlier `pnpm --dir e2e test` failure in global
      setup was the devshell's slim ffmpeg missing the `wav` muxer — the documented not-the-e2e-path
      invocation, not a defect on `main`.
- [x] The glibc and WebKitGTK floors (`readelf -V`) are measured and recorded, and the oldest
      supported distribution for the `.deb` is stated. Measured 2026-10-19 (`check-floors.ts`):
      binary **GLIBC_2.39**, whisper-cli and `libggml-base`/`libggml-vulkan` **2.38** (also their
      links), `libggml`/`libggml-cpu` 2.34, the audio-only ffmpeg **2.34** (not static, as item 62's
      notes had assumed), jlink JRE 2.15 (java 2.2). Highest: **2.39** from the binary, so the
      `.deb` needs **Ubuntu 24.04+ (glibc 2.39) or Debian 13+ (glibc 2.41)**; WebKitGTK is the
      distro's 4.1 API (`libwebkit2gtk-4.1-0`; 24.04 ships 2.52.6), so a distribution's WebKit
      version, not a bundled one, decides its behaviour. The flake package and bundle carry their
      own glibc and have no floor. Electron's `.deb` floor for comparison: 2.38, Ubuntu 23.10+ /
      Debian 13+; Tauri raises it to 2.39 (Ubuntu 24.04+, i.e. drops 23.10).
- [x] The outcome — keep Electron or migrate — is an ADR with the measured numbers and reasons; on
      "migrate", the migration's own roadmap item is filed. The user chose **keep Electron** at the
      checkpoint, on the evidence above (sizes and closure measured and compared, the ADR 0055
      window probe red, and the item-5 journey green on both halves — the packaged Tauri journey
      and, once re-run with the supported `./gradlew e2e`, the Playwright suite: 40 passed). Written
      2026-10-20: ADR
      [0059](../decisions/0059-os-shell-stays-electron.md) and its row in
      `docs/decisions/README.md`; no migration item is filed.
- [x] (must not break) Nothing on `main` changes: the prototype lives on its own branch/worktree
      only, and CI, the three desktop artifacts and the existing e2e are untouched. Verified
      2026-10-20: `main` is `52e99c3b` and the branch was rebased onto it, so `git diff main...HEAD`
      is the branch's own work — only `spike/tauri/`, the `devenv.nix` Rust/Tauri pins, six new
      `nix/tauri-*.nix`, `nix/bundle-*.nix` and `tauri-driver.Cargo.lock` files, the 10-line
      `flake.nix` addition, a refactor of `nix/desktop-bundle.nix` that both shells share, and this
      roadmap file.
      `.github/workflows/`, `desktop/`, `e2e/`, the backend and the frontend have no branch changes,
      so `main` and its Electron artifacts are exactly as item 50 left them.

## Cycle log

- 2026-10-19, test item 1 (green): `xvfb-run -a -s "-screen 0 1440x900x24 -dpi 96" node
  spike/tauri/check.ts` printed `PASS: the Tauri shell ran the bundled backend and the whole
  journey at http://127.0.0.1:41085/queue` — window attached to the loopback UI, both models
  downloaded from the loopback fixture server and verified into a fresh `${HOME}/.cache`, upload →
  finish notice → History → transcript verified in the DOM, then `window closed; the backend on
  port 41085 was still running after 30 s` (the discovered item).
  Red before it existed (`e2251c32`): "the Tauri prototype does not exist yet:
  …/spike/tauri/src-tauri/Cargo.toml is missing".
  Findings that feed the ADR, all measured on this machine: WebKitGTK 2.54.1 on GTK3 scales the
  page by `gdk_screen_get_resolution()/72`, which is negative without an XSETTINGS manager — the
  UI collapses to a few px (fixed in the shell by setting 96 DPI after GTK init); WebKitWebDriver's
  hit testing failed under that same negative scale and clicks work again after the fix; the
  transcript page's `<audio>` needs GStreamer (coreelements, base, good) or the WebProcess dies;
  an unwrapped GTK binary needs GTK's compiled schema dirs for the file chooser.
  Pre-fix evidence, kept on the branch (nothing merges): `spike/tauri/evidence/README.md` and
  `spike/tauri/evidence/2026-10-19-pre-fix-96dpi.png`, a screenshot of the collapsed window taken
  2026-10-19 18:51 UTC before the 96-DPI fixes (19:20/19:23) and the first green run (19:59).

- 2026-10-19, test item 2 (green): `node spike/tauri/check-deb.ts` printed `CONTAINER-OK` and
  `PASS: the Tauri .deb installs and runs on ubuntu:24.04 with its declared dependencies
  (payload 479.2 MB, .deb 220.4 MB, xz 159.4 MB)`. Red before the bundler existed: "no .deb under
  … — the Tauri bundler is not configured for this shell yet".
  Findings that feed the ADR: the Debian bundler declares **no** `Depends` by default (they are
  listed in `tauri.conf.json` now); resources live in `/usr/lib/<product>/` (the shell reads Tauri's
  `resource_dir()`); building in the Nix devshell sets the ELF interpreter to the store's `ld-linux`
  (glibc 2.44) and adds store RPATHs, so the packaged binary needs
  `patchelf --set-interpreter /lib64/ld-linux-x86-64.so.2` and an RPATH strip — its highest glibc
  symbol version is GLIBC_2.39, Ubuntu 24.04's own, so the same binary runs there (item 6's floor
  data); `tauri-build` copies `bundle.resources` into `target/release/resources` on every build and
  fails with EACCES over the staged JRE's read-only modes unless that copy is cleared.

- 2026-10-19, test item 3 (green): `node spike/tauri/check-nix.ts` printed `FLAKE-OK`,
  `BUNDLE-OK` and `PASS: the Tauri flake package (1542.8 MiB closure) and the self-contained bundle
  (459.8 MiB) run without Nix or host WebKitGTK (Electron reference: ~1598.5 MiB closure)`. Red
  before the outputs existed: "flake … does not provide attribute … desktop-tauri".
  Findings for the ADR: the Nix Rust build needs a committed Cargo.lock and an explicit fileset
  (target/ must not enter the store); `TAURI_CONFIG` (an RFC 7386 merge) turns off the bundler's
  resource copy; the bundle container needed the same bubblewrap permissions as the repo's Electron
  harness (`CAP_SYS_ADMIN`, seccomp/systempaths/apparmor unconfined) — a real host needs none; the
  closure is measured with `nix path-info -S` (summing `-rS` repeats each closure).

- 2026-10-19, test item 4 (measured failure): `xvfb-run -a -s "-screen 0 1440x900x24 -dpi 96" node
  spike/tauri/check-network.ts` printed `loopback fetch: 200`, `external fetch: status 200`,
  `window.open: denied`, `permissions: prompt`, `navigation away: refused`, `stand-in server
  requests: 2`, then failed with "the page reached 192.168.178.20:42375 (status 200): no subresource
  filter" and 'navigator.permissions.query says "prompt"'.

- 2026-10-19, test item 6 (green): `node spike/tauri/check-floors.ts` printed the GLIBC lines above
  and `PASS: measured floors recorded — GLIBC_2.39 from binary; the .deb therefore needs Ubuntu
  24.04+ (glibc 2.39) or Debian 13+ (glibc 2.41), with the distro's WebKitGTK 4.1`. The measurement
  corrected two assumptions: the audio-only ffmpeg is dynamic (2.34), and the staged ggml libraries
  repeat per symlink.

- 2026-10-19, test item 5 (Tauri half green, Playwright clause blocked): `node
  spike/tauri/check-packaged.ts` printed `window at http://127.0.0.1:39797/queue`, `Live`,
  `uploaded /fixtures/jfk.wav`, `transcribed`, `clicked History`, `clicked the transcript`,
  `transcript verified`, then `pnpm --dir e2e test` failed in global-setup with
  "Unable to choose an output format for '/tmp/…/jfk-b.wav'" (no wav muxer in the item-62 ffmpeg;
  main at 52e99c3b fails the same way). Path findings for the ADR and item 5's CI form: apt's
  WebKitWebDriver 2.52.6 has no `/status`; apt's `xvfb-run` stalls without `xdpyinfo`, the store's
  works; tauri-driver binds 127.0.0.1 in its own namespace, so the container needs host networking
  (or the client inside it).

- 2026-10-20, checkpoint and closeout: the user chose **keep Electron** and ADR evidence only. ADR
  [0059](../decisions/0059-os-shell-stays-electron.md) records the measured comparison, the
  decision and the reasons, with its row in `docs/decisions/README.md`; no migration item is filed.
  Item 4 stays open as its measured failure (the ADR 0055 window probe reached a non-loopback
  address with status 200 and the permission query says `prompt`, so the ADR states that the
  guarantee no longer holds under Tauri). Checkpoint 0 (2026-10-20): item 4's expected outcome was
  amended to its measurement and the item ticked; the two discovered observations (the backend
  alive 30 s after the window closed, and WebKitGTK's `<select>` popups) are kept, reworded with no
  follow-up since Electron stays, and ticked; ADR 0059's size rows are labelled as stale cross-date
  comparisons, with the inferred ~36% unpacked basis and the current compressed saving stated as
  unknown.

- 2026-10-20, test item 5 (second half green): `./gradlew e2e` in a stock `ubuntu:24.04` container
  printed **`40 passed (7.0m)`** and `BUILD SUCCESSFUL` — the whole item-21 Playwright journey on
  the served UI (frontend built by `:frontend:frontendBuild`, backend run through `gradle run`,
  models and fixtures from the Gradle-managed cache; `./gradlew runtimeSystemDeps` then
  `e2eSystemDeps` installed the prebuilt tools' and Chromium's apt libraries as root, exactly the
  CI recipe). Item 5 is ticked; the test list is fully ticked. Host-path findings: the Gradle
  downloads (node, whisper-cli, bytedeco ffmpeg) are generic-linux binaries, so `./gradlew e2e`
  cannot run on a NixOS host — the devenv's stub-ld rejects them (`node` started by
  `:frontend:pnpmSetup` failed with "Could not start dynamically linked executable") — and the
  e2e host per README and CI is Ubuntu/Debian; on a bare `ubuntu:24.04` the managed ffmpeg needed
  `libpulse.so.0` until `runtimeSystemDeps` installed it. Neither is a regression on `main`.
  Documentation checks green (the user's run, 2026-10-20): `node docs/check-links.mjs README.md
  docs/architecture.md` → "All relative links resolve in README.md, docs/architecture.md.";
  `node docs/check-architecture-names.mjs` → "All 168 code names in docs/architecture.md exist in
  the code."; `node docs/check-architecture-diagram.mjs` → "The diagram in docs/architecture.md
  matches the code: 11 boxes, 10 arrows."

e2e: the packaged Tauri journey above is this item's end-to-end proof (ADR 0046); no app feature
changes because nothing merges.

## Acceptance criteria

- An ADR records the measured comparison (sizes, floors, e2e path) and the decision, with the
  reasons; the prototype's branch is available to the user for inspection.
- On "migrate": the migration item exists with its spec and test list; on "keep Electron": the
  numbers are in the ADR and this item closes.
- All test-list items are ticked with their evidence; the user approved the diff.

## Approval log

- 2026-10-18: filed at the user's request after a consultation on two proposed architecture changes
  (React → Astro/Svelte, Electron → Tauri). The user chose: keep React (the UI is 0.6 MB of the
  628 MB payload); evaluate Tauri first with a spike that keeps all three artifacts and a packaged
  e2e; defer the frontend question until the Tauri numbers exist. Test list approved as worded above.
  Status: spec-approved. Filed on `main` through a docs PR.
- 2026-10-20: the user re-approved the closeout as checkpoint 0, after inspecting the spike and the
  ADR draft: the **keep Electron** decision stands, ADR 0059 is the outcome record, and **no
  migration item is filed** (the root agent prepares a docs-only merge commit with ADR 0059, the
  decisions index row, the moved item-64 spec and the roadmap row). Dispositions approved and
  applied: (1) item 4 amended to "measured: ADR 0055 is not expressible under Tauri 2 (non-loopback
  fetch 200, permissions prompt); recorded in ADR 0059" and ticked; (2) item 5 keeps its green Tauri
  half and its Playwright clause is re-run with the supported `./gradlew e2e` (not the devshell's
  slim ffmpeg), ticking on the green run — it passed, 40 tests, and the item is ticked; (3) the
  discovered window-close observation is kept, reworded ("the backend was still running 30 s after
  the window closed; recorded in ADR 0059; no follow-up because Electron stays") and ticked; (4) the
  discovered WebKitGTK `<select>` popup observation is kept, reworded ("WebKitGTK 4.1 renders
  `<select>` popups from the page's CSS `color-scheme`; recorded in ADR 0059; no follow-up because
  Electron's popups follow the system") and ticked; (5) the optional post-fix screenshot was not
  requested as a new scenario; (6) ADR 0059's 24–32% title/rows are labelled as stale cross-date
  comparisons, the comparable inferred unpacked saving (~36%) is stated, and the current compressed
  saving is stated as unknown — the user approved the wording; (7) this dated entry. The pre-fix
  screenshot was committed on the branch under `spike/tauri/evidence/` with a provenance note
  (nothing merges). Status: in-review.

- 2026-10-21 (landing, docs-only): re-reviewed by the root agent (all test-list items ticked, the
  `./gradlew e2e` run and the ADR wording verified, docs link checks green) and the user approved
  the closeout. This spec moved to `docs/specs/tauri-evaluation.md`; ADR 0059 and its index row
  landed; the roadmap row and graph node are done. The prototype and its evidence stay on
  `roadmap/64-tauri-evaluation` and are never merged.
