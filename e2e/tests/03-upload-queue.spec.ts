import { expect, test, type Locator, type Page } from '@playwright/test';
import { QUEUE_CLIPS } from '../lib/backend.mjs';
import { appLive, expectLiveQueue, rowOrder, uploadClips } from './helpers/ui';

declare global {
  interface Window {
    // The distinct transcription percentages the recorder below has seen for the sampled row.
    transcriptionValues: Set<string>;
  }
}

// A task's pending row by its filename.
function row(page: Page, filename: string): Locator {
  return page.getByRole('listitem').filter({ hasText: filename });
}

// Records the transcription percentages the pending row for `filename` shows, from before its task
// starts until the test reads the set: the sampler runs in the page on every attribute change, so it
// sees the bar's whole life. A poll started after the row's state has been awaited can arrive when the
// clip — about a second of whisper on one chunk — is already done, which is how CI run 38661372993 saw
// a single value. A bar stuck at 0 or at 100 still records one value and still fails.
async function recordTranscriptionPercentages(page: Page, filename: string): Promise<void> {
  await page.evaluate((name) => {
    window.transcriptionValues = new Set<string>();
    const sample = () => {
      const bars = document.querySelectorAll('[role="progressbar"][aria-label="Transcription"]');
      for (const bar of bars) {
        if (bar.closest('li')?.textContent?.includes(name)) {
          window.transcriptionValues.add(bar.getAttribute('aria-valuenow') ?? '');
        }
      }
    };
    new MutationObserver(sample).observe(document.documentElement, {
      subtree: true,
      childList: true,
      attributes: true,
      attributeFilter: ['aria-valuenow'],
    });
    sample();
  }, filename);
}

test('uploads several clips, queues them in order, and shows status, phase and both progress bars live', async ({
  page,
}) => {
  await appLive(page);
  await recordTranscriptionPercentages(page, 'jfk-a.wav');
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
  // The progress is live: the recorder has seen more than one percentage for a's bar since before the
  // uploads — 0 → 25 → … → 100, or 0 → 100 for a one-chunk clip — so a bar stuck at 0 or at 100 still
  // fails. It is a claim about what the bar did, not about when the test looked, which is what the
  // sampled-window version got wrong (CI run 38661372993, 2026-10-15).
  await expect
    .poll(() => page.evaluate(() => window.transcriptionValues.size), { timeout: 60_000 })
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
