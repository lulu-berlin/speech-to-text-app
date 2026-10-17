// Electron main process (roadmap item 50). The desktop app is packaging + lifecycle: it brings
// both models into the per-user cache (showing progress.html while it does), starts the bundled
// backend (backend.ts) on the URL it serves, opens the window on it, and on quit stops the
// backend and waits — no orphan JVM. All queue and engine logic stays in the backend.
import { dirname, join } from 'node:path';
import { app, BrowserWindow, dialog, ipcMain, Menu, session } from 'electron';
import { freePort, readyUrl, startBackend, waitForBackend } from './backend.js';
import { ensureModels } from './models.js';
import { applySessionPolicy, guardWindow } from './window-policy.js';

async function main(): Promise<void> {
  // The packaged resources are in the directory app.asar sits in once packed (resources/); the
  // app's own files (build/, progress.html, package.json) are inside the asar.
  const appRoot = app.getAppPath();
  const resourcesDir = dirname(appRoot);

  // The window is the app's UI and nothing else (the user, 2026-10-15): no default
  // File/Edit/View/Window/Help menu, which on Linux would also be a menu bar above the UI.
  Menu.setApplicationMenu(null);

  // The network policy (ADR 0055, window-policy.ts): the window reaches its own file and the
  // loopback backend, nothing else. Provisioning is unaffected — the model download is a
  // main-process fetch (models.ts), not a request of this session.
  applySessionPolicy(session.defaultSession);

  const window = new BrowserWindow({
    width: 1280,
    height: 800,
    title: 'Speech-to-Text',
    show: false,
    webPreferences: {
      preload: join(appRoot, 'build', 'preload.cjs'),
      // Chromium's spell checker would fetch dictionaries from the network; the app types speaker
      // names, and a dictionary is not worth an outbound request (ADR 0055).
      spellcheck: false,
    },
  });

  // The URL the window is meant to be on; the guard's safety net reloads it when a blocked
  // navigation has left the frame empty. It is set once the backend has a URL.
  let appUrl: string | null = null;
  // The window's own half of the network policy (ADR 0055): no popups, no navigation away.
  guardWindow(window.webContents, () => appUrl);

  // There is no backend to stop until startApp spawns one, but the quit path is wired first, so a
  // quit during the download runs through the same one place.
  let stopBackend = (): Promise<void> => Promise.resolve();
  quitWhenStopped(() => stopBackend());

  // The download screen comes first (decided 2026-10-14): the backend starts only once both
  // models are in the per-user cache and verified. A user-set STT_*_MODEL skips the download.
  await window.loadFile(join(appRoot, 'progress.html'));
  window.show();

  // Single-flight: the Retry button lives in the page, and a second click while a retry is already
  // running would race the first over the same cache files.
  let starting = false;
  const startApp = async (): Promise<void> => {
    if (starting) return;
    starting = true;
    try {
      const models = await ensureModels({
        resourcesDir,
        onProgress: (progress) => window.webContents.send('models:progress', progress),
      });
      const port = await freePort();
      const { stop } = await startBackend(port, { resourcesDir, models });
      stopBackend = stop;
      await waitForBackend(port);
      appUrl = readyUrl(port);
      await window.loadURL(appUrl);
    } catch (error) {
      // A backend that was spawned but never answered (or whose page did not load) is stopped and
      // waited for first: a Retry would otherwise start a second one beside it on the same stt.db.
      await stopBackend();
      stopBackend = () => Promise.resolve();
      // The screen shows the reason and offers the retry that calls startApp again.
      window.webContents.send('models:error', String(error));
    } finally {
      starting = false;
    }
  };
  ipcMain.on('models:retry', () => void startApp());
  await startApp();
}

/**
 * Routes every exit path through one clean quit: the backend (whose own lifecycle, item 26, also
 * stops whisper and ffmpeg) is asked to stop and waited for before the app goes. `before-quit` is
 * where the waiting happens. Only the `app.quit()` issued once the stop has finished takes the
 * default path; a quit request while the stop runs (a second Ctrl-C or SIGTERM) is held back too and
 * starts no second stop — letting it through would end the app, and the SIGKILL timer of a backend
 * slow to stop with it, leaving that backend behind.
 */
function quitWhenStopped(stop: () => Promise<void>): void {
  let backend: 'running' | 'stopping' | 'stopped' = 'running';
  app.on('before-quit', (event) => {
    if (backend === 'stopped') return;
    event.preventDefault();
    if (backend === 'stopping') return;
    backend = 'stopping';
    void stop().then(() => {
      backend = 'stopped';
      app.quit();
    });
  });
  app.on('window-all-closed', () => app.quit());
  process.on('SIGTERM', () => app.quit());
  process.on('SIGINT', () => app.quit());
}

app
  .whenReady()
  .then(main)
  .catch((error: unknown) => {
    console.error(error);
    dialog.showErrorBox('Speech-to-Text could not start', String(error));
    process.exitCode = 1;
    app.quit();
  });
