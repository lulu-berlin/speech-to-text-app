# 66 — A per-user local service with selectable frontends (browser, window, TUI; tray optional)

Status: done (roadmap item 66, approved 2026-10-22)
Depends on: [48](macos-support.md), [49](windows-support.md), [64](tauri-evaluation.md) —
all **done** before this item starts (the user 2026-10-19): they bring macOS/Windows support and
packaging/CI and the shell choice, so the login/tray/terminal mechanics this item adds become
testable on all three OSes.
Blocks: [67](../../roadmap/67-tui-frontend.md) (the TUI is one of this item's frontends)

## Goal

Processing becomes one per-user local service that owns the database, the queue and model/tool
provisioning; frontends are clients of it. The app can be used from the browser, a window (the shell
chosen in item 64) and a TUI, plus a tray that in app mode belongs to the app: the service and
the tray are one unit - both up or both down ([ADR 0064](../decisions/0064-service-and-tray-are-one-unit.md)).
The user installs the frontends they want; each is registered with the service, the user sets a
default, and the tray can
open any installed one. It works on Linux, macOS and Windows with each OS's own login, tray and
terminal mechanics. The tray and the service ship without any GUI frontend, and the tray is not
tied to the GUI shell.

## Evidence

- The backend already serves the React UI and the HTTP/SSE API
  (`docs/architecture.md:9,39-41,172-191`); `nix run .` **serves** the browser UI at
  `http://localhost:8080` — the user opens the URL; it does not launch a browser
  (`docs/specs/nix-flake-package.md:32`, `README.md:55`).
- Today's Electron desktop starts a private backend on a free port and stops it with the window
  (`docs/specs/electron-desktop-app.md:53-57`; ADR 0055); it is a sidecar, not a service.
  `desktop/src/main.ts:66` asks `freePort()` on every launch, so the URL changes each start.
- `Config.kt:12-13,55`: `STT_HOST` defaults to `0.0.0.0`; only the desktop sets `127.0.0.1`.
  The backend has no CORS/origin middleware and no authentication (`grep` over
  `backend/src/main/kotlin/stt/http`: no `Origin`, CORS or credential code).
- The frontend uses relative URLs and `EventSource`: `frontend/src/shared/api/client.ts` fetches
  `/api/…` and opens `new EventSource('/api/events')`. A custom header on every request is
  impossible for `EventSource`; the browser credential must therefore be a cookie (or SSE must move
  to a fetch-based reader).
- The settings row holds device, threads, upload defaults and the device-fallback notice only
  (`db/SettingsRepository.kt:37-43`) — there is no default-frontend or terminal setting.
- ADR [0059](../decisions/0059-os-shell-stays-electron.md) keeps Electron for the window; the
  user is considering another cross-platform GUI framework later, so the tray must not depend on
  it.
- No autostart, tray or service-manager code or decision exists in the repo; item 16 explicitly
  excluded a NixOS module/systemd service (`docs/specs/nix-flake-package.md:36,43`).
- Items 48/49 bring macOS/Windows app support and packaging; neither has a service/tray model yet.
- Platform mechanics (surveyed 2026-10-19):

| Concern | Linux | macOS | Windows |
| --- | --- | --- | --- |
| Start at login (user-scoped) | XDG autostart `~/.config/autostart/*.desktop`; `systemd --user` optional and not universal | `~/Library/LaunchAgents` agent (`RunAtLoad`/`KeepAlive`), or the macOS 13+ login-item API | `HKCU\…\CurrentVersion\Run` or a Startup-folder shortcut (Task Scheduler for delay/restart) |
| Background-process scope | per-user process, no root | per-user LaunchAgent, no root | per-user process — Windows services run in session 0 and cannot show UI |
| Tray API | StatusNotifierItem/AppIndicator over D-Bus; GNOME shows it only with the AppIndicator extension; XEmbed is deprecated | `NSStatusItem` (menu bar); `LSUIElement` hides the Dock icon | `Shell_NotifyIcon`; tray-only means no taskbar entry |
| Durable vs ephemeral state | durable port + per-install secret in the private config dir (`$XDG_CONFIG_HOME/stt-app`, 0600); ephemeral PID + actual endpoint in `$XDG_RUNTIME_DIR/stt-app` (cleared at logout/reboot) + lock | durable in `~/Library/Application Support/stt-app`; ephemeral PID/endpoint in the per-user temp dir | durable in `%APPDATA%\stt-app` (config); ephemeral in `%LOCALAPPDATA%\Temp\stt-app`; named mutex/pipe |
| Data / models / logs | XDG directories | `~/Library/{Application Support,Caches,Logs}` | `%APPDATA%` (config) / `%LOCALAPPDATA%` (data, DB, models) |
| Browser launch | `xdg-open URL` (default browser) | `open URL` (default browser) | the shell's default-browser association |
| Terminal launch | no standard default-terminal API: chain `$TERMINAL`, `xdg-terminal-exec`, `x-terminal-emulator`, common emulators | `open -a Terminal` launches Terminal.app explicitly (no OS default-terminal API; another terminal is a configured override) | spawn PowerShell (the user's choice) and let the configured console host — Windows Terminal when set, else ConHost — draw the window; `wt` is an override, not the default |
| Unsigned friction | none | Gatekeeper first-run approval; an unsigned login agent can be blocked until approved | SmartScreen warning on first run |

## Decided (consultation, 2026-10-19)

- **The service owns the app state and the work** (the user): processing, the database and
  model/tool provisioning live in one per-user service; the browser, the window and the TUI are
  clients.
- **Flavors** (the user): webview-app, browser and TUI; each with tray or no tray. The tray menu can
  open any installed frontend; a default frontend is user-settable.
- **Sequence after 48, 49 and 64** (the user): this item starts only when those are done — they
  establish platform support, packaging/CI and the shell choice. The login/tray/terminal mechanics
  are added *here* and become testable on all three OSes at that point; no half-verified platform
  legs.
- **Endpoint and durable state** (the user; corrected 2026-10-19 after review): a default port, with
  a fallback to a free port when it is taken. The default/chosen port and the per-install secret are
  **durable** in the private config/data directory; only the running instance's PID and actual
  endpoint live in an **ephemeral** runtime file, because `$XDG_RUNTIME_DIR` and its analogues are
  cleared at logout/reboot. 8080 is rejected as the default because it is popular; proposal:
  **48763**, settled in the first cycle. **Settled 2026-10-29 (the user): 28763** — unassigned at
  IANA (28590–28999) and below Linux's default ephemeral range (32768–60999) and the Windows/macOS
  dynamic range (49152+), so no outgoing client socket takes it by chance; 48763 lies inside
  Linux's ephemeral range, where a transient socket would trigger the re-persisting fallback.
- **Frontend launch from the tray** (the user; wording corrected 2026-10-19): the browser flavor
  opens in the OS default browser. The TUI opens in a terminal through a per-OS **launcher plus a
  user override** — not a claimed OS "default terminal" API (none exists on macOS or Linux, and
  PowerShell is a shell, not a terminal host): Windows spawns PowerShell and the configured console
  host (Windows Terminal when set) draws it; macOS runs `open -a Terminal`; Linux uses the chain
  below.
- **Frontends are explicit launch descriptors, not PATH probes** (review 2026-10-19): each
  installed frontend registers a descriptor — `browser` (URL), `gui` (executable and arguments) or
  `terminal` (command) — written by its package/installer into a per-user registry; the tray reads
  the registry, and the default frontend is a setting. GUI login/tray processes do not inherit the
  shell PATH on macOS/Windows, and a TUI needs a terminal host.
- **Trust boundary** (review 2026-10-19): the service binds loopback only, validates `Host` and
  `Origin` (cross-site requests and DNS rebinding), and requires a per-install credential kept in
  the **durable** private file (0600 on POSIX, a user-only ACL on Windows); the ephemeral runtime
  file may reference it but never holds the secret. A random or stable localhost port alone is not
  a boundary. The browser gets the credential in the tray's launch URL; the TUI reads the durable
  file.
- **Item 64's scope is unchanged** (the user): its spike evaluates the embedded-shell role only;
  its ADR feeds the tray-host choice here (the chosen shell in tray-only mode, or a native helper).

## Decided (consultation, 2026-10-22)

- **Port conflict** (the user): the durable port is tried first; when a foreign program holds it,
  the service takes a free port, **re-persists** it and advertises the actual endpoint in the
  runtime file. The tray and `stt-app open` read the runtime file, so they always use the live URL;
  only an old browser bookmark can go stale.
- **Bootstrap state is files, not SQLite** (the user's question, settled): the durable
  `config.json` in the private config dir (0600 / user ACL) holds the chosen port and the
  per-install secret; the ephemeral `runtime.json` in the per-user runtime dir (0700) holds the PID
  and the actual endpoint; an OS lock file next to it enforces one instance per user. The database
  is the service's own state and holds app settings only (default frontend, terminal command,
  device, threads, …). Rationale: clients must read the port and secret *before* they can talk to
  the service; "a process is alive" is not durable DB state (stale rows survive crashes), and the
  single-instance lock must be an OS lock anyway; keeping bootstrap data out of the DB means
  `stt-app open` can start the service while it migrates the schema and the tray/TUI never open
  SQLite.
- **Browser credential: one-time token → HttpOnly cookie** (the user): `stt-app open` and the tray
  open `http://127.0.0.1:<port>/open?t=<single-use token, seconds TTL>`; the service validates the
  token, sets an `HttpOnly`, `SameSite=Strict` cookie and redirects to the app. The per-install
  secret itself never appears in a URL, a log or browser history; the TUI and the tray send it as
  an `Authorization` header. The frontend's relative fetches and `EventSource` are unchanged.
  Supersedes the 2026-10-19 wording "the browser gets the credential in the tray's launch URL".
- **Standalone Rust tray** (the user): a small Rust binary using the cross-platform tray backends
  (`tray-icon`/`muda`: `Shell_NotifyIcon`, `NSStatusItem`, StatusNotifierItem). It ships without
  any GUI flavor, lists/opens any registered frontend, and is unaffected by a later Electron
  replacement. GNOME without AppIndicator degrades to no icon, with a notification and
  `stt-app open` still working. This supersedes "the item-64 shell in tray-only mode": Electron is
  the window flavor only.
- **The Rust pin starts here**: the tray is the repo's first Rust artifact, so this item establishes
  the pin (`versions.json` + `rust-toolchain.toml`, a committed `Cargo.lock`, a sync check like
  `versions-sync`) and the non-Nix bootstrap/CI path for it; item 67's TUI reuses it.
- **Default frontend lives in the service settings row** (the user): one user preference shared by
  browser, window, TUI and tray, exposed by the settings API and editable on the Settings page
  (schema migration); the registry stays install-time data.
- **The terminal override is a settings field** (`terminalCommand`, empty = the OS chain), because
  the tray/service — not the TUI — launches the terminal.
- **The window attaches** (the user): the Electron window becomes a `gui` frontend that attaches to
  the running service and **starts it if it is not running**; closing the window leaves the service
  running, and quitting is explicit (tray or `stt-app quit`). The desktop packages install the
  service alongside the window.
- **Descriptor registry**: JSON files in the per-user config dir (`…/stt-app/frontends`), one per
  frontend, `{version, id, kind: browser|gui|terminal, name, command?, url?, args?}`, 0600 / user
  ACL, written by installers and removed on uninstall. A malformed or unknown-kind file is skipped
  with a logged warning.
- **Unsigned is accepted** (the user): no signing in this item; the first-run approvals (macOS
  LaunchAgent/Gatekeeper, Windows SmartScreen) are documented.
- **Items 48/49's packaged behavior is adapted here**: their specs stay historical; this item
  updates the packaged e2e, README and `docs/architecture.md` to the service model.
- **Test list** approved as worded in this file.

## Decided (workspace, 2026-10-29)

- **Two modes of one backend** (the user): **dev/server mode** — today's env-configured start
  (`STT_HOST` 0.0.0.0, `STT_PORT` 8080, no credential) — stays unchanged for development and the
  existing server paths (`devenv up`, `./gradlew run`, Docker); **app mode** is the per-user
  service centred on the tray and the frontends: the lock, `config.json`/`runtime.json`, port
  28763, loopback, `Host`/`Origin` checks and the credential. The e2e suite (E2) runs against app
  mode through the handshake.
- **The client commands are Rust** (the user): one Cargo workspace — a client crate (the files,
  start-or-attach, launch descriptors, the terminal chain) shared by the tray and a `stt-app` CLI
  (`open`, `quit`); the CLI starts the JVM service; the window's attach uses the same CLI rather
  than a TypeScript copy.
- **`stt-app` finds the backend runner beside it** (the user, 2026-10-31): the packaged layout puts
  the `stt-app` binary in the same directory as the command that runs the JVM service, and
  `stt-app` starts the service through that sibling without an environment variable. No fixed
  default path and no `STT_SERVICE_CMD`.
- **Autostart is the OS entry only, for now** (the user, 2026-10-31): `stt-app autostart on|off`
  writes or removes the user-scoped login entry, and the entry itself is the opt-in state. The
  Settings page toggle is deferred until the tray exists, since the service cannot apply the setting
  (it does not know where the runner lives).
- **The service and the tray are one unit in app mode** (the user, 2026-10-31): both are up or
  both are down, not necessarily as one process. Opening any frontend starts the unit; quitting the
  tray or `stt-app quit` stops it. Either end may restart the other when it dies, and when that
  restart fails the survivor stops too. A tray with no tray host runs virtually (process up, no
  icon) for shell-session users. Dev mode (`devenv up`, the harness's service-only start)
  is exempt. Recorded as [ADR 0064](../decisions/0064-service-and-tray-are-one-unit.md);
  supersedes "a tray is an optional launcher/supervisor" and the wording below that the frontends'
  own launch starts only the tray.
- **A client tells a live service from a leftover by asking it** (the user, 2026-10-31): a client
  treats `runtime.json` as current only when its endpoint answers `GET /api/health` with `200` to
  the per-install secret; a refused connection or any other answer means no service runs. The
  instance lock is not read from Rust: the service takes it through Java's `FileChannel.tryLock`
  (`fcntl` on Unix), which Rust's `File::try_lock` (`flock`) does not see on Linux (checked in the
  workspace). A hung service that still holds the port counts as none; a second launch then
  defers to it through the lock (item 1), so the outcome is a failed open, never two services.

## Proposal

1. Lifecycle: one service instance per user with an OS lock, a durable `config.json` (port, secret)
   and an ephemeral `runtime.json` (PID, endpoint); autostart is an opt-in setting implemented per
   OS with the user-scoped mechanisms above; on-demand start via the tray, a window or
   `stt-app open` always works.
2. Endpoint: try the durable port, fall back to a free port, re-persist it and publish the actual
   endpoint; the tray, `stt-app open` and the window read the runtime file.
3. Frontend registry: installed descriptors in the per-user config dir; the service exposes the
   list and serves the default-frontend setting (a settings column, migration included).
4. Tray: the standalone Rust tray builds/packages on Linux, macOS and Windows, reads descriptors,
   opens any frontend, pauses/resumes the queue, shows status, quits the service and toggles
   autostart; it works with no GUI flavor installed.
5. Security: loopback bind, `Host`/`Origin` validation, the one-time-token → HttpOnly-cookie
   handshake for the browser, the secret as an `Authorization` header for the TUI/tray, durable
   files 0600 / user ACL, ephemeral files holding no secret.
6. Packaging per OS: the service package includes the tray and the autostart entry; frontends are
   separate artifacts (browser needs none, the window is the Electron app, the TUI is item 67).
7. Record the service model, the trust boundary, the launch model and the tray choice as an ADR.

## Open questions

None. (All settled 2026-10-22, see "Decided (consultation, 2026-10-22)".)

## Test list (approved; behaviors only)

- [x] (basic) One service per user: a second launch attaches to the running instance instead of
      starting another; clients read the durable port and secret from `config.json` and the running
      instance's actual endpoint from `runtime.json`
- [x] (edge) A taken default port falls back to a free one, the durable chosen port updates, and
      the tray/`stt-app open` use the endpoint from the runtime file
- [x] (edge) After a logout/reboot clears the ephemeral runtime directory, the service starts on the
      durable port, the next runtime file advertises it, and no secret was stored ephemerally
- [x] (edge) A malformed or unknown-kind descriptor is skipped with a logged warning while the
      valid descriptors stay listed
- [x] (security) The service refuses requests with a foreign `Host`/`Origin` or without a
      credential; the browser works through the one-time launch token's HttpOnly cookie (SSE
      included) and the TUI through the durable secret as a header; the secret appears in no URL,
      log, world-readable file or ephemeral file
- [x] (CI per OS) Each OS leg proves its mechanics: macOS LaunchAgent + menu bar, Windows autostart
      + tray + PowerShell TUI, Linux autostart + AppIndicator (or the documented GNOME fallback)
      — CI run #505 (`41502699347`) green job by job: `cargo fmt, clippy, test` and `gradle check` on all
      three OSes, `gradle integrationTest` on Linux and macOS, and `Windows (packaged zip e2e)`,
      `macOS (DMG + packaged e2e)`; the macOS LaunchAgent and HKCU Run specs run only on their own OS,
      and the tray icon itself is not observable headlessly
- [x] (must not break) The existing server, Docker, desktop and e2e paths stay green on the platforms
      they already cover; the three Linux desktop artifacts keep their packaged e2e
      — CI run #505: `Linux (e2e)` 12:33, `macOS (e2e)` 19:05, `Windows (e2e)` 15:14 (both item-66
      app-mode specs in each), `Linux (packaged .deb e2e)`, `Linux (bundle e2e)`, `Linux (flake package
      e2e)`, `Windows (packaged zip e2e)`, `macOS (DMG + packaged e2e)`, `Linux (Nix build)`,
      `Linux (Nix flake check)`, `Linux (Nix devenv shell)`, `macOS (Nix build + devenv)`, and
      `gradle check` + `pnpm test` on all three
