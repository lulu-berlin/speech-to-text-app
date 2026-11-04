# 92 — Electron 45: the desktop window on the next major

Status: draft
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

## Decided (consultation, 2026-11-04)

- Filed at the user's request from the dependency scan; a major, so its own item with the packaged
  verification rather than riding another change.

## Proposal

1. Read Electron 45's breaking-changes notes and inventory every `electron` import, the web
   preferences, the session policy and the packaging configuration.
2. Bump `electron` to `^45.2.2` and change only what the notes require.
3. Re-run the desktop tests and type checks, then the three packaged targets and their e2e journeys.
4. Record any behaviour change (security defaults, window flags, menu handling) in this item's spec
   and its log.

## Open questions

To settle in this item's consultation: whether to adopt any new security default Electron 45 offers
(e.g. sandbox/preload isolation), and whether the pinned `electron` takes a caret or an exact
version.

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

## Approval log
