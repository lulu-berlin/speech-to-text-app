# 89 — The service package: the runner, the CLI and the tray, installed together

Status: spec-approved
Depends on: [66](../docs/specs/local-service-and-frontends.md) (the unit this package ships)
Blocks: line 4 of item 66's test list (the window attaching to a running service, proven in CI)

## Goal

Per OS, **one package** that installs everything the app is — the `stt-service` runner (the backend
in app mode), the `stt-app` CLI, the `stt-tray` binary built with its `tray-menu` feature, the
backend's installDist, the JRE, the built UI and the `stt-window` GUI — all beside each other, so
`stt-app open browser` starts the service and the tray with no environment variable pointing at
anything else ([ADR 0064](../docs/decisions/0064-service-and-tray-are-one-unit.md)) and the window
needs no separate install. The window is bundled, not optional.

## Evidence

- **The unit has no artifact of its own.** `flake.nix` packages the dev/server app and the desktop
  window; the client workspace builds `stt-app` and `stt-tray` from source (`client/stt-client`), but
  nothing installs the `stt-service` runner the CLI and the tray spawn
  (`stt-client/src/lib.rs`, `start_runner_beside`), so `stt-app open` fails with "no stt-service
  beside …" outside a source checkout.
- **Autostart points at that runner**: the login entry names `<dir>/stt-service service`
  (`stt-client/src/autostart.rs`), so a checkout's entry is inert.
- **The desktop e2e harness installs one app**: `e2e/desktop-tests/helpers/app.ts` launches
  `STT_DESKTOP_APP` alone, so a spec cannot start a service for the window to attach to
  (`e2e/desktop-tests` has no such spec because there is no service to start). With one package, that
  harness starts the unit and the window from one install.
- **Item 66's line 4 is written against this**: "the window attaches to a running service … and
  closing the window leaves the service running" needs this package in the CI job that runs the
  packaged specs (`.github/workflows/ci.yml`: `desktop-e2e`, `macos-dmg`, `windows-desktop-zip`).
- **The window's size was the reason for two artifacts, and it is gone**: item 66's consultation
  (2026-11-04) split the app because "the window is optional and nobody downloads what they do not
  use", and the window then was Electron, 281 MB of a 628 MB payload (ADR 0059). Item 70 replaces it
  with a native Slint window: 13.8 MB of binary in Tauri's comparable payload, plus the carried
  fonts — a rounding error beside the backend, the JRE and the tools (269 MB), and smaller still once
  items 103–106 move the backend to Rust (item 108).

## Decided (consultation, 2026-11-04)

- **The unit is one singleton, and every frontend goes through it** (the user): the service and the
  tray behave as one unit (ADR 0064); the browser, the window and the TUI talk to that unit and never
  around it.
- **No systemd unit; XDG autostart only** (the user): item 66's model stays — the autostart entry
  (off until `stt-app autostart on`) starts the unit and the tray supervises the service.
- **Future extension, not this item** (the user): a systemd mode in which systemd alone supervises
  the service, instead of the tray.
- **One installed version at a time** (the user): a newer installer replaces the installed version
  in place — the stable package name (`.deb`), the stable UpgradeCode with a major upgrade (the
  MSI), the same app-bundle path (the macOS app) — and the user's data directory is left alone, so
  settings and the database survive and the new backend migrates them on its first start. Out of
  scope: the Windows portable zips (not installers) and copies of a macOS app kept outside its
  install location.
- **Downgrades are refused** (the user): an older installer stops with a message naming the
  installed version and changes nothing; going back means uninstalling first. A database a newer
  version has migrated is never handed to an older backend.
- **An upgrade quits the running unit and starts nothing** (the user): the installer runs a clean
  `stt-app quit` (no backend, whisper or ffmpeg left behind) before replacing files — Windows locks
  running files anyway — and the next `stt-app open` starts the new version.
- **A frontend refuses to start when its version and the service's differ** (the user), naming both
  versions: with one package they are installed together, but a binary built from source or copied
  in by hand can still skew, and the check costs nothing.

## Decided (consultation, 2026-11-14, supersedes the 2026-11-04 split)

