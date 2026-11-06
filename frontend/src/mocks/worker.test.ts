// The dev mock's service worker: `pnpm dev:mock` runs MSW in the browser through the
// committed script, which must be the one the installed msw ships (a mismatch is exactly
// what breaks interception at start), and Vite must serve it at the origin root so its
// scope covers the page.
import { readFileSync } from 'node:fs';
import type { AddressInfo } from 'node:net';
import { createRequire } from 'node:module';
import { join } from 'node:path';
import { createServer } from 'vite';
import { describe, expect, it } from 'vitest';

// Vitest runs from the frontend package root; import.meta.url is not a file: URL here.
const projectRoot = process.cwd();
const committedWorker = join(projectRoot, 'mock/mockServiceWorker.js');
const installedWorker = createRequire(join(projectRoot, 'package.json')).resolve(
  'msw/mockServiceWorker.js',
);

describe('the dev mock worker', () => {
  it("is the script the installed msw ships, served at the origin root by dev:mock's Vite", async () => {
    const served = readFileSync(committedWorker, 'utf8');
    expect(served).toBe(readFileSync(installedWorker, 'utf8'));

    const vite = await createServer({
      mode: 'mock',
      logLevel: 'silent',
      server: { port: 0, strictPort: false },
    });
    try {
      await vite.listen();
      const port = (vite.httpServer?.address() as AddressInfo).port;
      const response = await fetch(`http://localhost:${port}/mockServiceWorker.js`);

      expect(response.status).toBe(200);
      expect(await response.text()).toBe(served);
    } finally {
      await vite.close();
    }
  }, 30_000);
});
