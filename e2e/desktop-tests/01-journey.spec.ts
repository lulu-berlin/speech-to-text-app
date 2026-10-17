import { expect, test } from '@playwright/test';
import { networkInterfaces } from 'node:os';
import { jfkClip, launchDesktopApp, transcribeAndOpenTranscript } from './helpers/app';

// Roadmap item 50 (ADR 0046), test item 1: the desktop app as a user gets it — the whole journey
// inside the app's own window, with no browser, terminal or dev server in the loop. The shared
// setup (the packaged command, the fresh HOME, the model overrides) is `helpers/app.ts`.
test('the packaged desktop app transcribes an upload and shows it in History, in its own window', async () => {
  const clip = jfkClip();
  const app = await launchDesktopApp();
  try {
    // The app's own window shows the UI as its bundled backend serves it on the loopback
    // interface: no browser, no dev server, no file:// page. The frontend redirects the bare
    // root to its default route (App.tsx: '/' → '/queue'), so /queue is the landing URL.
    const window = await app.firstWindow();
    await expect(window).toHaveURL(/^http:\/\/127\.0\.0\.1:\d+\/queue$/, { timeout: 120_000 });
    await expect(window).toHaveTitle('Speech-to-Text');
    await expect(window.getByText('Live', { exact: true })).toBeVisible({ timeout: 120_000 });

    // The backend listens on the loopback interface only: no other interface of the
    // machine answers on the app's port (discovered 2026-10-14; the backend default binds
    // 0.0.0.0, the desktop app sets STT_HOST=127.0.0.1).
    const port = Number(new URL(window.url()).port);
    for (const address of externalAddresses()) {
      let reachable = false;
      try {
        reachable = (
          await fetch(`http://${address}:${port}/api/health`, { signal: AbortSignal.timeout(2000) })
        ).ok;
      } catch {
        // no interface answers: exactly what the assertion wants
      }
      expect(reachable, `the API must not be reachable on ${address}`).toBeFalsy();
    }

    // Upload → transcribe → History → the transcript's quote: the journey both desktop specs end
    // on, shared with the first-run spec (helpers/app.ts).
    await transcribeAndOpenTranscript(window, clip);

    // The whole journey stayed in the one app window.
    expect(app.windows()).toHaveLength(1);
  } finally {
    await app.close();
  }
});

// Every non-loopback IPv4 address of the machine, for the loopback-binding assertion.
function externalAddresses(): string[] {
  const addresses: string[] = [];
  for (const ifaces of Object.values(networkInterfaces())) {
    for (const iface of ifaces ?? []) {
      if (iface.family === 'IPv4' && !iface.internal) addresses.push(iface.address);
    }
  }
  return addresses;
}
