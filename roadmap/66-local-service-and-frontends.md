# 66 — A per-user local service with selectable frontends (browser, window, TUI; tray optional)

Status: draft
Depends on: [48](48-macos-support.md), [49](49-windows-support.md), [64](64-tauri-evaluation.md) —
all **done** before this item starts (the user 2026-10-19): they bring macOS/Windows support and
packaging/CI and the shell choice, so the login/tray/terminal mechanics this item adds become
testable on all three OSes.
Blocks: —

## Goal

Processing becomes one per-user local service that owns the database, the queue and model/tool
provisioning; frontends are clients of it. The app can be used from the browser, a window (the shell
chosen in item 64) and a TUI; a tray is an optional launcher/supervisor. The user installs the
frontends they want; each is registered with the service, the user sets a default, and the tray can
open any installed one. It works on Linux, macOS and Windows with each OS's own login, tray and
terminal mechanics.

## Evidence

- The backend already serves the React UI and the HTTP/SSE API
  (`docs/architecture.md:9,39-41,172-191`); `nix run .` **serves** the browser UI at
  `http://localhost:8080` — the user opens the URL; it does not launch a browser
  (`docs/specs/nix-flake-package.md:32`, `README.md:55`).
- Today's Electron desktop starts a private backend on a free port and stops it with the window
  (`docs/specs/electron-desktop-app.md:53-57`; ADR 0055); it is a sidecar, not a service.
- `Config.kt:12-13,55`: `STT_HOST` defaults to `0.0.0.0`; only the desktop sets `127.0.0.1`.
  The backend has no CORS/origin middleware and no authentication.
- The settings row holds device, threads, upload defaults and the device-fallback notice only
  (`db/SettingsRepository.kt:37-43`) — there is no default-frontend setting.
- No autostart, tray or service-manager code or decision exists in the repo; item 16 explicitly
  excluded a NixOS module/systemd service (`docs/specs/nix-flake-package.md:36,43`).
- Items 48/49 bring macOS/Windows app support and packaging; neither has a service/tray model yet.
- Platform mechanics (surveyed 2026-10-19):

| Concern | Linux | macOS | Windows |
| --- | --- | --- | --- |
| Start at login (user-scoped) | XDG autostart `~/.config/autostart/*.desktop`; `systemd --user` optional and not universal | `~/Library/LaunchAgents` agent (`RunAtLoad`/`KeepAlive`), or the macOS 13+ login-item API | `HKCU\…\CurrentVersion\Run` or a Startup-folder shortcut (Task Scheduler for delay/restart) |
| Background-process scope | per-user process, no root | per-user LaunchAgent, no root | per-user process — Windows services run in session 0 and cannot show UI |
| Tray API | StatusNotifierItem/AppIndicator over D-Bus; GNOME shows it only with the AppIndicator extension; XEmbed is deprecated | `NSStatusItem` (menu bar); `LSUIElement` hides the Dock icon | `Shell_NotifyIcon`; tray-only means no taskbar entry |
| Durable vs ephemeral state | durable port + per-install secret in the private config dir (`$XDG_CONFIG_HOME/stt-app`, 0600); ephemeral PID + actual endpoint in `$XDG_RUNTIME_DIR/stt-app` (cleared at logout/reboot) + lock | durable in `~/Library/Application Support/stt-app`; ephemeral PID/endpoint in the per-user temp dir | durable in `%LOCALAPPDATA%\stt-app` (user ACL); ephemeral in `%LOCALAPPDATA%\Temp\stt-app`; named mutex/pipe |
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

## Proposal

1. Lifecycle: one service instance per user with a lock and an ephemeral runtime file (PID and the
   actual endpoint); durable state — the chosen port, the per-install secret, the default frontend —
   lives in the private config/data directory; autostart is an opt-in setting implemented per OS
   with the user-scoped mechanisms above; on-demand start via the tray or `stt-app open` always
   works.
2. Endpoint: try the durable default/chosen port, fall back to a free one, persist the chosen port
   durably, and publish the actual endpoint in the runtime file; print and expose the URL so
   bookmarks, the tray and `stt-app open` agree.
