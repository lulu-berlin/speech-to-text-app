import { expect, test } from '@playwright/test';
import { once } from 'node:events';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import { networkInterfaces } from 'node:os';
import { existsSync, mkdtempSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { launchDesktopApp, userPaths } from './helpers/app';
import { writeFixturePins } from './helpers/model-server';

// Roadmap item 50, approved test item: provisioning is limited to the GitHub model hosts. This
// fixture server listens on all interfaces, and the app is pointed at its non-loopback address —
// genuinely reachable from the packaged app, so a missing allowlist will contact it. It answers
// 404 immediately: the old behavior reaches the fixture and shows that URL in the error; the new
// behavior must reject the host before the first request, name the host, and leave no cache entry.
test('model provisioning refuses a non-allowlisted host before sending a request', async () => {
  const lan = Object.values(networkInterfaces())
    .flat()
    .find((entry) => entry?.family === 'IPv4' && !entry.internal)?.address;
  test.skip(!lan, 'needs a non-loopback IPv4 address for the reachable deny fixture');

  let requests = 0;
  const server = createServer((_request, response) => {
    requests += 1;
    response.writeHead(404);
    response.end('reachable fixture: the host policy did not stop this request');
  });
  server.listen(0, '0.0.0.0');
  await once(server, 'listening');
  const { port } = server.address() as AddressInfo;
  const host = lan!;

  const home = mkdtempSync(join(tmpdir(), 'stt-desktop-denied-model-host-'));
  const file = 'fixture-not-in-the-nix-store.bin';
  const pins = writeFixturePins(`http://${host}:${port}`, { whisper: file, speaker: file });

  const app = await launchDesktopApp({
    home,
    models: 'download',
    env: { STT_MODELS_FILE: pins },
  });
  try {
    const page = await app.firstWindow();
    await expect(page.locator('#error')).toContainText(host, { timeout: 30_000 });
    expect(requests, `the disallowed host ${host} must be refused before any request`).toBe(0);
    // The cache directory may exist (the app creates it before it downloads anything), but a
    // refused host must leave no file in it — not even a `.part`.
    const cache = userPaths(home).models;
    expect(existsSync(cache) ? readdirSync(cache) : []).toEqual([]);
    await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible();
  } finally {
    await app.close();
    server.close();
  }
});
