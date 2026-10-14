import { expect, test, type Locator, type Page } from '@playwright/test';
import { QUEUE_CLIPS } from '../lib/backend.mjs';
import { appLive, expectLiveQueue, rowOrder, uploadClips } from './helpers/ui';

// A task's pending row by its filename.
function row(page: Page, filename: string): Locator {
  return page.getByRole('listitem').filter({ hasText: filename });
}

test('uploads several clips, queues them in order, and shows status, phase and both progress bars live', async ({
  page,
}) => {
  await appLive(page);
  await uploadClips(page, QUEUE_CLIPS);

  // The dropped files are worked through one at a time, so the queue gets them in order;
  // the pending list keeps that order.
  await expect.poll(async () => rowOrder(page, QUEUE_CLIPS)).toEqual([0, 1, 2]);

  // While the first clip runs, the second still waits: the engine walks the queue in
  // order. The running row shows its status badge, one of the three phase labels, and
  // both progress bars — the transcription percentage as a visible value.
  const a = row(page, 'jfk-a.wav');
  const b = row(page, 'jfk-b.wav');
  await expect(a.getByText('RUNNING', { exact: true })).toBeVisible({ timeout: 60_000 });
  await expect(b.getByText('QUEUED', { exact: true })).toBeVisible();
  await expect(a.getByText(/Chunking|Transcribing|Diarizing/)).toBeVisible();
  await expect(a.getByRole('progressbar', { name: 'Transcription' })).toBeVisible();
  await expect(a.getByText(/\d+%/)).toBeVisible();
  // The progress is live: sample the transcription value from the moment the bars appear — the
  // row is still CHUNKING or TRANSCRIBING. The diarization bar below renders only once DIARIZING
  // starts, and by then the transcription value is already 100 (the engine weights the composite
  // progress and every chunk is DONE), so a sample taken after that wait could never move. Two
  // distinct observations — 0 → 25 → … → 100, or 0 → 100 for a one-chunk clip — prove the bar is
  // live; a bar stuck at 0 or at 100 never produces them.
  const transcription = a.getByRole('progressbar', { name: 'Transcription' });
  const seen = new Set<string>();
  await expect
    .poll(
      async () => {
        const now = await transcription.getAttribute('aria-valuenow');
        if (now !== null) {
          seen.add(now);
        }
        return seen.size;
      },
      { timeout: 60_000 },
    )
    .toBeGreaterThan(1);

  // The diarization bar joins the row once it applies, and shows its stage label.
  await expect(a.getByRole('progressbar', { name: 'Speaker diarization' })).toBeVisible({
    timeout: 60_000,
  });
  await expect(a.getByText(/Embedding \d+%|Clustering \d+%/)).toBeVisible({ timeout: 60_000 });

  // The queue drains in order (the engine runs one task at a time): each finish notice
  // appears when its task completes — a's before b's, b's before c's. Completed rows
  // leave the pending list the moment they finish, so the notices are the completion
  // signal. Once the queue is empty, the drop zone returns to its empty state.
  const notice = (name: string) => page.getByText(`${name} transcribed`);
  await expect(notice('jfk-a.wav')).toBeVisible({ timeout: 120_000 });
  await expect(notice('jfk-b.wav')).not.toBeVisible();
  await expect(notice('jfk-c.wav')).not.toBeVisible();
  await expect(notice('jfk-b.wav')).toBeVisible();
  await expect(notice('jfk-c.wav')).not.toBeVisible();
  await expect(notice('jfk-c.wav')).toBeVisible();
  await expectLiveQueue(page);
});
