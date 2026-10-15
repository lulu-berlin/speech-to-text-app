import { existsSync, readdirSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { BROKEN_CLIP, CANCEL_CLIP, appUrl, loadState, scratchDirPath } from '../lib/backend.mjs';
import { appLive, goTo, rowOf, taskIdOf, uploadClips } from './helpers/ui';

// The entries left in a task's scratch directory (the app's OS-temp area, one per data directory); none
// when the directory is gone — the app removes it when the task ends (item 44).
function scratchEntries(id: string): string[] {
  const dir = scratchDirPath(loadState().dataDir, id);
  return existsSync(dir) ? readdirSync(dir).sort() : [];
}

test('cancelling a running task, retrying it, and retrying a failed one work through the UI', async ({
  page,
}) => {
  test.setTimeout(360_000);
  const pause = page.getByRole('switch', { name: 'Pause the queue' });

  // Cancel a running task once it is past chunking (its row says "Transcribing"): the
  // running task asks for confirmation, then leaves the queue as CANCELED. Its scratch
  // directory goes with the cancel; the audio itself lives in the database (item 44).
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
  expect(scratchEntries(canceledId)).toEqual([]);

  // Retrying the canceled task runs it again, to completion; a completed task's scratch
  // directory is removed too.
  await canceled.getByRole('button', { name: 'Retry' }).click();
  await expect(canceled.getByText('COMPLETED', { exact: true })).toBeVisible({
    timeout: 240_000,
  });
  expect(scratchEntries(canceledId)).toEqual([]);

  // A file that cannot be decoded fails in chunking: its card shows the stored error, and
  // its scratch directory is removed with the failure — its stored original waits in the
  // database for a retry.
  await goTo(page, 'Queue');
  await uploadClips(page, [BROKEN_CLIP]);
  await goTo(page, 'History');
  const broken = rowOf(page, BROKEN_CLIP);
  await expect(broken.getByText('FAILED', { exact: true })).toBeVisible({ timeout: 60_000 });
  const brokenId = await taskIdOf(page, BROKEN_CLIP);
  const stored = await (await page.request.get(appUrl(`/api/tasks/${brokenId}`))).json();
  expect(stored.error).toBeTruthy();
  await expect(broken).toContainText(stored.error);
  expect(scratchEntries(brokenId)).toEqual([]);

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
  expect(scratchEntries(brokenId)).toEqual([]);
});
