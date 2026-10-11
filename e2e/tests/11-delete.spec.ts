import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { DELETE_CLIP, loadState } from '../lib/backend.mjs';
import { uploadAndOpen, uploadClips } from './helpers/ui';

// A task's History card, by its filename.
function cardOf(page: Page, filename: string): Locator {
  return page.getByRole('listitem').filter({ hasText: filename });
}

// Switches view through the header navigation (finish notices carry their own links).
async function goTo(page: Page, view: 'Queue' | 'History') {
  await page.getByRole('navigation').getByRole('link', { name: view }).click();
}

// The files the backend keeps for a task outside the database: its cached joined FLAC
// ($STT_DATA_DIR/tmp/<id>.flac, built on the first /audio request) and its work directory.
// The task id behind a History card, from its link to the transcript.
async function taskIdOf(page: Page, filename: string): Promise<string> {
  const href = await cardOf(page, filename)
    .getByRole('link', { name: filename })
    .getAttribute('href');
  return href?.split('/').pop() ?? '';
}

function cachedAudioOf(id: string): string {
  return join(loadState().dataDir, 'tmp', `${id}.flac`);
}
function workDirOf(id: string): string {
  return join(loadState().dataDir, 'work', id);
}

test('deleting a task removes it from History and frees its file for re-upload', async ({
  page,
}) => {
  test.setTimeout(300_000);

  // A completed task whose transcript was opened: the player's /audio built the cache.
  await uploadAndOpen(page, DELETE_CLIP);
  await goTo(page, 'History');
  const card = cardOf(page, DELETE_CLIP);
  const id = await taskIdOf(page, DELETE_CLIP);
  await expect.poll(() => existsSync(cachedAudioOf(id))).toBe(true);

  // Delete asks first; confirming removes the card, for good (a reload brings the list
  // back from the server), and the cached audio with it. A completed task's work
  // directory went when it completed and stays gone.
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
  expect(existsSync(cachedAudioOf(id))).toBe(false);
  expect(existsSync(workDirOf(id))).toBe(false);

  // The same bytes upload again as a new task (the deleted row no longer holds their
  // hash): accepted with no duplicate notice, transcribed to completion, and the fresh
  // task opens with a real transcript. Its task id is not asserted — SQLite reuses the
  // rowid when the tasks table is empty, so a same-again id is not a stale row.
  await goTo(page, 'Queue');
  await uploadClips(page, [DELETE_CLIP]);
  await expect(page.getByText(`${DELETE_CLIP} transcribed`)).toBeVisible({ timeout: 240_000 });
  await expect(page.getByText(/Already uploaded/)).toHaveCount(0);
  // The History card is the fresh task's — the old one was just deleted — so open it
  // there, not from the finish notice: the first task's notice may still be in the
  // capped list, and its link would 404 (the row is gone). The fresh task transcribed
  // for real: its transcript renders non-empty text (the phrase is not pinned — a
  // 6-11 s clip starts mid-sentence).
  await goTo(page, 'History');
  await expect(card.getByText('COMPLETED', { exact: true })).toBeVisible();
  await card.getByRole('link', { name: DELETE_CLIP }).click();
  await expect(page.getByRole('heading', { name: DELETE_CLIP })).toBeVisible();
  const transcript = page.getByRole('region', { name: 'Transcript' });
  const firstTurn = transcript.locator('p').first();
  await expect(firstTurn).toBeVisible({ timeout: 30_000 });
  await expect(firstTurn).not.toBeEmpty();
});