- [x] (e2e) The browser enters through the one-time launch URL that `stt-app open` produces: the
      app loads, an upload reaches Completed through the live event stream, and the cookie is not
      readable from page script; reusing the same launch URL, a fresh browser without the cookie,
      and a page on a foreign origin all get no app data
- [x] (e2e) The two item-66 specs run the service in app mode and enter through the launch-token
      handshake (the journey itself still runs against a dev-mode backend, which is what
      `applyE2eEnv` sets; the app-mode journey is unplanned and recorded below)
- [x] (e2e) The Settings page lists exactly the registered frontends as default-frontend choices
      and keeps the chosen default and the terminal command after a reload
- [x] (discovered) The survivor restarts a dead sibling, and when that restart fails the survivor
      stops, so the pair is never left half up (ADR 0064)
- [x] (discovered) With no tray host the tray still starts, supervises the service and shows no
      icon, and the app works (ADR 0064)
- [x] (discovered) When the tray cannot start, the service the same launch just started stops too,
      and the open fails naming the tray (ADR 0064)
- [x] (discovered) A sibling that keeps dying right after each restart is restarted at most 3 times
      within 60 seconds; the next death in that window counts as a failed restart, so the survivor
      stops too (ADR 0064)
- [x] (discovered) With no port configured, the first app-mode start serves on the default port
      28763
