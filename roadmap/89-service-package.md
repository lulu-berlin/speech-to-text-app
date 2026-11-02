# 89 — The service package: the runner, the CLI and the tray, installed together

Status: draft
Depends on: [66](../docs/specs/local-service-and-frontends.md) (the unit this package ships)
Blocks: line 4 of item 66's test list (the window attaching to a running service, proven in CI)

## Goal

One per-OS artifact that installs everything a `stt-app` command needs to run the unit: the
`stt-service` runner (the backend in app mode), the `stt-app` CLI, the `stt-tray` binary built with
its `tray-menu` feature, the backend's installDist, the JRE and the built UI — all beside each other,
so `stt-app open browser` starts the service and the tray with no environment variable pointing at
anything else ([ADR 0064](../docs/decisions/0064-service-and-tray-are-one-unit.md)).

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
  (`e2e/desktop-tests` has no such spec because there is no service to start).
- **Item 66's line 4 is written against this**: "the window attaches to a running service … and
  closing the window leaves the service running" needs this package in the CI job that runs the
  packaged specs (`.github/workflows/ci.yml`: `desktop-e2e`, `macos-dmg`, `windows-desktop-zip`).

## Proposal

1. **Contents**: `bin/stt-service` (a small runner that execs the backend with the packaged JRE in
   app mode), `bin/stt-app`, `bin/stt-tray` (with `tray-menu`), `backend/lib` (Gradle
   `installDist`), `jre`, `ui` (the built frontend). Linux: `.deb` and the Nix package; macOS:
   `.pkg` or the DMG layout used for the window; Windows: `.zip` and the MSI's service files.
2. **Registration**: the package registers its installed frontends' descriptors (browser always
   needs none) and ships the autostart entry, off until `stt-app autostart on`.
3. **CI**: a job per OS that installs this package beside the window package, runs
   `stt-app open browser --print-url` to bring the unit up and then the packaged desktop spec that
   asserts the window attaches, one backend runs, and closing the window leaves the service up.
4. **Not in this item**: the tray's icon on a headless runner (proven virtual), and the TUI (item 67).

## Open questions

- Should the window package depend on this one (one installer for both), or stay separate as the
  desktop spec assumes?
- Linux: one `.deb` for both artifacts, or two packages?
- Does the app-mode backend need its own systemd user unit on Linux, or is the autostart entry
  enough (the tray supervises it either way)?

## Approval log

- 2026-11-02 (workspace): drafted while finishing roadmap item 66, from its proposal 6 and the
  missing CI proof of its line 4. The test list is written with the user when this item is approved.

## Test list (moved here from item 66's review, 2026-11-02; approve with the spec)

- [ ] Opening the browser frontend from the tray opens the OS default browser at the app, through the
      frontend's registered descriptor
- [ ] The tray lists exactly the installed frontends' descriptors and opens each; "Open" uses the
      user's default frontend; uninstalling a frontend removes it from the tray; the tray works in an
      install with no GUI flavor (browser and/or TUI only)
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
