import { expect, test } from '@playwright/test';
import { appUrl } from '../lib/backend.ts';
import { MASCOT, mascotAbove } from './helpers/ui';

test('shell, loading and empty regions show the mascot', async ({ page }) => {
  // The shell's mark; with nothing queued the header's artwork rests (item 59) — the
  // empty drop zone's illustration is the one that types.
  await page.goto(appUrl('/'));
  await expect(page.locator(`header ${MASCOT}`)).toBeVisible();
  await expect(page.getByText('Live', { exact: true })).toBeVisible();
  await expect(page.locator('header .is-typing')).toHaveCount(0);

  // "Connecting…" then "Reconnecting…": the first stream is held unanswered, so the
  // machine stays in its initial connecting state (an aborted stream would drop it to
  // reconnecting immediately — the hold is what makes the label observable). Once let
  // go, the stream errors; while the route stays up every retry is still aborted, so the
  // region shows the mascot above "Reconnecting…". With the route gone, a retry
  // succeeds and the app goes live. (Silent live drops are already covered by the app
  // itself since item 29: the backend heartbeats every 5 s and the client's 15 s idle
  // watchdog reconnects — ADR 0044 — while item 13's restart exercises the live drop
  // against the real process.)
  const firstStream = Promise.withResolvers<void>();
  await page.route('**/api/events', async (route) => {
    try {
      await firstStream.promise;
      await route.abort();
    } catch {
      // the request died with a navigation; nothing left to handle
    }
  });
  await page.reload();
  await expect(page.getByText('Connecting…')).toBeVisible();
  await expect(mascotAbove(page, 'Connecting…')).toBeVisible();

  firstStream.resolve();
  await expect(page.getByText('Reconnecting…')).toBeVisible();
  await expect(mascotAbove(page, 'Reconnecting…')).toBeVisible();
  await page.unroute('**/api/events');
  await expect(page.getByText('Live', { exact: true })).toBeVisible({ timeout: 30_000 });

  // Empty regions lead with the mascot: the queue's drop zone and History's empty state.
  await expect(
    page.getByText('No tasks yet — drop audio files here, or click to choose them'),
  ).toBeVisible();
  await expect(
    mascotAbove(page, 'No tasks yet — drop audio files here, or click to choose them'),
  ).toBeVisible();
  await page.getByRole('link', { name: 'History' }).click();
  await expect(page.getByText('No transcripts yet')).toBeVisible();
  await expect(mascotAbove(page, 'No transcripts yet')).toBeVisible();
});