- [x] (discovered) Without directory overrides, the service keeps its per-user state in each OS's
      per-user directories from the Evidence table (config, runtime)
- [x] (discovered) A clean stop removes `runtime.json`; a `runtime.json` left by a crash is ignored
      by clients (its endpoint does not answer `GET /api/health` with `200` to the per-install
      secret)
- [x] (discovered) A `config.json` the service cannot read (for example cut short by a crash while
      it was written) is replaced at the next start with the default port and a new secret, and
      clients use the new one
- [x] (discovered) When another program takes the durable port in the instant between the service's
      check and its bind, the start still falls back to a free port
- [x] (discovered) A browser that reaches app mode without the cookie (an old bookmark) is shown a
      page that opens the app from the tray or `stt-app open`, not the bare `401 unauthorized` JSON
- [x] (discovered) The service reads the same frontend registry for the Settings page's
      default-frontend choices, and skips a malformed or unknown-kind descriptor the same way the
      tray and `stt-app` do
- [x] (discovered) On macOS the TUI opens in Terminal with its arguments too (`open -a Terminal`
      cannot pass arguments to the program it opens)
- [x] (discovered) The Linux terminal chain falls through `$TERMINAL`, `xdg-terminal-exec`,
      `x-terminal-emulator`, `gnome-terminal`, `konsole` and `xterm` to the first one present; an
      empty `$TERMINAL` counts as unset
