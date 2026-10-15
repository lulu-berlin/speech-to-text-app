import { existsSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { DELETE_CLIP, loadState, scratchDirPath } from '../lib/backend.mjs';
import { goTo, rowOf, taskIdOf, uploadAndOpen, uploadClips } from './helpers/ui';

test('deleting a task removes it from History and frees its file for re-upload', async ({
  page,
}) => {
  test.setTimeout(300_000);
  const dataDir = loadState().dataDir;

  // A completed task whose transcript was opened: the player's /audio serves the clip stored
  // in the database, so no playback file exists on disk.
  await uploadAndOpen(page, DELETE_CLIP);
  await goTo(page, 'History');
  const card = rowOf(page, DELETE_CLIP);
  const id = await taskIdOf(page, DELETE_CLIP);

  // Delete asks first; confirming removes the card, for good (a reload brings the list
  // back from the server). A completed task's work directory went when it completed and
  // stays gone.
  await card.getByRole('button', { name: 'Delete' }).click();
  await page
    .getByRole('dialog', { name: 'Delete task?' })
    .getByRole('button', { name: 'Delete' })
    .click();
  await expect(card).toHaveCount(0);
  await page.reload();
  await expect(page.getByText('Live', { exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'History' })).toBeVisible();
  await expect(card).toHaveCount(0);
  expect(existsSync(scratchDirPath(dataDir, id))).toBe(false);

  // The same bytes upload again as a new task (the deleted row no longer holds their
  // hash): accepted with no duplicate notice, and transcribed to completion. The card
  // in History is unambiguous — the old one was deleted — so it opens the fresh task,
  // whose transcript renders non-empty text. Its task id is not asserted: SQLite
  // reuses the rowid when the tasks table is empty, so a same-again id is not a stale
  // row. The phrase is not pinned either — a 6-11 s clip starts mid-sentence.
  await goTo(page, 'Queue');
  await uploadClips(page, [DELETE_CLIP]);
  await expect(page.getByText(`${DELETE_CLIP} transcribed`)).toBeVisible({ timeout: 240_000 });
  await expect(page.getByText(/Already uploaded/)).toHaveCount(0);
  // Open from the History card, not the finish notice: the first task's notice may
  // still be in the capped list, and its link would 404 (the row is gone).
  await goTo(page, 'History');
  await expect(card.getByText('COMPLETED', { exact: true })).toBeVisible();
  await card.getByRole('link', { name: DELETE_CLIP }).click();
  await expect(page.getByRole('heading', { name: DELETE_CLIP })).toBeVisible();
  const transcript = page.getByRole('region', { name: 'Transcript' });
  const firstTurn = transcript.locator('p').first();
  await expect(firstTurn).toBeVisible({ timeout: 30_000 });
  await expect(firstTurn).not.toBeEmpty();
});
