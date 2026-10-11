import { join } from 'node:path';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { FIXTURE_CLIPS, appUrl, loadState } from '../lib/backend.mjs';
import { expectLiveQueue, rowOrder } from './helpers/ui';

// A task's pending row by its filename.
function row(page: Page, filename: string): Locator {
  return page.getByRole('listitem').filter({ hasText: filename });
}

test('uploads several clips, queues them in order, and shows status, phase and both progress bars live', async ({
  page,
}) => {
  await page.goto(appUrl('/'));
  await expect(page.getByText('Live', { exact: true })).toBeVisible();

  const { fixturesDir } = loadState();
  await page.setInputFiles(
    'input[type="file"]',
    FIXTURE_CLIPS.map((name) => join(fixturesDir, name)),
  );

  // The dropped files are worked through one at a time, so the queue gets them in order;
  // the pending list keeps that order.
  await expect.poll(async () => rowOrder(page, FIXTURE_CLIPS)).toEqual([0, 1, 2]);

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
  // The diarization bar joins the row once it applies, and shows its stage label.
  await expect(a.getByRole('progressbar', { name: 'Speaker diarization' })).toBeVisible({
    timeout: 60_000,
  });
  await expect(a.getByText(/Embedding \d+%|Clustering \d+%/)).toBeVisible({ timeout: 60_000 });

  // The progress is live: the running row's transcription value moves (or the row leaves
  // RUNNING) instead of sitting still.
  const transcription = a.getByRole('progressbar', { name: 'Transcription' });
  const first = Number(await transcription.getAttribute('aria-valuenow'));
  await expect
    .poll(async () => {
      const running = await a
        .getByText('RUNNING', { exact: true })
        .isVisible()
        .catch(() => false);
      if (!running) {
        return -1;
      }
      const now = await transcription.getAttribute('aria-valuenow');
      return now === null ? -1 : Number(now);
    })
    .not.toBe(first);

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