- [x] (discovered) A non-empty `terminalCommand` setting replaces the OS terminal launcher on every
      OS
- [x] (discovered) The client (`stt-client`, so `stt-app` and the tray) resolves the same per-user
      directories as the service, both checked against one shared table of cases
      (`docs/service-dirs.json`); on macOS the runtime directory is the per-user temp dir
      (`$TMPDIR`), and on Linux an unset `$XDG_RUNTIME_DIR` falls back to `~/.cache/stt-app` with a
      warning
- [x] (discovered) The service writes `config.json` whole or not at all (a temporary file moved
      into place), so a crash while it is written leaves the previous file and its secret intact
      rather than a truncated one

## Acceptance criteria

- One per-user service owns state and processing on all three OSes; browser, window and TUI are
  clients; the tray is a standalone Rust binary that works without a GUI flavor; installed
  frontends are listed and the default is user-set.
- The port and the per-install secret persist across logout/reboot in the private directory, with a
  default-port/free-port fallback that re-persists; the runtime file only advertises the running
  instance; the service is loopback-only, `Host`/`Origin`-validated and credential-protected (the
  browser via the one-time-token cookie, other clients via the header).
- Per-OS login, tray and terminal mechanics are implemented and verified by that OS's CI leg; the
  Rust pin and its sync check cover every path; the service model is recorded as an ADR.
