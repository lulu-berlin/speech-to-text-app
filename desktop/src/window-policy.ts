// The window's network policy (ADR 0055), in one place: what the app's window may reach — its own
// file and the loopback backend, nothing else — and the guards that keep it there. `main.ts` keeps
// the lifecycle (models, backend, window, quit); the rules live here.
import type { Session, WebContents } from 'electron';

// The window may load its own UI (the download screen) and talk to the loopback backend. Anything
// else is refused, so a recording or a transcript has no way off the machine even if the UI were
// ever tricked into requesting something.
const LOOPBACK_HOSTS = new Set(['127.0.0.1', '[::1]', 'localhost']);

export function windowMayReach(url: string): boolean {
  const { protocol, hostname } = new URL(url);
  if (protocol === 'file:' || protocol === 'data:' || protocol === 'blob:') return true;
  return (protocol === 'http:' || protocol === 'https:') && LOOPBACK_HOSTS.has(hostname);
}

/**
 * The session-level half: cancel every request outside the allowlist, and grant no permission —
 * both handlers, so a permission *request* and a *query* (`navigator.permissions.query`) agree.
 */
export function applySessionPolicy(target: Session): void {
  target.webRequest.onBeforeRequest((details, callback) => {
    callback({ cancel: !windowMayReach(details.url) });
  });
  target.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
  target.setPermissionCheckHandler(() => false);
}

/**
 * The window-level half: no popups, and no navigation away from the app. `will-frame-navigate` is
 * the event that fires for a page-initiated navigation (Electron 44 deprecates `will-navigate`'s
 * extra arguments) and preventing it stops the navigation before the request is made. `did-fail-load`
 * is the safety net: if a blocked navigation ever did leave the main frame empty, the app goes back
 * to its own URL. `appUrl` is that URL — null until the backend has one.
 */
export function guardWindow(contents: WebContents, appUrl: () => string | null): void {
  contents.setWindowOpenHandler(() => ({ action: 'deny' }));
  contents.on('will-frame-navigate', (details) => {
    if (!windowMayReach(details.url)) details.preventDefault();
  });
  contents.on('did-fail-load', (_event, _code, _description, failedUrl, isMainFrame) => {
    const url = appUrl();
    if (!isMainFrame || !url || failedUrl === url || contents.isDestroyed()) return;
    void contents.loadURL(url);
  });
}
