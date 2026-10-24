import { expect, test } from '@playwright/test';
import { appUrl } from '../lib/backend.ts';

// Item 59, finding 7: the connection indicator was the bare word "Live", with no visual
// state beyond the text. It is now a dot plus its label, informational (no reconnect
// click), and the connection machine's three states read distinctly — both in the label
// and in the dot's colour.
test('the connection indicator shows connecting, connected and reconnecting distinctly', async ({
  page,
}) => {
  // Hold the first event stream unanswered: the machine stays in connecting.
  const held = Promise.withResolvers<void>();
  await page.route('**/api/events', async (route) => {
    try {
      await held.promise;
      await route.abort();
    } catch {
      // the request died with a navigation; nothing left to handle
    }
  });
  await page.goto(appUrl('/'));

  const dot = page.locator('header [data-connection-state]');
  await expect(dot).toBeVisible();
  await expect(page.locator('header')).toContainText('Connecting');
  const connecting = await dot.evaluate((element) => getComputedStyle(element).backgroundColor);
  expect(await dot.getAttribute('data-connection-state')).toBe('connecting');

  // The stream errors and its retries are still aborted: reconnecting.
  held.resolve();
  await expect(page.locator('header')).toContainText('Reconnecting');
  await expect(dot).toHaveAttribute('data-connection-state', 'reconnecting');
  const reconnecting = await dot.evaluate((element) => getComputedStyle(element).backgroundColor);
  expect(reconnecting).not.toBe(connecting);

  // With the route gone a retry succeeds: connected ("Live").
  await page.unroute('**/api/events');
  await expect(page.locator('header')).toContainText('Live', { timeout: 30_000 });
  await expect(dot).toHaveAttribute('data-connection-state', 'live');
  const live = await dot.evaluate((element) => getComputedStyle(element).backgroundColor);
  expect(live).not.toBe(connecting);
  expect(live).not.toBe(reconnecting);
});
