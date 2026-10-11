import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { BROKEN_CLIP, CANCEL_CLIP, appUrl, loadState } from '../lib/backend.mjs';
import { appLive, uploadClips } from './helpers/ui';

// The entries left in a task's work directory ($STT_DATA_DIR/work/<id>); none when the
// directory is gone.
function workDirEntries(id: string): string[] {
  const dir = join(loadState().dataDir, 'work', id);
  return existsSync(dir) ? readdirSync(dir).sort() : [];
}

// A task's row: a pending row in the Queue, a finished card in History.
function rowOf(page: Page, filename: string): Locator {
  return page.getByRole('listitem').filter({ hasText: filename });
}

// The task id behind a History card, from its link to the transcript.
async function taskIdOf(page: Page, filename: string): Promise<string> {
  const href = await rowOf(page, filename)
    .getByRole('link', { name: filename })
    .getAttribute('href');
  return href?.split('/').pop() ?? '';
}

// Switches view through the header navigation (finish notices carry their own links).
async function goTo(page: Page, view: 'Queue' | 'History') {
  await page.getByRole('navigation').getByRole('link', { name: view }).click();
}

test('cancelling a running task, retrying it, and retrying a failed one work through the UI', async ({
  page,
}) => {
  test.setTimeout(360_000);
  const pause = page.getByRole('switch', { name: 'Pause the queue' });

  // Cancel a running task once it is past chunking (its row says "Transcribing"): the
  // running task asks for confirmation, then leaves the queue as CANCELED. Its work
  // directory keeps only an unchunked original (ADR 0024); a chunked task has none left,
  // so the directory is empty — item 11's cleanup, checked on disk.
  await appLive(page);
  await uploadClips(page, [CANCEL_CLIP]);
  const pending = rowOf(page, CANCEL_CLIP);
  await expect(pending.getByText('Transcribing')).toBeVisible({ timeout: 120_000 });
  await page.getByRole('button', { name: `Cancel ${CANCEL_CLIP}` }).click();
  await page
    .getByRole('dialog', { name: `Cancel ${CANCEL_CLIP}?` })
    .getByRole('button', { name: 'Cancel task' })
    .click();
  await expect(pending).toHaveCount(0);
  await goTo(page, 'History');
  const canceled = rowOf(page, CANCEL_CLIP);
  await expect(canceled.getByText('CANCELED', { exact: true })).toBeVisible();
  const canceledId = await taskIdOf(page, CANCEL_CLIP);
  expect(workDirEntries(canceledId)).toEqual([]);

  // Retrying the canceled task runs it again, to completion; a completed task's work
  // directory is removed.
  await canceled.getByRole('button', { name: 'Retry' }).click();
  await expect(canceled.getByText('COMPLETED', { exact: true })).toBeVisible({
    timeout: 240_000,
  });
  expect(workDirEntries(canceledId)).toEqual([]);

  // A file that cannot be decoded fails in chunking: its card shows the stored error, and
  // its work directory keeps only the original, which a retry needs.
  await goTo(page, 'Queue');
  await uploadClips(page, [BROKEN_CLIP]);
  await goTo(page, 'History');
  const broken = rowOf(page, BROKEN_CLIP);
  await expect(broken.getByText('FAILED', { exact: true })).toBeVisible({ timeout: 60_000 });
  const brokenId = await taskIdOf(page, BROKEN_CLIP);
  const stored = await (await page.request.get(appUrl(`/api/tasks/${brokenId}`))).json();
  expect(stored.error).toBeTruthy();
  await expect(broken).toContainText(stored.error);
  expect(workDirEntries(brokenId)).toEqual(['source']);

  // Retrying the failed task is accepted: with the queue paused it waits in the queue as
  // QUEUED; once the queue runs, it fails again (its bytes are still not audio).
  await goTo(page, 'Queue');
  await pause.click();
  await expect(pause).toBeChecked();
  await goTo(page, 'History');
  await broken.getByRole('button', { name: 'Retry' }).click();
  await goTo(page, 'Queue');
  const retried = rowOf(page, BROKEN_CLIP);
  await expect(retried.getByText('QUEUED', { exact: true })).toBeVisible();
  await pause.click();
  await expect(pause).not.toBeChecked();
  await expect(retried).toHaveCount(0, { timeout: 60_000 });
  await goTo(page, 'History');
  await expect(broken.getByText('FAILED', { exact: true })).toBeVisible();
  expect(workDirEntries(brokenId)).toEqual(['source']);
});
