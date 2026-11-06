// The test server's own behaviour: a per-test override wins over the shared handlers,
// and the next test starts from the shared handlers again (setup.ts resets them). Every
// other spec relies on this through server.use.
import { http, HttpResponse } from 'msw/http';
import { describe, expect, it, vi } from 'vitest';
import { settings as settingsFixture } from '../shared/api/fixtures';
import { server } from './server';

describe('the test server', () => {
  it('serves a per-test override ahead of the shared handler for the same endpoint', async () => {
    server.use(
      http.get('/api/settings', () => HttpResponse.json({ ...settingsFixture, threads: 2 })),
    );

    const response = await fetch('/api/settings');

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ...settingsFixture, threads: 2 });
  });

  it('serves the shared handler again in the next test, the override reset', async () => {
    const response = await fetch('/api/settings');

    expect(response.status).toBe(200);
    // settings.json stores 6 threads: the shared handler's body, not the override's 2.
    expect(await response.json()).toEqual(settingsFixture);
  });

  it('prints and rejects an unmatched request under the configured error policy', async () => {
    const printed = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      await expect(fetch('/api/no-such-endpoint')).rejects.toThrow();

      // setup.ts configures the error policy: MSW names the request and its method,
      // and the request is refused rather than reaching the real network.
      expect(printed).toHaveBeenCalledTimes(1);
      const message = printed.mock.calls[0]?.[0] as string;
      expect(message).toContain('intercepted a request without a matching request handler');
      expect(message).toContain('GET /api/no-such-endpoint');
    } finally {
      printed.mockRestore();
    }
  });
});