- **One package per OS, everything in it** (the user): the split into a service package and a window
  package existed because the window was Electron (281 MB of a 628 MB payload, ADR 0059), and the
  reason was "nobody downloads what they do not use". With item 70's native window (13.8 MB of
  binary plus the carried fonts, ADR 0059's measurement table), that saving no longer pays for two
  packages, two version locks and a bootstrapper: the window is bundled, and no per-user choice to
  omit it is offered — installing the app installs all of it.
- **Linux: one `.deb`** (the user): a single package with the unit and the window, its window
  dependencies declared, no `Depends` on a second package. Browser-only users install the same
  package and never launch the window; the Nix side mirrors it with one package.
- **Windows: one MSI, no features** (the user): one product, one UpgradeCode, no feature tree to
  untick and no WiX Burn bootstrapper — nothing is downloaded on demand, everything is in the MSI;
  the portable zips stay as they are.
- **macOS: one DMG, one app** (the user): the app bundle holds the unit and the window together (the
  tray is its visible UI; the CLI is linked onto `PATH` on first run), dragged to Applications once.
  No second app to install, no "Get the window…" action, and no `.pkg` (it would need an installer
  signing identity ADR 0060 does not have).
- **The split's other decisions stay**: the singleton unit, XDG autostart only, one installed version
  at a time, refused downgrades, the clean quit before replacing files, and the frontend's
  version-mismatch refusal.

## Proposal

1. **Contents of the one package**: `bin/stt-service` (a small runner that execs the backend with
   the packaged JRE in app mode), `bin/stt-app`, `bin/stt-tray` (with `tray-menu`), `stt-window`
   (item 70), `backend/lib` (Gradle `installDist`), `jre`, `ui` (the built frontend). Per OS it is
   one `.deb` (window dependencies declared, no second package), one MSI (one product, no feature
   tree, the portable zips unchanged) and one macOS app bundle with the window inside it, shipped in
   one DMG.
2. **Registration**: the package registers the frontends it ships (browser needs no descriptor; the
   window and the TUI are registered) and ships the autostart entry, off until
   `stt-app autostart on`.
3. **CI**: a job per OS that installs the single package, runs `stt-app open browser --print-url`
   to bring the unit up and then the packaged desktop spec that asserts the window attaches, one
   backend runs, and closing the window leaves the service up.
4. **Not in this item**: the tray's icon on a headless runner (proven virtual), and the TUI
   (item 67).
5. **Upgrades**: in-place replacement on every installer, refused downgrades, the clean quit before
   replacing files, and the frontend's version-mismatch refusal. The release version itself comes
   from item 78 (`VERSION`); the tests build two versions of the package to prove the upgrade and
   the refusal.
6. **An ADR records the one-package model** — the reversed split, the Electron size it rested on and
   the per-OS forms — when this item is worked.

## Open questions

None. (The one-package model settled 2026-11-14; everything else 2026-11-04 — see "Decided"
above.)

## Approval log

- 2026-11-02 (workspace): drafted while finishing roadmap item 66, from its proposal 6 and the
  missing CI proof of its line 4. The test list is written with the user when this item is approved.
- 2026-11-04: consultation with the user: the unit is a singleton every frontend goes through; the
  window is optional and nobody downloads what they do not use, so two artifacts per OS with the
  window depending on the service; Linux two `.deb`s with `Depends`; XDG autostart only, no systemd
  unit (a systemd-supervised mode is a future extension). Open: macOS's form of the two artifacts,
  and the test list. Status stays draft.
- 2026-11-04: macOS settled (two DMGs, the window checks for the service). Test list approved as
  written — the seven lines moved from item 66 and the four derived from the consultation; status set
  to spec-approved.
- 2026-11-04 (amendment, before the push): a one-go install of both artifacts when the user chooses
  it — Linux one `apt install` (documented), Windows a Burn bootstrapper downloading on demand, macOS
  the tray's "Get the window…" — with one test-list line per OS; the user accepted the
  recommendation. Still spec-approved.
