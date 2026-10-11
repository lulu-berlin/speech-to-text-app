import { join } from 'node:path';
import { expect, test } from '@playwright/test';
import { REORDER_CLIPS, appUrl, loadState } from '../lib/backend.mjs';
import { rowOrder } from './helpers/ui';

test('the row menu reorders the queue and the order survives a reload', async ({ page }) => {
  await page.goto(appUrl('/'));
  await expect(page.getByText('Live', { exact: true })).toBeVisible();

  // Pause the queue first: while processing is paused every task stays QUEUED, so the
  // reorder has a stable window instead of racing the engine.
  const pause = page.getByRole('switch', { name: 'Pause the queue' });
  await pause.click();
  await expect(pause).toBeChecked();

  const { fixturesDir } = loadState();
  await page.setInputFiles(
    'input[type="file"]',
    REORDER_CLIPS.map((name) => join(fixturesDir, name)),
  );
  await expect.poll(async () => rowOrder(page, REORDER_CLIPS)).toEqual([0, 1, 2]);

  // Move the first clip down one slot with its row menu: the UI reflects the new order
  // (PUT /api/queue/order round-trip through the live queue state).
  await page.getByRole('button', { name: 'Actions for jfk-d.wav' }).click();
  await page.getByRole('menuitem', { name: 'Move down' }).click();
  await expect.poll(async () => rowOrder(page, REORDER_CLIPS)).toEqual([1, 0, 2]);

  // The order survives a reload: the snapshot restores the queue exactly as the server
  // persisted it.
  await page.reload();
  await expect(page.getByText('Live', { exact: true })).toBeVisible();
  await expect.poll(async () => rowOrder(page, REORDER_CLIPS)).toEqual([1, 0, 2]);
});