- All test-list items ticked and green; user approved the diff.

## Approval log

- 2026-11-02: **in review on CI run #505** (`41502699347`, head `2d8c7ad`): all 31 jobs succeeded
  and `CI (all checks)` is green. That run carries the credential fix (`dc104dbe`) that ended the
  app-mode e2e timeouts, and its e2e legs ran both item-66 specs in app mode on Linux, macOS and
  Windows. Lines 10 and 11 are ticked from it; the list now has every line closed (the eight moved
  ones live in items 67, 78 and 89).
- 2026-11-02: **root cause of the app-mode e2e timeouts**: `waitForHealth` accepted an `authorization`
  option and never sent it (the `http.get` options carried no `headers`), so every health poll against the
  service got `401` and the start sat out its whole timeout — the same on all three legs, in runs #500 and
  #504, with no service fault at all (the user's `curl` showed `401 … This request needs the service's
  credential` while the service answered on its advertised port). Fixed with a test that fails on the
  missing header. Locally, both item-66 specs then pass in 27.9 s against the launched service
  (`2 passed`), transcription through `Vulkan0` included.
- 2026-11-02: the app-mode e2e service starts from Gradle's `installDist` launcher, not `gradle run` at
  test time: CI run #500 (`41440118708`) showed `66-launch-token` timing out on both the Linux and the
  macOS leg (`backend on :49852 did not answer within 600000 ms`), because Gradle at test time (daemon and
  wrapper work under a loaded runner) outlasted the health wait. `startService` now runs the distribution's
  launcher (`backend/build/install/stt-backend/bin/stt-backend`, Gradle's `.bat` on Windows through
  `needsShell`) and builds the distribution once per run when it is missing; the launcher answered in
  about two seconds locally.
- 2026-11-02: **review of the in-review branch** (the user). Run #477 was not green: `CI (all checks)`
  failed, `Windows (cargo fmt, clippy, test)` timed out at 30 min and `Windows (e2e)` failed. The e2e
  harness started the app-mode service with `repoRoot/gradlew`, which Windows cannot spawn; `backendGradle`
  is now platform-shaped (`gradlew.bat` through `needsShell`, `gradle.bat` from the PATH probe) and has
  its own test. The journey itself still runs against a dev-mode backend, because `applyE2eEnv` sets
  `E2E_BACKEND_CMD`; the e2e line was reworded to what CI proves (the two item-66 specs run the service in
  app mode), and the app-mode journey stays unplanned. Eight unticked items moved out (the user's review,
  2026-11-02): the TUI opening to item 67; the tray's browser opening, tray listing, window attach,
  autostart, packaged-window attach, unit-up-on-open and window singleton to item 89; the version-drift
  replacement to item 78 - each there marked "moved from item 66" and pending that item's approval.
  `windows-client-check` gets a 60-minute timeout (a cold tray build does not fit in 30). Lines 10 and 11
  were un-ticked: they are re-ticked only from the next fully green CI run, cited by its number.
- 2026-10-19: filed as a draft by the root agent from the consultation on a localhost service and
  frontend flavors. Decisions recorded above (the user): service owns state; flavors and tray; start
  after 48/49/64; default port with free-port fallback persisted; browser/TUI launched from the tray
  (Windows PowerShell for the TUI); descriptors instead of PATH probing; loopback + Host/Origin +
  per-install credential. Test list proposed, not yet approved.
- 2026-10-19 (review corrections): durable/ephemeral state split — the runtime directory is cleared
  at logout/reboot, so the port and secret live in the private directory and the runtime file only
  advertises the running instance; terminal launch recorded as a per-OS launcher plus a user
  override (no OS default-terminal API; PowerShell is a shell, not a terminal host).
- 2026-10-22: consulted (main tree). The user settled the port-conflict fallback, the files-vs-DB
  split (the question: why not SQLite), the browser credential (a one-time token exchanged for an
  HttpOnly cookie — the secret itself only ever travels in headers or the 0600 file), the default
  frontend's home, the terminal override and the sidecar's fate (the window attaches and starts the
  service). The user replaced the tray host with a standalone, cross-platform **Rust** tray that
  ships without a GUI flavor, so this item establishes the Rust pin that item 67 reuses. Test list
  approved; item 3 now also states the no-GUI case and item 8 names the cookie/header split. Status:
  spec-approved; work starts after item 49 is done.
- 2026-10-29 (workspace, ADR 0046 gap): the user approved four e2e lines — the browser's
  launch-token handshake, the existing journey through that handshake with no credential-off
  switch, the Settings page's default frontend and terminal command, and the packaged window
  attaching to the service (desktop quit specs 02/12/13 rewritten to the service model). The tray
  and terminal launches stay with the per-OS CI item; the TUI itself is item 67. The user settled
  the default port as **28763** (see the 2026-10-19 Endpoint decision).
- 2026-10-29 (workspace, before cycle 1): the user split the backend into dev/server mode
  (unchanged) and app mode (the service), chose Rust for `stt-app open`/`quit` (shared with the
  tray), and added that launching the TUI or the window starts the tray — appended to the test
  list as `(discovered)`. See "Decided (workspace, 2026-10-29)".
- 2026-10-31 (workspace): the user replaced "the tray is optional" with one unit: in app mode the
  service and the tray are both up or both down (ADR 0064). A dead sibling may be restarted by the
  survivor, and if the restart fails the survivor stops too; a tray with no tray host runs virtually
  (process and lock up, no icon) for shell-session users; dev mode is exempt. The test list's
  frontend-launch line now asserts the unit, and three discovered lines were added: the unit's
  start/quit, the mutual restart and its failed-restart stop, and the virtual tray.
- 2026-10-31 (workspace, same consult): "optional" now means any particular frontend, not the tray.
  The app running means service and tray active; no frontend may be open (allowed at any moment);
  browser tabs and TUI instances are not singletons; closing a frontend or all of them never stops
  the unit. ADR 0064 amended; the unit's test-list line states it.
- 2026-10-31 (workspace, same consult): the `gui` frontend (the Electron window) is a singleton
  too - reopening attaches and raises it - while browser tabs and TUI instances stay multiple.
  ADR 0064 amended; added to the test list as `(discovered)`.
- 2026-10-31 (workspace, same consult): the tray takes no lock of its own - the unit's one
  singleton claim is the service's instance lock, and only the launch that started the service
  starts the tray (ADR 0064 corrected; the virtual-tray line no longer mentions a lock). Added as
  `(discovered)`: when the tray cannot start, the service the same launch just started stops too and
  the open fails naming the tray.
- 2026-10-31 (workspace, same consult): a crash loop counts as a failed restart - the survivor
  restarts a sibling at most 3 times within 60 seconds, then stops too (ADR 0064; added to the test
  list as `(discovered)`).
- 2026-10-31 (workspace, same consult): a launch of a different version replaces the running unit
  (quit the old service and tray, start the new ones), and a loaded frontend page reloads - recorded
  in ADR 0064 and on the test list as `(discovered, blocked on 78's version surface)`, with a digest
  of `versions.json` as the interim version identity.
- 2026-11-02 (workspace): the user verified the tray on a Linux desktop (GNOME, app mode via
  `gradle run --args=service` and `stt-tray` directly): the icon appears, the menu lists exactly the
  registered frontends plus Quit, "Open Browser" opens the app in the default browser through the
  one-time launch URL (the address bar shows `/` after the redirect), Quit stops the unit and the
  icon, a frontend registered while the tray runs appears in the menu within a second and disappears
  when its descriptor is removed, and `kill -9` on the tray is answered by the service starting a new
  one within a second. Not ticked, by the user's decision: items 2 and 3 name the TUI and the window
  as well, and the TUI is item 67's deliverable.
- 2026-11-02 (workspace): the unit line's browser half was verified on the Linux desktop: two
  `stt-app open browser --print-url` runs each got their own one-time token (two tabs), exactly one
  `stt-tray` ran for both, and closing both tabs left the tray and the service's endpoint intact.
  The same session verified the virtual tray: with `DISPLAY` and `WAYLAND_DISPLAY` unset the tray
  prints only "no display; supervising without an icon" (no icon), keeps supervising, and exits
  quietly — no "stops too" — when `stt-app quit` stops the service (`gradle run` ends BUILD
  SUCCESSFUL). Its restart of a dead service cannot be shown in a dev checkout (no `stt-service`
  runner beside it); that half is the process/unit tests' and a packaged install's.
- 2026-11-02 (workspace): the Linux autostart entry was verified on the desktop: `stt-app autostart
  on` writes `~/.config/autostart/stt-app.desktop` with `Exec="…/client/target/debug/stt-service"
  service`, `Terminal=false` and `X-GNOME-Autostart-enabled=true`, and `autostart off` removes the
  file. The login half cannot be shown from a checkout: the entry names the runner beside the binary,
  which only a packaged install carries, so it belongs to item 49's install and to the CI legs.

- 2026-11-02 (root agent, PR preparation): approved after the review. Run #504's red was a real
  harness bug — `waitForHealth` accepted the app-mode credential and dropped it, so every poll got
  `401` and the start timed out; `dc104dbe` sends it (a callback, the secret exists only after
  `config.json` is written) with a test that proves the 401/credential pair. `backendGradle` is
  platform-aware and resolves the shim Windows' PATH holds, and the Windows Rust job's budget is 60
  minutes; run #505 (`2d8c7ad7`) is green job by job (31 jobs; `Windows (e2e)` 15.2 min,
  `Windows (cargo fmt, clippy, test)` 1.1 min, `Linux (e2e)` 12.6 min, `macOS (e2e)` 19.1 min), and
  the run's per-spec durations show both app-mode specs passing on Linux and Windows (7/3 s and
  14/6 s). `e2e/repro-app-mode.ts` was added to investigate and deleted in `8dfe8255`, so the final
  tree is #505's tree minus that standalone script (nothing imports it); the evidence counted for
  this head is the pull request's own run on the squashed commit. Four list items moved to item 89
  (window attach, autostart, packaged window, window singleton) and the version-drift replacement to
  item 78. Local gate by exit code: `gradle check` 0; frontend `vitest run` 0 (38 files, 299 tests),
  `tsc` 0, prettier 0; e2e `tsc` 0, prettier 0, `node --test` 0 (43); docs `tsc`, prettier, links,
  names, diagram, exceptions 0, `node --test` 0 (33); `cargo fmt --check` 0. `cargo test`/clippy and
  the app-mode specs cannot run in the sandbox (network dependencies, `stt-app`), so CI is their
  evidence. The item's spec and final test list moved from
  `roadmap/66-local-service-and-frontends.md` to `docs/specs/local-service-and-frontends.md` with
  `Status: done`, the README row and graph node, item 78's row, and items 67, 70 and 89's
  dependencies; the branch is squashed to one commit on `main` (ADR 0050).
