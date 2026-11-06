# 89 — The service package: the runner, the CLI and the tray, installed together

Status: spec-approved
Depends on: [66](../docs/specs/local-service-and-frontends.md) (the unit this package ships)
Blocks: line 4 of item 66's test list (the window attaching to a running service, proven in CI)

## Goal

Per OS, a **service package** that installs everything a `stt-app` command needs to run the unit —
the `stt-service` runner (the backend in app mode), the `stt-app` CLI, the `stt-tray` binary built
with its `tray-menu` feature, the backend's installDist, the JRE and the built UI, all beside each
other, so `stt-app open browser` starts the service and the tray with no environment variable
pointing at anything else ([ADR 0064](../docs/decisions/0064-service-and-tray-are-one-unit.md)) —
and a separate **window package** that depends on it, so the window stays optional.

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

## Decided (consultation, 2026-11-04)

- **The unit is one singleton, and every frontend goes through it** (the user): the service and the
  tray behave as one unit (ADR 0064); the browser, the window and the TUI talk to that unit and never
  around it.
- **The window is optional, and nobody downloads what they do not use** (the user): installing the
  unit without the GUI window must be possible, and a user who wants only the browser (or the TUI)
  does not download the window. So each OS gets **two artifacts** — the service package (runner,
  CLI, tray, backend, JRE, UI) and the window package, which depends on the service package — not
  one installer with a selector, which would still ship everything.
- **Linux: two `.deb`s with `Depends`** (the user): `stt-service` and the window package depending on
  it; the Nix side mirrors the split (a service package, and the desktop package depending on it).
- **No systemd unit; XDG autostart only** (the user): item 66's model stays — the autostart entry
  (off until `stt-app autostart on`) starts the unit and the tray supervises the service.
- **Future extension, not this item** (the user): a systemd mode in which systemd alone supervises
  the service, instead of the tray.
- **macOS: two DMGs, the window checks** (the user): the service DMG holds the service's app bundle
  (the tray is its visible app; the CLI is linked onto `PATH` on first run), and the window DMG's app
  refuses to start without the service and says how to install it. Same drag-to-Applications install
  as today's DMG; no `.pkg` (it would need an installer signing identity ADR 0060 does not have).
