import { expect, test } from '@playwright/test';
import { PAUSE_QUEUED_CLIP, PAUSE_QUEUED_RUNNING_CLIP } from '../lib/backend.ts';
import { appLive, goTo, rowOf, uploadClips, waitForTranscription } from './helpers/ui';

// Pausing a queued upload (roadmap item 40, the review): the pause used to freeze "the
// current run" whichever task was paused, so pausing a task still waiting in the queue
// froze the one transcribing ahead of it, for good. Against the real backend, the running
// task must still complete, and the paused one must wait — not started — until it is
// resumed, then complete too.
test('pausing a queued upload leaves the transcribing one running; the paused one waits until resumed', async ({
  page,
}) => {
  await appLive(page);
  await uploadClips(page, [PAUSE_QUEUED_RUNNING_CLIP, PAUSE_QUEUED_CLIP]);
  const running = rowOf(page, PAUSE_QUEUED_RUNNING_CLIP);
  const queued = rowOf(page, PAUSE_QUEUED_CLIP);
  await expect(running.getByText('Transcribing')).toBeVisible({ timeout: 120_000 });

  // The second upload is still waiting behind the long one when it is paused.
  await expect(queued.getByText('QUEUED', { exact: true })).toBeVisible();
  await queued.getByRole('button', { name: `Pause ${PAUSE_QUEUED_CLIP}` }).click();
  await expect(queued.getByText('PAUSED', { exact: true })).toBeVisible();
  await expect(running.getByText(/Transcribing… \d+%/)).toBeVisible();

  // The transcribing task was not frozen: it still completes and leaves the queue.
  await waitForTranscription(page, PAUSE_QUEUED_RUNNING_CLIP);
  await expect(page.getByText(`${PAUSE_QUEUED_RUNNING_CLIP} transcribed`)).toBeVisible();
  await expect(running).toHaveCount(0);

  // The paused task is not started once the queue is free: give the worker time to look
  // again, and it is still PAUSED with no phase (it never ran).
  await page.waitForTimeout(3_000);
  await expect(queued.getByText('PAUSED', { exact: true })).toBeVisible();
  await expect(queued.getByText('Transcribing')).toHaveCount(0);
  await expect(queued.getByRole('button', { name: `Resume ${PAUSE_QUEUED_CLIP}` })).toBeVisible();

  // Resumed, it runs and completes.
  await queued.getByRole('button', { name: `Resume ${PAUSE_QUEUED_CLIP}` }).click();
  await waitForTranscription(page, PAUSE_QUEUED_CLIP);
  await expect(page.getByText(`${PAUSE_QUEUED_CLIP} transcribed`)).toBeVisible();
  await expect(queued).toHaveCount(0);
  await goTo(page, 'History');
  for (const clip of [PAUSE_QUEUED_RUNNING_CLIP, PAUSE_QUEUED_CLIP]) {
    await expect(rowOf(page, clip).getByText('COMPLETED', { exact: true })).toBeVisible();
  }
});