3. Frontend registry: descriptor files per installed frontend in a per-user directory, with kind,
   name, command/URL and arguments; the service exposes the list and serves the default-frontend
   setting; installers/packages write the descriptors.
4. Tray: the item-64 shell in tray-only mode (or a native helper) as a service client — open default
   frontend, open any registered one, pause/resume the queue, show status, quit the service, toggle
   start at login.
5. Security: loopback bind, `Host`/`Origin` validation, per-install credential in a durable `0600`
   file (user ACL on Windows), user-only registry and runtime files; the browser gets the launch URL
   from the tray/`stt-app open`, the TUI reads the durable credential file.
6. Packaging per OS: Linux first (`.deb`, flake package, bundle + autostart entry), then the
   macOS/Windows forms from items 48/49; the service binary is the same JVM app; frontends are
   separate artifacts/packages.
7. Record the service model, the trust boundary and the flavor/launch model as an ADR.

## Open questions

- The exact default port (proposal 48763) and whether a taken persisted port is retried or a new
  free one is adopted and re-persisted.
- Descriptor schema, its per-user directory per OS, and whether the default frontend lives in the
  service's settings row (shared by all clients) or in the registry's config.
- Tray host: the item-64 shell in tray-only mode vs a small native helper per OS; GNOME without
  AppIndicator degrades to no tray (notification + launcher) — confirm the degradation.
- Terminal override: where the user sets the per-OS launcher (Linux terminal command, macOS terminal app, Windows shell/console host).
- Whether the current sidecar desktop becomes one flavor (attaches to the service) or is retired.
- What items 48/49's packaged-app sections need to change once this model exists; the ADR is the
  input for that decision.
- Unsigned macOS autostart and Windows SmartScreen acceptance for the service and its tray.

## Test list (proposed 2026-10-19; behaviors only; to be approved with the user)

- [ ] (basic) One service per user: a second launch attaches to the running instance instead of
      starting another; clients read the durable port and credential from the private file and the
      running instance's actual endpoint from the ephemeral runtime file.
- [ ] (basic) The browser frontend opens from the tray in the OS default browser; the TUI opens in
      a terminal (Windows: PowerShell default; macOS: Terminal; Linux: the terminal chain), each via
      its registered descriptor.
- [ ] (basic) The tray lists exactly the installed frontends' descriptors and opens each; "Open"
      uses the user's default frontend; uninstalling a frontend removes it from the tray.
- [ ] (basic) The window frontend attaches to the running service (no second backend); closing the
      window leaves the service running; quitting the service is explicit and stops the engine
      cleanly.
- [ ] (basic) Autostart (opt in) starts the service at login on each OS with the user-scoped
      mechanism (XDG autostart / LaunchAgent / HKCU Run) and produces no duplicate instance.
- [ ] (edge) A taken default port falls back to a free one, the durable chosen port updates, and
      bookmarks/`stt-app open`/the tray all use the new URL.
- [ ] (edge) After a logout/reboot clears the ephemeral runtime directory, the service starts on
      the durable port, the next runtime file advertises it, and no secret was stored ephemerally.
- [ ] (security) The service refuses requests with a foreign `Host`/`Origin` or without the
      per-install credential, while the browser (via the launch URL) and the TUI (via the durable
      file) work; no credential appears in world-readable or ephemeral files.
- [ ] (CI per OS) Each OS leg proves its mechanics: macOS LaunchAgent + menu bar, Windows autostart
      + tray + PowerShell TUI, Linux autostart + AppIndicator (or the documented GNOME fallback).
- [ ] (must not break) The existing server, Docker, desktop and e2e paths stay green on the platforms
      they already cover; the three Linux desktop artifacts keep their packaged e2e.

## Acceptance criteria

- One per-user service owns state and processing on all three OSes; browser, window and TUI are
  clients; the tray is optional; installed frontends are listed and the default is user-set.
- The port and the per-install secret persist across logout/reboot in the private directory, with a
  default-port/free-port fallback; the ephemeral runtime file only advertises the running instance;
  the service is loopback-only, `Host`/`Origin`-validated and credential-protected.
- Per-OS login, tray and terminal mechanics are implemented and verified by that OS's CI leg; the
  service model is recorded as an ADR.
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
