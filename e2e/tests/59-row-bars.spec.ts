import { expect, test } from '@playwright/test';
import { BAR_ROW_CLIP, appUrl } from '../lib/backend.mjs';
import { appLive, rowOf, uploadClips } from './helpers/ui';

// Item 59, Proposal 3: a running row shows one visible progress bar, and the diarization
// bar joins only while the task is actually diarizing. The snapshot's TRANSCRIBING task
// carries diarizationProgress 0.0 with a null stage, so the row used to show a second,
// empty bar through chunking and transcription (queue-running-light-desktop.png).
test('a running task shows one progress bar until diarizing starts', async ({ page }) => {
  test.setTimeout(300_000);
  await appLive(page);

  // The journey's earlier uploads may still be finishing; start from an idle queue.
  await expect
    .poll(
      async () => {
        const body = (await (await page.request.get(appUrl('/api/tasks'))).json()) as {
          tasks: { status: string }[];
        };
        return body.tasks.filter((task) => ['QUEUED', 'RUNNING', 'PAUSED'].includes(task.status))
          .length;
      },
      { timeout: 120_000 },
    )
    .toBe(0);

  await uploadClips(page, [BAR_ROW_CLIP]);
  const row = rowOf(page, BAR_ROW_CLIP);
  await expect(row.getByText(/Chunking…|Transcribing… \d+%/)).toBeVisible({
    timeout: 120_000,
  });
  const bars = row.getByRole('progressbar');
  await expect(bars, 'one bar while chunking and transcribing').toHaveCount(1);
  await expect(row.getByRole('progressbar', { name: 'Transcription' })).toBeVisible();

  // The diarization bar joins when the task reaches its diarizing phase.
  await expect(row.getByText(/Diarizing… \d+%/)).toBeVisible({ timeout: 240_000 });
  await expect(bars, 'the diarization bar appears while diarizing').toHaveCount(2);
  await expect(row.getByRole('progressbar', { name: 'Speaker diarization' })).toBeVisible();
});
