# 70 — Replace Electron with a Rust GUI for the window flavor

Status: spec-approved
Depends on: [66](66-local-service-and-frontends.md) (the service, descriptors, credential and Rust
pin — spec-approved), [67](67-tui-frontend.md) (the `stt-client` crate and the Rust workspace —
spec-approved), [49](../docs/specs/windows-support.md) (the Windows packages)
Blocks: —

## Goal

The window flavor no longer ships Electron or Chromium: it is a native **Slint** window in the
item-66 client model (attaches to the per-user service, starts it when absent, uses its credential),
accepts audio files dropped onto the window for upload, and is packaged in the same Linux, macOS and
Windows artifacts with an automated end-to-end proof. It reaches parity with the browser UI,
including the stenographer animation and audio playback. The browser frontend stays React; the tray
(Rust, item 66) and the TUI (Rust, item 67) are unaffected.

## Evidence

- **ADR 0059 keeps Electron on measured grounds**: item 64's Tauri 2 prototype worked on all three
  Linux artifacts and the packaged e2e, but the savings are the shell alone — 13.8 MB of Tauri's
  479.2 MB payload versus Electron's 281 MB of 628 MB — and the current, like-for-like comparison
  is inferred at ~36% unpacked (compressed unknown). The rest of the payload (backend ~102 MB,
  jlink JRE ~95 MB, tools 268.9 MB, UI 0.6 MB) is identical in both.
- **The reason for that decision was ADR 0055, not size**: Tauri's public API has no subresource
  request filter, so the window could reach a non-loopback address (measured: `fetch` → 200,
  permissions `prompt`). A webview shell must replace that guarantee deliberately; a native GUI has
  no web content at all, so the boundary becomes "only the service origin" and is enforced by the
  client code and the probe in this item's test list.
- **The service model changes the shell's job** (item 66): the window is a client of the local
  service, not the owner of a private backend; model/tool provisioning moves with the service, so
  the Electron `models.ts`/`progress.html`/`preload.cts` download screen is no longer the shell's.
- **A Rust client and workspace already exist in the plan** (items 66/67): `stt-client` (discovery,
  credential, task/queue/transcript/speaker/merge/settings HTTP, SSE with reconnect) with the tray
  and the TUI, reqwest + tokio without TLS, a pinned toolchain and a committed `Cargo.lock`. The
  window is a fourth workspace member (`stt-window`), not a new integration.
- **Slint licensing is not a blocker** (verified 2026-10-22, upstream `LICENSE.md`): Slint is
  offered under the **Royalty-free Desktop, Mobile, and Web Applications License** (proprietary
  desktop/mobile/web use at no cost; embedded excluded), GPLv3, or a commercial license. This
  desktop app uses the royalty-free option: the repository stays BSD-3-Clause, Slint is a
  dependency, and `docs/attributions.md` records it. GPLv3 would have forced the combined work
  under GPLv3; it is not chosen.
