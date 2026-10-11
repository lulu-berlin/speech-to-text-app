import { expect, test, type Page } from '@playwright/test';
import { appUrl } from '../lib/backend.mjs';

// The mascot (item 17): the stenographer SVG. It is decorative (aria-hidden, no role),
// so it is found by the artwork's fixed viewBox. It appears in the header, and every
// loading region and empty state renders it above its label or title.
const MASCOT = 'svg[viewBox="55 61 293 293"]';

// The mascot directly above a region's label. Two layouts: the loading regions and the
// History empty state put the label in a paragraph right after the mascot (`svg + p`),
// the queue drop zone's zone span contains the label text with the mascot as its direct
// child. Playwright's xpath chaining supports only `..`, so these CSS forms carry the
// structure. One locator with a comma union: whichever shape matches this label wins.
function mascotAbove(page: Page, label: string) {
  return page.locator(`svg + p:has-text("${label}"), span:has-text("${label}") > svg`);
}

test('shell, loading and empty regions show the mascot, resting while nothing runs', async ({
  page,
}) => {
  // The shell's mascot; with nothing queued the piece rests (no is-typing anywhere).
  await page.goto(appUrl('/'));
  await expect(page.locator(`header ${MASCOT}`)).toBeVisible();
  await expect(page.getByText('Live', { exact: true })).toBeVisible();
  await expect(page.locator('.is-typing')).toHaveCount(0);

  // "Connecting…" then "Reconnecting…": the first stream is held unanswered, so the
  // machine stays in its initial connecting state (an aborted stream would drop it to
  // reconnecting immediately — the hold is what makes the label observable). Once let
  // go, the stream errors; while the route stays up every retry is still aborted, so the
  // region shows the mascot above "Reconnecting…". With the route gone, a retry
  // succeeds and the app goes live. (A live stream dropped by an idle connection cannot
  // be observed from the browser: the backend sends no keepalives, so an offline toggle
  // leaves a quiet stream looking connected; item 13's restart exercises that drop.)
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
