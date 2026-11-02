# 0064 - The service and the tray are one unit in app mode

Status: accepted · Date: 2026-10-31

## Context

Roadmap item 66 described the tray as "an optional launcher/supervisor", and its test list treats the service and the tray as independent singletons: the service always runs when the app is usable, while a tray may or may not run beside it, and either can be up while the other is down. The user rejected that model (2026-10-31): in app (release) mode the app is one thing - the service and the tray are either both up or both down. Not necessarily one process; the two keep their own jobs (the service owns the database, the queue and provisioning; the tray owns the icon, the frontend menu and quitting).

Why the two-independent-singletons model was wrong in practice: it lets a release installation run with no tray, which is a state nobody can reach through the product's own UI (there is no way to start the tray again from the app), and it lets the tray run after the service died, showing a menu whose every entry fails.

## Decision

- **One unit in app mode.** In app (release) mode the service and the tray are both up or both down. Opening any frontend (`stt-app open browser|gui|terminal`), the tray's own start, autostart, or the window's start-or-attach brings the unit up; quitting the tray or `stt-app quit` brings it down.
- **Frontends are not part of the unit.** "Optional" now means only that no particular frontend
  has to be open: the browser may have several tabs, the TUI several instances, and the app may run
  with none of them open. Closing a frontend never stops the unit - the window's close already
  leaves the service running, and the tray stays too; closing the window never stops the service or
  the tray. Since the window is a singleton, closing it leaves nothing to attach to, so the next
  `stt-app open gui` (or the tray's Open) starts a fresh window process against the still-running
  service.
- **Singletons are by kind.** The service, the tray and the `gui` frontend (today's Electron
  window) are singletons per user; the browser (tabs) and the TUI (instances) are not. Opening the
  window when one exists attaches to it - Electron's single-instance lock, with the second
  invocation handing its arguments to the running one and exiting - so "Open" from the tray or a
  second `stt-app open gui` raises the window instead of starting another.
- **Mutual supervision, symmetric.** Either process may restart the other when it dies: each end is
  allowed to try. If the restart fails, the survivor stops too - the rule reads the same in both
  directions: service dies and cannot be restarted, the tray stops; tray dies and cannot be
  restarted, the service stops. A broken pair never lingers as half an app.
- **A crash loop counts as a failed restart.** A sibling that starts and then dies again at once
  would make every restart look successful. So the survivor restarts a sibling at most 3 times
  within 60 seconds; a 4th death inside that window is treated like a failed restart, and the
  survivor stops too.
- **Dev mode is exempt.** `devenv up` and the e2e harness's service-only start run the service without a tray; they are development tools, not the product. The e2e harness's app-mode runs are not exempt: they exercise the unit, and the launch handshake brings the tray up with the service.
- **A tray without a tray host runs virtually.** Where no StatusNotifierItem/AppIndicator host exists (headless shell sessions, GNOME without the extension) the tray process still starts and supervises the service, but shows no icon. That state is for technical users who drive the app from the TUI; a user with a browser or a GUI can be expected to have a tray host. It is a degraded state, not a separate mode, and it never changes what the service does.
- **A different version replaces the running unit** (the user, 2026-10-31): if the app being
  launched is not the same version as the service that is running, the launch replaces it - the old
  unit is stopped and the new one started - and a loaded frontend page reloads rather than keep
  talking to the old service. The version identity itself is roadmap item 78's (one number
  everywhere, reported by the service); until that exists, the interim identity is a digest of
  `versions.json`, which both the service and the clients can embed. Deferred to item 78.
- **One singleton claim: the service's.** The tray exists only as part of the unit, so the unit has one claim - the service's `FileChannel.tryLock` instance lock and its `runtime.json` advertisement. Start-or-attach reports which of the two a launch did, and only the launch that started the service starts the tray; a concurrent second launch loses the lock, attaches, and starts no tray. The tray takes no lock of its own.

## Consequences

- Test-list items 2, 3 and the discovered "starting a frontend starts the tray" line now assert the unit: opening any frontend yields exactly one service and exactly one tray; several frontend instances (browser tabs, TUI instances) are fine; closing a frontend or all of them leaves the unit up; quitting the tray or `stt-app quit` ends the unit.
- The e2e restart specs must stop the unit, not only the service: with supervision in place, stopping the service alone makes the tray restart it and the test fights the supervisor. They stop the tray first (or `stt-app quit`), then the service.
- An app-mode e2e run now starts a tray process for its duration. On a desktop that shows an icon while the suite runs; on a headless runner it is virtual. No switch turns the tray off, for the same reason no switch turns the credential check off.
- Packaging ships the tray with the service (item 66's proposal 6), and autostart starts the unit.
- The unit's processes log to their own files, `<config>/service.log` and `<config>/tray.log`: a
  detached unit that inherited the launch command's stdout and stderr would hold those pipes open
  for as long as it runs, so a caller capturing the output (the e2e harness runs `stt-app open
  browser --print-url`) would never see its command finish.
- The roadmap item's Goal and Decided wording that calls the tray optional is superseded by this ADR.

## Alternatives considered

- **Tray optional (the previous wording):** rejected above - it makes "service with no tray" a reachable state with no way back from the UI.
- **One process for both** (the tray inside the JVM): rejected - the tray is Rust by the 2026-10-22 decision, and a JVM-owned tray would tie the icon's lifetime and the service's crash behaviour together in the wrong direction (a tray cannot restart a JVM that it lives in).
- **A tray lock (`tray.lock`) or a `tray.json` pid file:** rejected (2026-10-31) - both are a second singleton claim for a process that only exists as part of the unit. The service's lock already decides, and the launch that won it starts the tray; a pid file would also race (two launches both read "no pid").
- **Refusing app mode without a tray host:** rejected - it would ban servers, CI and shell sessions from the product's own mode and leave the TUI without a way to run.