- **Slint has no OS file-drop API** (verified 2026-10-22): `DragArea`/`DropArea` cover in-process
  drags and, per the docs, "drops from another application on platforms that support it";
  `DataTransfer` mentions file paths but says the mapping "depends on platform and features". A
  Slint maintainer answered the exact file-drop question (discussion #9424, 2025-09-15): *"There is
  not yet API in slint for this. A more advanced d&d feature is tracked in #1967. Maybe you can get
  the winit api directly … CustomApplicationHandler … DroppedFile event."* A Slint 1.17
  troubleshooting note reports no OS-native file-list drops on any backend. **This is the item's
  main framework risk**: the drop path must use the winit backend integration, not Slint's stable
  API. The user chose Slint with that workaround explicitly.
- **Slint renderers** (upstream README): a pure-Rust **software renderer** (CPU, no extra
  dependencies), **femtovg** (OpenGL ES 2.0) and **Skia**. The self-contained bundle's Mesa is
  Vulkan-only, so the software renderer is the only path that needs no GPU stack there;
  accessibility is AccessKit-based (shipped since Slint 1.1); Slint also targets the web (WASM),
  which this item does not use.
- **Slint's testing backend is internal** (`internal/backends/testing`), not a stable public test
  API; the window's tests therefore live at the logic seams plus fixed-time snapshots and the
  packaged e2e, with a visual sign-off for the animation.
- **Drag-and-drop upload is part of the window's contract** (the user, 2026-10-22): the browser
  drops any number of files, checks each extension case-insensitively against the shared contract
  `docs/api/allowed-extensions.json` (19 extensions, also pinned by the backend's
  `AllowedExtensionsContractSpec`) before any request, shows per-file upload progress (`XHR`,
  because `fetch` has none), sends the upload-options panel's language/speakers fields, and
  surfaces the server's 1 GiB cap and `409 duplicate-audio`.
- **The animation** (item 17): one self-contained SVG with 138 elements, nine CSS animations (wave,
  bob, blink, drift, press, flash, rise, hum, twinkle), 30 theme tokens, typing only while a task
  runs and a fully static rest otherwise, a `prefers-reduced-motion` fallback, no flash faster than
  3 Hz, and two sizes (120 px header, ~120–160 px empty/loading). The still and favicon are
  derivatives.
- **Electron-specific surface that goes away**: `desktop/src/{main,backend,models,progress,
  window-policy,download-hosts}.ts`, `preload.cts`, `stage.ts`, `electron-builder.yml`,
  `desktop/package.json`, the pinned Electron/electron-builder downloads, `e2e/desktop-tests/`,
  `e2e/playwright.desktop.config.ts`, and the Electron form of the CI jobs `desktop-e2e`,
  `desktop-nix-e2e`, `desktop-bundle-e2e`, `macos-dmg`.
- **Playback and rich UI live in the window today**: the transcript player, per-segment playback,
  speaker rename/merge, merges and settings. The native window implements them (the TUI does not;
  item 67 excludes playback).

## Considered and rejected

- **A Rust webview shell (`wry`/`tao`, Tauri's engine)**: keeps the React UI but repeats Tauri's
  measured WebKitGTK costs and needs a new network-boundary decision; the user chose a native UI.
- **egui/eframe and iced**: both have first-class OS file drops and would avoid the winit
  workaround; the user chose Slint. They are the recorded fallback if the drop integration proves
  unworkable (see the risk note).
- **Moving the browser frontend to Slint's web target**: rejected (the user): canvas/WASM and the
  Slint runtime would replace React, the design system and the 173 frontend tests, for no size
  win. The browser stays React; the window's UI is a deliberate second implementation.

## Decided (consultation, 2026-10-22)

- **Native Slint GUI** (the user): the window is a native window, not a webview; Slint under its
  royalty-free license.
- **Drag-and-drop via the winit integration** (the user, with the maintainer's limitation known):
  one adapter built on Slint's winit backend (`CustomApplicationHandler`) receives `DroppedFile`,
  `HoveredFile` and `HoveredFileCancelled`, resolves the paths and queues them through
  `stt-client`. The adapter is the only place that touches winit; the upload logic behind it is
  framework-free and unit-tested. If the adapter cannot be made to work in the first cycle, stop
  and ask the user whether to switch to egui or iced (first-class drops) — do not build a bespoke
  windowing layer.
- **Software renderer shipped by default** (the user: no GPU requirement; the earlier wgpu/glow
  question does not apply): every package uses Slint's pure-Rust software renderer, so the window
  needs no GPU or GL stack and the bundle's Vulkan-only Mesa is irrelevant to it. femtovg is
  considered only if the first cycle's frame-time measurement demands it, and never required.
- **The animation is reimplemented as Slint vector elements** (the user): the geometry is generated
  from `stenographer.svg` once (codegen into `Path`/shape elements), and the nine animations are
  Slint property animations with the same timings, delays and theme-token palette; typing/rest
  gating, the reduced-motion static frame and the 3 Hz flash limit carry over. Look is verified by
  fixed-time snapshots plus the user's visual sign-off in the real window (item 17's pattern).
- **Full window parity** (the user): queue and controls, drag-and-drop upload, history, transcript
  with turns, per-segment playback, speaker rename/merge, task merging, settings and the animation.
  Audio playback is implemented natively with the same semantics as the browser's
  `playbackMachine` (click-to-seek plays on, the per-segment control plays `[startMs, endMs)` once).
- **The browser stays React** (the user); no Slint web target.
- **The window joins the item-66/67 Cargo workspace** as `stt-window`, reusing `stt-client`, the
  pinned Rust toolchain, `Cargo.lock` and the sync check; the `gui` descriptor launches it.
- **Electron leaves in the same cutover**, only after the new window's packaged e2e passes on all
  three OSes; `desktop/` and its CI jobs are deleted then.
- **Test list** approved as worded below (11 items).
- **A new ADR** records the shell choice, the royalty-free Slint dependency, the drop adapter risk
  and the renderer policy, superseding ADR 0059's shell decision and restating ADR 0055's boundary
  as "the window talks only to the service origin".

## Proposal

1. `stt-window` crate: Slint UI (`.slint` files) + Rust logic through `stt-client`; descriptor
   `gui`; attach/start-service lifecycle; explicit quit; the service's credential header.
2. Drop adapter: winit `DroppedFile`/`HoveredFile` over one small module; validate extensions
   against generated constants from `docs/api/allowed-extensions.json`; multi-file uploads with
   per-file progress; upload-options panel; the server's size/duplicate errors; folder drops out
   of scope (the browser does not accept them either).
3. UI parity: queue and controls, history, transcript with turns and speakers, per-segment
   playback and the player (a native audio stack with `playbackMachine`'s semantics), rename/merge,
   merges, settings, loading/empty states and the animation.
4. Animation: a codegen step from `stenographer.svg` to Slint elements; the nine animations as
   Slint property animations; reduced-motion detection per OS (or an app setting) settled in the
   first cycle.
5. Packaging: replace the Electron shell in the `.deb`, flake package, bundle, DMG, zip and MSI;
   software rendering; the tray and TUI unchanged; remove `desktop/` and its jobs after the green
   e2e.
6. Measurement: payload/closure, glibc floor, startup time and resident memory next to a freshly
   recorded Electron baseline; the ADR records the comparison.

## Open questions (settled in the first cycles)

- **How the packaged e2e drives a drop**: XDND automation is platform-specific and flaky; the plan
  is the framework-free upload seam tested automatically plus a real drop exercised on at least one
  OS (or the user's manual sign-off). The exact mechanism is chosen at the first test item.
- **Reduced-motion source**: Slint exposes no cross-platform reduced-motion query; macOS, Windows
  and Linux each have a setting, and an app setting is the fallback.
- **Audio stack**: `rodio`/`cpal` vs `cpal` + a decoder, chosen with the playback test; seek and
  pause-at-`endMs` semantics must match the browser.
- **Slint version pin, license notice and codegen location** for the SVG geometry.
- **Automated look comparison** for the animation: Slint's testing backend is internal; snapshot
  rendering at fixed times plus the user's visual sign-off is the accepted route unless a stable
  harness is found in the first cycle.

## Test list (approved; behaviors only)

- [ ] The packaged Linux artifact opens the window and completes upload → completed → history
      against the service, with no Electron/Chromium in the payload or closure
- [ ] The window attaches to the running service and starts it when absent; closing the window
      leaves the service running and an explicit quit stops it (item 66 semantics)
- [ ] First run downloads and verifies the pinned models with progress and Retry, reusing the
      store/cache path
- [ ] The agreed UI surface works in the window: queue and controls, history, transcript with
      speaker turns, per-segment playback, speaker rename/merge, task merging, settings and the
      stenographer animation
- [ ] Dropping files onto the window uploads them: several supported files at once are queued
      (each reaching its task) with per-file progress; an unsupported extension is rejected before
      any request, matching the browser's error; the server's size cap and a duplicate answer show
      the browser's messages; dropping while the queue is paused still queues
- [ ] The window's network boundary matches the decided rule, proved by a probe (no external
      requests; only the service origin)
- [ ] The macOS package and the Windows package ship the same shell and run the journey
- [ ] Payload/closure sizes, glibc floors, startup time and resident memory are measured and
      recorded next to a freshly recorded Electron baseline
- [ ] The tray lists and opens the window, and no duplicate service starts
- [ ] (must not break) The browser UI, the API contract, Docker, and the Linux/macOS/Windows server
      suites and e2e stay green
- [ ] (must not break) The TUI and tray from items 66/67 are unaffected; the shared `stt-client`
      crate has one implementation

## Acceptance criteria

- The window flavor ships no Electron/Chromium on Linux, macOS and Windows; it is a native Slint
  client of the per-user service with the decided lifecycle, drag-and-drop upload and network
  boundary, rendered without a GPU requirement.
- The agreed UI surface, including per-segment playback and the stenographer animation, is in the
  window, with the browser and TUI unaffected.
- Sizes, floors, startup and memory are recorded against the Electron baseline; `desktop/` and its
  Electron CI jobs are removed in the same cutover; the ADR records the choice and the drop risk.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-10-22: drafted at the user's request, as the follow-on to item 66's "considering another
  cross-platform GUI framework" note and ADR 0059's measured Electron decision. Not yet discussed.
- 2026-10-22 (constraint added): the user requires drag-and-drop audio upload in the window; the
  parity evidence and the test list gained it.
- 2026-10-22 (consultation): approach B (native) chosen. The user chose **Slint**, accepting the
  winit workaround for OS file drops after the maintainer's "not yet API" answer; the royalty-free
  license keeps the repo BSD-3, and GPLv3 is not used. The browser stays React; the animation is a
  generated vector reimplementation; the software renderer is the shipped default (no GPU
  requirement). Test list approved (11 items). Status: spec-approved; work starts after items 66
  and 67.
