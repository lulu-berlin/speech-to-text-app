import { expect, test } from '@playwright/test';
import { lookup } from 'node:dns/promises';
import { once } from 'node:events';
import { existsSync, mkdtempSync, readdirSync } from 'node:fs';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { launchDesktopApp, userPaths } from './helpers/app';
import { writeFixturePins } from './helpers/model-server';

// Roadmap item 50, discovered in the main-tree review (2026-10-15): provisioning accepts exactly
// `github.com` and `release-assets.githubusercontent.com` (plus loopback), so a redirect hop to any
// other githubusercontent.com host is refused before a request, naming the host, and leaves no cache
// or partial file. The harness maps `raw.githubusercontent.com` to 127.0.0.1 (docker --add-host; an
// /etc/hosts line in the .deb CI job), so the redirect target is this fixture again: a hop the app
// does not refuse is a counted request, and nothing reaches the real host.
const REFUSED_HOST = 'raw.githubusercontent.com';

test('a redirect to a githubusercontent.com host other than release-assets is refused', async () => {
  const { address } = await lookup(REFUSED_HOST);
  expect(address, `the harness must map ${REFUSED_HOST} to 127.0.0.1`).toBe('127.0.0.1');

  let hops = 0;
  let refusedHostRequests = 0;
  const server = createServer((request, response) => {
    if (request.url?.endsWith('-hop')) {
      // The allowed first hop (loopback) redirects to the same file on the refused host.
      hops += 1;
      const name = request.url.slice(1, -'-hop'.length);
      response.writeHead(302, { location: `http://${REFUSED_HOST}:${port}/${name}.bin` });
    } else {
      refusedHostRequests += 1;
      response.writeHead(404);
    }
    response.end();
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const { port } = server.address() as AddressInfo;

  const home = mkdtempSync(join(tmpdir(), 'stt-desktop-refused-redirect-home-'));
  const pins = writeFixturePins(`http://127.0.0.1:${port}`, {
    whisper: 'whisper-hop',
    speaker: 'speaker-hop',
  });

  const app = await launchDesktopApp({ home, models: 'download', env: { STT_MODELS_FILE: pins } });
  try {
    const page = await app.firstWindow();
    await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible({ timeout: 30_000 });
    expect(hops, 'the allowed loopback hop is followed').toBeGreaterThan(0);
    expect(
      refusedHostRequests,
      `the redirect to ${REFUSED_HOST} must be refused before a request`,
    ).toBe(0);
    await expect(page.locator('#error')).toContainText(REFUSED_HOST);
    const cache = userPaths(home).models;
    expect(existsSync(cache) ? readdirSync(cache) : []).toEqual([]);
  } finally {
    await app.close();
    server.close();
  }
});
