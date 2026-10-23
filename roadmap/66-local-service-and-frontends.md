# 66 — A per-user local service with selectable frontends (browser, window, TUI; tray optional)

Status: spec-approved
Depends on: [48](../docs/specs/macos-support.md), [49](49-windows-support.md), [64](../docs/specs/tauri-evaluation.md) —
all **done** before this item starts (the user 2026-10-19): they bring macOS/Windows support and
packaging/CI and the shell choice, so the login/tray/terminal mechanics this item adds become
testable on all three OSes.
Blocks: [67](67-tui-frontend.md) (the TUI is one of this item's frontends)

## Goal

Processing becomes one per-user local service that owns the database, the queue and model/tool
provisioning; frontends are clients of it. The app can be used from the browser, a window (the shell
chosen in item 64) and a TUI; a tray is an optional launcher/supervisor. The user installs the
frontends they want; each is registered with the service, the user sets a default, and the tray can
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
- ADR [0059](../docs/decisions/0059-os-shell-stays-electron.md) keeps Electron for the window; the
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
  **48763**, settled in the first cycle.
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

- [ ] (basic) One service per user: a second launch attaches to the running instance instead of
      starting another; clients read the durable port and secret from `config.json` and the running
      instance's actual endpoint from `runtime.json`
- [ ] (basic) The browser frontend opens from the tray in the OS default browser; the TUI opens in
      a terminal (Windows: PowerShell default; macOS: Terminal; Linux: the terminal chain), each via
      its registered descriptor
- [ ] (basic) The tray lists exactly the installed frontends' descriptors and opens each; "Open"
      uses the user's default frontend; uninstalling a frontend removes it from the tray; the tray
      works in an install with no GUI flavor (browser and/or TUI only)
- [ ] (basic) The window frontend attaches to the running service (no second backend) and starts it
      when it is not running; closing the window leaves the service running; quitting the service is
      explicit and stops the engine cleanly
- [ ] (basic) Autostart (opt in) starts the service at login on each OS with the user-scoped
      mechanism (XDG autostart / LaunchAgent / HKCU Run) and produces no duplicate instance
- [ ] (edge) A taken default port falls back to a free one, the durable chosen port updates, and
      the tray/`stt-app open` use the endpoint from the runtime file
- [ ] (edge) After a logout/reboot clears the ephemeral runtime directory, the service starts on the
      durable port, the next runtime file advertises it, and no secret was stored ephemerally
- [ ] (edge) A malformed or unknown-kind descriptor is skipped with a logged warning while the
      valid descriptors stay listed
- [ ] (security) The service refuses requests with a foreign `Host`/`Origin` or without a
      credential; the browser works through the one-time launch token's HttpOnly cookie (SSE
      included) and the TUI through the durable secret as a header; the secret appears in no URL,
      log, world-readable file or ephemeral file
- [ ] (CI per OS) Each OS leg proves its mechanics: macOS LaunchAgent + menu bar, Windows autostart
      + tray + PowerShell TUI, Linux autostart + AppIndicator (or the documented GNOME fallback)
- [ ] (must not break) The existing server, Docker, desktop and e2e paths stay green on the platforms
      they already cover; the three Linux desktop artifacts keep their packaged e2e

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
