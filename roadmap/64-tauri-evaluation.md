# 64 — Evaluate Tauri as the desktop shell

Status: spec-approved
Depends on: [50](../docs/specs/electron-desktop-app.md) (done; the Electron desktop app, its three
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

- [ ] A Tauri 2 shell starts the bundled backend and shows the real built UI at the loopback URL;
      upload → completed → transcript works through it, including the first-run model download path
      (ADR 0055)
- [ ] The `.deb` built with the Tauri shell installs and runs in a stock `ubuntu:24.04` container
      with only its declared dependencies; its payload and compressed size are measured and recorded
      next to Electron's 628 MB / 233 MB
- [ ] The flake package and the self-contained bundle run on a machine without Nix and without host
      WebKitGTK; their closures and the bundle executable's size are measured and recorded next to
      ~1598.5 MiB
- [ ] The Tauri window reaches only the loopback backend: the ADR 0055 test (no request leaves the
      machine, no navigation to another origin) is re-run under Tauri's capabilities
- [ ] A packaged e2e path drives the Tauri window in the stock container through one journey
      (upload → completed → transcript), and the existing Playwright journey stays green on the
      served UI
- [ ] The glibc and WebKitGTK floors (`readelf -V`) are measured and recorded, and the oldest
      supported distribution for the `.deb` is stated
- [ ] The outcome — keep Electron or migrate — is an ADR with the measured numbers and reasons; on
      "migrate", the migration's own roadmap item is filed
- [ ] (must not break) Nothing on `main` changes: the prototype lives on its own branch/worktree
      only, and CI, the three desktop artifacts and the existing e2e are untouched

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