- 2026-11-04 (amendment, before the push): one installed version at a time — newer installers
  replace the installed version in place, older ones are refused with the installed version named,
  an upgrade quits the running unit cleanly and starts nothing, and the two artifacts are locked to
  one release with a frontend refusing to start on a version mismatch (the user's three decisions);
  five test-list lines added. Cross-item note: item 78's line moved from item 66 ("a launch of a
  different version replaces the running unit") overlaps this item's mismatch refusal; reconcile it
  when item 78 is worked. Still spec-approved.
- 2026-11-14 (amendment reversing the split, at the user's request): the window package is dropped
  because item 70 removes Electron (281 MB of a 628 MB payload) for a native window of 13.8 MB, so
  there is nothing left for "nobody downloads what they do not use" to save. One package per OS with
  everything in it: one `.deb`, one MSI with no feature tree and no Burn bootstrapper, one DMG with
  one app bundle — package deal, no per-user choice to omit the window. The singleton unit,
  XDG-only autostart, one installed version, refused downgrades, the clean quit, and the frontend's
  version-mismatch refusal stay. The three split lines and the three "both in one go" lines leave
  the test list; four single-artifact lines take their place, and the upgrade and mismatch lines now
  name one artifact. Still spec-approved.

## Test list (approved 2026-11-04, amended 2026-11-14; behaviors only)

- [ ] Opening the browser frontend from the tray opens the OS default browser at the app, through the
      frontend's registered descriptor
- [ ] The tray lists exactly the installed frontends' descriptors and opens each; "Open" uses the
      user's default frontend; the browser, the window and the TUI are the three it lists
- [ ] The window frontend attaches to the running service (no second backend) and starts it when it is
      not running; closing the window leaves the service running; quitting the service is explicit and
      stops the engine cleanly
- [ ] Autostart (opt in) starts the service at login on each OS with the user-scoped mechanism (XDG
      autostart / LaunchAgent / HKCU Run) and produces no duplicate instance
- [ ] (e2e, packaged desktop) The window attaches to a running service: a task uploaded through the
      browser shows in the window and no second backend starts; closing the window leaves the service
      answering; with no service running, opening the window starts it; `stt-app quit` stops it with no
      backend, whisper or ffmpeg left behind (`desktop-tests/02`, `12` and `13` rewritten to the
      service model)
- [ ] Opening any frontend (browser, window or TUI) in app mode starts the unit - the service and the
      tray - and never a second service or a second tray; several frontend instances run side by side,
      closing them all leaves the unit up, quitting the tray stops the service too, and `stt-app quit`
      stops the tray (ADR 0064)
- [ ] The window is a singleton: opening it again (tray, or a second `stt-app open gui`) attaches to
      the running window and raises it instead of starting a second (ADR 0064)
- [ ] (basic) Each OS has one installer and it installs the whole app: the runner, the CLI, the
      tray, the backend, the JRE, the UI and the window, nothing left to download or add; after it,
      `stt-app open browser` starts the service and the tray, and `stt-app open gui` opens the
      window attaching to that service
- [ ] (basic) Linux: one `.deb` installs the app, a browser-only use launches nothing else, and
      removing the package removes the whole app, the tray's window entry included
- [ ] (basic) Windows: one MSI installs the app with no feature to choose; the portable zip carries
      the same window
- [ ] (basic) macOS: one DMG's single app bundle holds the unit and the window, and dragging it to
      Applications installs both
- [ ] (must not break) The existing packaged suites stay green on the one package, and the browser
      and TUI paths are unchanged
- [ ] (basic) Installing a newer version replaces the installed one in place on each OS (the `.deb`,
      the MSI, the app bundle): exactly one version is installed afterwards, and the user's settings
      and database survive, migrated by the new version
- [ ] (edge) An older installer refuses to replace a newer installed version, names the installed
      version, and changes nothing
- [ ] (edge) An upgrade quits a running unit cleanly before replacing files (no backend, whisper or
      ffmpeg left behind) and starts nothing; the next `stt-app open` starts the new version
- [ ] (edge) A frontend refuses to start when its version differs from the service's, naming both
      versions; with matching versions it attaches
- [ ] (must not break) Reinstalling the same version repairs in place and keeps the user's data

## e2e stance

The item's ADR 0046 e2e test is its packaged-desktop journey line: the window attaches to a running
service, uploads made in the browser show in the window, and `stt-app quit` leaves nothing behind.
