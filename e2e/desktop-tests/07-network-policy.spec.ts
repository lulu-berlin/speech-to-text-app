import { expect, test } from '@playwright/test';
import { once } from 'node:events';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import { networkInterfaces } from 'node:os';
import { launchDesktopApp } from './helpers/app';

// Roadmap item 50, test item "The app's window cannot reach the network": the window may load the
// progress page from its own file and talk to the loopback backend, and nothing else — the app's
// promise is that recordings and transcripts never leave the machine. Provisioning keeps its own
// path (the model download runs in the main process), so this is about the window only.
//
// The stand-in "internet" host listens on both addresses at once, and the same server must answer
// on loopback and be refused on the machine's own non-loopback address. An offline machine would
// fail both fetches and a permissive app neither, so the pair is what makes this a policy test.
test('the app window cannot reach anything but the loopback backend', async () => {
  const server = createServer((_request, response) => {
    // CORS, so a permitted fetch yields a status instead of a cross-origin error.
    response.writeHead(200, { 'access-control-allow-origin': '*', 'content-type': 'text/plain' });
    response.end('reachable');
  });
  server.listen(0, '0.0.0.0');
  await once(server, 'listening');
  const { port } = server.address() as AddressInfo;
  // The machine's own non-loopback IPv4 (in the container harness, its eth0 address).
  const lan = Object.values(networkInterfaces())
    .flat()
    .find((entry) => entry?.family === 'IPv4' && !entry.internal)?.address;
  expect(lan, 'the harness machine needs a non-loopback IPv4 for this test').toBeTruthy();

  const app = await launchDesktopApp();
  try {
    // Named `page`, not `window`: inside `evaluate` the DOM's own `window` must be the global.
    const page = await app.firstWindow();
    await expect(page.getByText('Live', { exact: true })).toBeVisible({ timeout: 120_000 });

    // The window can reach a server through loopback, so its network stack works…
    const loopback = await page.evaluate(
      (url) => fetch(url).then((response) => response.status),
      `http://127.0.0.1:${port}/`,
    );
    expect(loopback).toBe(200);

    // …and cannot reach the very same server at the machine's own non-loopback address.
    const outside = await page.evaluate(async (url) => {
      try {
        await fetch(url);
        return 'reachable';
      } catch (error) {
        return `refused: ${String(error)}`;
      }
    }, `http://${lan}:${port}/`);
    expect(outside).toContain('refused');

    // No new window, no permission, and no navigating the app's own window away.
    expect(await page.evaluate(() => window.open('https://example.com'))).toBeFalsy();
    expect(
      await page.evaluate(() =>
        navigator.permissions.query({ name: 'geolocation' }).then((status) => status.state),
      ),
    ).toBe('denied');
    // Navigating the app's own window away is refused before the request is made
    // (`will-frame-navigate`), so the window keeps showing the app on its own URL. Playwright's
    // locators wait for a pending navigation to settle and a *cancelled* navigation never does
    // (diagnosed 2026-10-15: the frame stays on /queue, `loading=false`, the UI intact), so the
    // app's state is read through the main process and the renderer directly.
    await page.evaluate(() => {
      window.location.href = 'https://example.com';
    });
    await expect
      .poll(
        () =>
          app.evaluate(
            ({ BrowserWindow }) =>
              BrowserWindow.getAllWindows()[0]?.webContents.isLoading() ?? true,
          ),
        { timeout: 30_000 },
      )
      .toBe(false);
    const url = await app.evaluate(
      ({ BrowserWindow }) => BrowserWindow.getAllWindows()[0]?.webContents.getURL() ?? '',
    );
    expect(url).toMatch(/^http:\/\/127\.0\.0\.1:\d+\//);
    expect(await page.evaluate(() => document.body.innerText.includes('Live'))).toBe(true);
  } finally {
    await app.close();
    server.close();
  }
});
