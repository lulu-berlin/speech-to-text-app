# 92 — Electron 45: the desktop window on the next major

Status: spec-approved
Depends on: [50](../docs/specs/electron-desktop-app.md) (done; the app, its packaging and its e2e suites)
Blocks: —

## Goal

The desktop app runs on Electron 45 (currently 44.9.1) with the same behaviour for the user: the
window on the service, the backend's start/wait/stop, the network policy, the model download and the
three packaged installers built by the same electron-builder.

## Evidence

- **The pin**: `desktop/package.json` has `electron` 44.9.1 and `electron-builder` 26.18.0; latest on
  npm is **45.2.2** (checked 2026-11-04). `electron-builder` is current.
- **What the main process uses**: `app`, `BrowserWindow`, `dialog`, `ipcMain`, `Menu`, `session`
  (`desktop/src/main.ts:6`), the `Session`/`WebContents` types (`desktop/src/window-policy.ts:4`), a
  preload script through `webPreferences.preload`, `Menu.setApplicationMenu(null)`, and
  `applySessionPolicy(session.defaultSession)` implementing ADR 0055's network policy.
- **What proves it**: the desktop unit tests and the four type checks, plus the packaged e2e jobs
  (`Linux .deb`, `macOS DMG`, `Windows zip/MSI`), which launch the installed app against the real
  backend; a major may change preload/sandbox defaults or remove an API, so the review is driven by
  Electron 45's breaking-changes notes.
- **What 45 changes for this code** (Electron 45 release notes, 2026-10-21; Chromium 156, Node
  24.22.0): sandboxed preload scripts lose the `Buffer`/`setImmediate`/`clearImmediate` globals and
  the `events`/`timers`/`url` shims, and their `require()` loads only Electron's own modules —
  `desktop/src/preload.cts` uses none of the removed names (to be confirmed by the build);
  screen-capture requests now reach `setPermissionRequestHandler` as `display-capture` — the app
  denies every permission (`window-policy.ts:25-26`), so nothing changes; `window.open()` children
  of unsandboxed windows get their own process — the app denies every `window.open`
  (`window-policy.ts:37`); descriptors of files inside ASAR archives are only usable through `fs`;
  synchronous `safeStorage` is deprecated and `crashReporter` gains a Windows helper DLL — the app
  uses neither. No new security default applies to this app.

## Decided (consultation, 2026-11-04)

- Filed at the user's request from the dependency scan; a major, so its own item with the packaged
  verification rather than riding another change.
- **Exact pin** (the repository's convention for `electron`): `electron` is pinned to an exact 45.x
  version.
- **No security-default change**: the release notes bring no new default this app would adopt (see
  Evidence); the item verifies, it does not redesign the window's security.
- **Exempt from ADR 0046's per-feature e2e test** (the user): no app feature changes; the existing
  packaged journeys (`.deb`, DMG, Windows zip/MSI) running on 45 are the proof.

## Proposal

1. Read Electron 45's breaking-changes notes and inventory every `electron` import, the web
   preferences, the session policy and the packaging configuration.
2. Pin `electron` exactly to 45.2.2 (or the then-current 45.x, exactly) and change only what the
   notes require; confirm the preload uses none of the removed sandbox globals and shims.
3. Re-run the desktop tests and type checks, then the three packaged targets and their e2e journeys.
4. Record any behaviour change (security defaults, window flags, menu handling) in this item's spec
   and its log.

## Open questions

None. (Settled 2026-11-04, see "Decided (consultation, 2026-11-04)".)

## Test list

- [ ] (basic) The window opens on the backend's URL and the preload bridge works; the lifecycle tests
      pass on 45.
- [ ] (basic) The network policy still holds: the window reaches its own file and the loopback backend
      only, and an off-origin request is blocked (ADR 0055's specs stay green).
- [ ] (basic) The backend starts, is waited for, and is stopped on quit with no orphan JVM.
- [ ] (basic) The three packaged targets build (`.deb`, DMG, Windows zip/MSI) and their packaged e2e
      journeys pass on 45.
- [ ] (edge) The model download's progress window still works, and a failed download reports and
      exits cleanly.
- [ ] (edge) A second launch behaves as it does today, and quitting closes the service.
- [ ] (must not break) The user-visible surface is unchanged: same window, no application menu, the
      same security defaults, data directory and packaged layout.
- [ ] (must not break) `electron-builder` 26.18.0 keeps producing installers with the same unpacked
      layout.

## e2e stance

Exempt from ADR 0046's per-feature e2e test (decided 2026-11-04): no app feature changes, and the
existing packaged journeys running on Electron 45 are its proof.

## Approval log

- 2026-11-04: filed as a draft by the root agent from the dependency scan (electron 44.9.1 → 45.2.2).
- 2026-11-04: consultation with the user: exact pin; Electron 45's release notes checked against the
  app (no new default applies, the preload uses none of the removed shims, permissions and
  `window.open` are already denied); exempt from ADR 0046. Test list approved as written; status set
  to spec-approved.
