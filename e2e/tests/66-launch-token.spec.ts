import { createServer } from 'node:http';
import { expect, test } from '@playwright/test';
import { HISTORY_CLIPS } from '../lib/backend.ts';
import { browserLaunchUrl, registerFrontend, startAppModeService } from './helpers/service';
import { uploadClips } from './helpers/ui';

// Roadmap item 66, e2e line E1 (ADR 0046): the browser enters the per-user service through the
// one-time launch URL that `stt-app open` produces. The service runs in app mode on its own
// directories (the shared dev-mode backend is untouched); `stt-app register` installs the browser
// frontend, `stt-app open browser --print-url` asks the service for a token, and the browser that
// follows it is live — its API and event stream ride the HttpOnly cookie: an upload reaches
// Completed through the event stream, and page script cannot read the cookie. The spent token, a
// browser that never got the cookie, and a page on a foreign origin get no app data.
test('the browser enters app mode through the one-time launch URL stt-app open produces', async ({
  page,
  browser,
}) => {
  test.setTimeout(600_000);
  const service = await startAppModeService();
  const port = service.port;
  try {
    registerFrontend(service, { id: 'browser', name: 'Browser', kind: 'browser', url: '/' });
    const url = browserLaunchUrl(service);
    expect(url).toMatch(new RegExp(`^http://127\\.0\\.0\\.1:${port}/open\\?t=.+`));

    // The launch: the token is redeemed for the session cookie, and the app is live on it.
    await page.goto(url);
    await expect(page.getByText('Live', { exact: true })).toBeVisible();
    const session = (await page.context().cookies()).find(
      (cookie) => cookie.name === 'stt_session',
    );
    expect(session).toMatchObject({ httpOnly: true, sameSite: 'Strict' });
    expect(await page.evaluate(() => document.cookie)).not.toContain('stt_session');

    // The app works on the cookie: an upload is transcribed, and the live event stream says so.
    const clip = HISTORY_CLIPS[0];
    await uploadClips(page, [clip]);
    await expect(page.getByText(`${clip} transcribed`)).toBeVisible({ timeout: 300_000 });

    // A page on a foreign origin, in the same browser (the cookie is there for this host): the
    // service refuses the origin, and the browser would not hand the answer over anyway.
    const foreign = createServer((_request, response) => {
      response.setHeader('Content-Type', 'text/html');
      response.end('<!doctype html><title>foreign</title><p>foreign</p>');
    });
    await new Promise<void>((resolve) => foreign.listen(0, '127.0.0.1', resolve));
    try {
      const address = foreign.address();
      const foreignPort = typeof address === 'object' && address !== null ? address.port : 0;
      await page.goto(`http://127.0.0.1:${foreignPort}/`);
      const answer = await page.evaluate(async (api) => {
        try {
          const response = await fetch(api, { credentials: 'include' });
          return response.ok ? await response.text() : `status ${response.status}`;
        } catch {
          return 'blocked';
        }
      }, `http://127.0.0.1:${port}/api/tasks`);
      expect(answer).toMatch(/^(blocked|status 40[13])$/);
    } finally {
      foreign.close();
    }

    // Another browser: the spent token is refused, and the app without the cookie gets the page
    // that says how to open it.
    const stranger = await browser.newContext();
    try {
      const other = await stranger.newPage();
      expect((await other.goto(url))?.status()).toBe(401);
      expect((await other.goto(`http://127.0.0.1:${port}/`))?.status()).toBe(401);
      await expect(
        other.getByRole('heading', { name: 'Open the app from the tray' }),
      ).toBeVisible();
    } finally {
      await stranger.close();
    }
  } finally {
    await service.stop();
  }
});