- **Both in one go, when the user chooses** (the user, accepting the root agent's recommendation):
  the two artifacts stay the base, and each OS also offers installing both from one action without
  downloading the window unless it is chosen. Linux needs no new artifact: one `apt install` of both
  `.deb`s (or `apt install` of the window package from an APT repository, which pulls the service
  through `Depends`), documented. Windows gets a small WiX Burn bootstrapper (`stt-setup.exe`) with
  an "also install the window" choice, whose packages download on demand from the same release as
  both MSIs. macOS gets a "Get the window…" action in the tray that downloads and installs the
  window DMG of the same release version as the running service.
- **One installed version at a time** (the user): a newer installer replaces the installed version
  in place — stable package names (`.deb`), stable UpgradeCodes with a major upgrade (both MSIs and
  the Burn bootstrapper), the same app-bundle paths (the two macOS apps) — and the user's data
  directory is left alone, so settings and the database survive and the new backend migrates them on
  its first start. Out of scope: the Windows portable zips (not installers) and copies of a macOS app
  kept outside its install location.
- **Downgrades are refused** (the user): an older installer stops with a message naming the
  installed version and changes nothing; going back means uninstalling first. A database a newer
  version has migrated is never handed to an older backend.
- **An upgrade quits the running unit and starts nothing** (the user): the installer runs a clean
  `stt-app quit` (no backend, whisper or ffmpeg left behind) before replacing files — Windows locks
  running files anyway — and the next `stt-app open` starts the new version.
- **The two artifacts are locked to one release** (the user): the window requires the service at
  exactly its own version (Linux `Depends: stt-service (= X.Y.Z)`, the window MSI's launch condition,
  the macOS window's check, the bootstrapper installing one release of both), so upgrading one
  upgrades both; and **a frontend refuses to start when its version and the service's differ**,
  naming both versions.

## Proposal

1. **Contents of the service package**: `bin/stt-service` (a small runner that execs the backend
   with the packaged JRE in app mode), `bin/stt-app`, `bin/stt-tray` (with `tray-menu`),
   `backend/lib` (Gradle `installDist`), `jre`, `ui` (the built frontend). **The window package**
   carries the Electron window only and depends on the service package: Linux `stt-service.deb` +
   the window `.deb` (`Depends: stt-service`) and the matching Nix packages; Windows two MSIs, the
   window's with a launch condition requiring the service product, plus the portable zips; macOS two
   DMGs, the window app refusing to start without the service.
2. **Registration**: the package registers its installed frontends' descriptors (browser always
   needs none) and ships the autostart entry, off until `stt-app autostart on`.
3. **CI**: a job per OS that installs this package beside the window package, runs
   `stt-app open browser --print-url` to bring the unit up and then the packaged desktop spec that
   asserts the window attaches, one backend runs, and closing the window leaves the service up.
4. **Not in this item**: the tray's icon on a headless runner (proven virtual), and the TUI (item 67).
5. **Both in one go**: the documented single `apt install` on Linux; the Burn bootstrapper on
   Windows, downloading only the chosen MSIs from the pinned release; the tray's "Get the window…"
   action on macOS, fetching the window DMG of the service's own release version.
6. **Upgrades**: in-place replacement on every installer, refused downgrades, the clean quit before
   replacing files, and the exact version lock between the two artifacts with the mismatch refusal.
   The release version itself comes from item 78 (`VERSION`); the tests build two versions of the
   artifacts to prove the upgrade and the refusal.

## Open questions

None. (Settled 2026-11-04, see "Decided (consultation, 2026-11-04)".)

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

## Test list (approved 2026-11-04; behaviors only)

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
- [ ] (basic) Installing only the service package gives a working unit:
      `stt-app open browser` starts the service and the tray, no window is downloaded or installed,
      and the tray lists only the installed frontends
- [ ] (basic) Installing the window package requires the service package (Linux `Depends`, the
      Windows MSI's launch condition; on macOS the window app refuses to start without the service and
      says how to install it) and the window attaches to the unit
- [ ] (edge) Removing the window package leaves the unit working; removing the
      service package is blocked by, or removes, the window package
- [ ] (must not break) The existing packaged suites stay green on the split
      artifacts, and the browser and TUI paths are unchanged
- [ ] (basic) Linux: one documented `apt install` installs both packages together, and installing
      only the service package installs no window
- [ ] (basic) Windows: the bootstrapper installs the service, and the window too when chosen; when
      the window is not chosen it is neither downloaded nor installed, and both come from the same
      release
- [ ] (basic) macOS: the tray's "Get the window…" downloads and installs the window of the running
      service's release version, and the window then attaches to the unit; without that action no
      window is downloaded
- [ ] (basic) Installing a newer version replaces the installed one in place on each OS (the `.deb`s,
      both MSIs and the bootstrapper, the two DMG apps): exactly one version is installed afterwards,
      and the user's settings and database survive, migrated by the new version
- [ ] (edge) An older installer refuses to replace a newer installed version, names the installed
      version, and changes nothing
- [ ] (edge) An upgrade quits a running unit cleanly before replacing files (no backend, whisper or
      ffmpeg left behind) and starts nothing; the next `stt-app open` starts the new version
- [ ] (edge) A frontend refuses to start when its version differs from the service's, naming both
      versions; with matching versions it attaches, and upgrading one artifact upgrades both
- [ ] (must not break) Reinstalling the same version repairs in place and keeps the user's data

## e2e stance

The item's ADR 0046 e2e test is its packaged-desktop journey line: the window attaches to a running
service, uploads made in the browser show in the window, and `stt-app quit` leaves nothing behind.
