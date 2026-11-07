import { expect, test } from './helpers/test';
import {
  PAUSE_QUEUED_CLIP,
  PAUSE_QUEUED_RUNNING_CLIP,
  PAUSE_QUEUED_SIGNAL_CLIP,
} from '../lib/backend.ts';
import { appLive, goTo, rowOf, uploadClips, waitForTaskState } from './helpers/ui';

// Pausing a queued upload (roadmap item 40, the review): the pause used to freeze "the
// current run" whichever task was paused, so pausing a task still waiting in the queue
// froze the one transcribing ahead of it, for good. Against the real backend, the running
// task must still complete, and the paused one must wait — not started — until it is
// resumed, then complete too.
test('pausing a queued upload leaves the transcribing one running; the paused one waits until resumed', async ({
  page,
}) => {
  await appLive(page);
  // The third clip queues behind the paused one: the worker starting it is the signal that it
  // looked at the queue again and skipped the paused task (roadmap item 85 replaced the spec's
  // 3 s window with that positive signal).
  await uploadClips(page, [PAUSE_QUEUED_RUNNING_CLIP, PAUSE_QUEUED_CLIP, PAUSE_QUEUED_SIGNAL_CLIP]);
  const running = rowOf(page, PAUSE_QUEUED_RUNNING_CLIP);
  const queued = rowOf(page, PAUSE_QUEUED_CLIP);
  const signal = rowOf(page, PAUSE_QUEUED_SIGNAL_CLIP);
  await waitForTaskState(
    page,
    PAUSE_QUEUED_RUNNING_CLIP,
    (task) => task.status === 'RUNNING' && task.phase === 'TRANSCRIBING',
  );
  await expect(running.getByText('Transcribing')).toBeVisible();

  // The second upload is still waiting behind the long one when it is paused.
  await expect(queued.getByText('QUEUED', { exact: true })).toBeVisible();
  await queued.getByRole('button', { name: `Pause ${PAUSE_QUEUED_CLIP}` }).click();
  await expect(queued.getByText('PAUSED', { exact: true })).toBeVisible();
  await expect(running.getByText(/Transcribing… \d+%/)).toBeVisible();

  // The transcribing task was not frozen: it still completes and leaves the queue.
  await waitForTaskState(page, PAUSE_QUEUED_RUNNING_CLIP);
  await expect(page.getByText(`${PAUSE_QUEUED_RUNNING_CLIP} transcribed`)).toBeVisible();
  await expect(running).toHaveCount(0);

  // The worker looked again as soon as the queue was free — it started the clip behind the paused
  // one — and it skipped the paused task: still PAUSED, never transcribing, whose Resume is offered.
  await waitForTaskState(page, PAUSE_QUEUED_SIGNAL_CLIP, (task) => task.status === 'RUNNING');
  await expect(queued.getByText('PAUSED', { exact: true })).toBeVisible();
  await expect(queued.getByText('Transcribing')).toHaveCount(0);
  await expect(queued.getByRole('button', { name: `Resume ${PAUSE_QUEUED_CLIP}` })).toBeVisible();

  // Resumed, it runs and completes, as does the clip that ran ahead of it.
  await queued.getByRole('button', { name: `Resume ${PAUSE_QUEUED_CLIP}` }).click();
  await waitForTaskState(page, PAUSE_QUEUED_CLIP);
  await waitForTaskState(page, PAUSE_QUEUED_SIGNAL_CLIP);
  await expect(page.getByText(`${PAUSE_QUEUED_CLIP} transcribed`)).toBeVisible();
  await expect(queued).toHaveCount(0);
  await expect(signal).toHaveCount(0);
  await goTo(page, 'History');
  for (const clip of [PAUSE_QUEUED_RUNNING_CLIP, PAUSE_QUEUED_CLIP, PAUSE_QUEUED_SIGNAL_CLIP]) {
    await expect(rowOf(page, clip).getByText('COMPLETED', { exact: true })).toBeVisible();
  }
});
