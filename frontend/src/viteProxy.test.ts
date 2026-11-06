// With mocking off, `pnpm dev` must still reach the app's service: Vite's /api proxy forwards
// to the backend, and the mock entry applies only in mock mode (the page loads main.tsx).
// The requests go through node:http, not fetch, so the test server's interception (setup.ts)
// does not stand in front of the dev server under test.
import { createServer as createHttpServer, request } from 'node:http';
import type { AddressInfo } from 'node:net';
import { createServer as createViteServer, loadConfigFromFile } from 'vite';
import { describe, expect, it } from 'vitest';

const get = (port: number, path: string) => {
  const result = Promise.withResolvers<{ status: number; body: string }>();
  request({ host: 'localhost', port, path }, (response) => {
    let body = '';
    response.setEncoding('utf8');
    response.on('data', (chunk: string) => {
      body += chunk;
    });
    response.on('end', () => result.resolve({ status: response.statusCode ?? 0, body }));
  }).end();
  return result.promise;
};

describe('the dev server without mocking', () => {
  it('proxies /api to the backend and serves the real entry', async () => {
    // What the dev command's config says, read from the file itself (the server below overrides
    // it): the backend's dev port (AGENTS.md § Commands), not a test detail.
    const loaded = await loadConfigFromFile({ command: 'serve', mode: 'development' });
    const configured = loaded?.config.server?.proxy?.['/api'];
    expect(typeof configured === 'string' ? configured : configured?.target).toBe(
      'http://localhost:8080',
    );

    // The stand-in answers on a free port and this test's own Vite server points its proxy at
    // it, so a service already holding 8080 (a running backend, `devenv up`) cannot fail the run.
    const standIn = createHttpServer((incoming, response) => {
      response.setHeader('content-type', 'application/json');
      response.end(JSON.stringify({ path: incoming.url }));
    });
    const listening = Promise.withResolvers<void>();
    standIn.listen(0, listening.resolve);
    await listening.promise;
    const standInPort = (standIn.address() as AddressInfo).port;
    const vite = await createViteServer({
      logLevel: 'silent',
      server: {
        port: 0,
        strictPort: false,
        proxy: { '/api': `http://127.0.0.1:${standInPort}` },
      },
    });
    try {
      await vite.listen();
      const port = (vite.httpServer?.address() as AddressInfo).port;

      const api = await get(port, '/api/queue');

      expect(api.status).toBe(200);
      expect(JSON.parse(api.body)).toEqual({ path: '/api/queue' });

      const page = await get(port, '/history');
      expect(page.body).toContain('/src/main.tsx');
      expect(page.body).not.toContain('/src/mock-main.tsx');
    } finally {
      await vite.close();
      const closed = Promise.withResolvers<void>();
      standIn.close(() => closed.resolve());
      await closed.promise;
    }
  }, 30_000);
});
