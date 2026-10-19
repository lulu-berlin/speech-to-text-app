import { expect, test } from '@playwright/test';
import { QUEUED_ROW_CLIP, RUNNING_ROW_CLIP, appUrl } from '../lib/backend.mjs';
import { appLive, rowOf, uploadClips } from './helpers/ui';

// Item 59, finding 3: a running row showed the phase text and an all-caps RUNNING badge
// next to two thin unlabeled bars, so it read as stuck. Decided behaviour: one human status
// line ("Transcribing… 43%"), no RUNNING badge; QUEUED and PAUSED keep their badge, where
// nothing else says the state; while chunking the bar is indeterminate instead of 0 %.
test('a running task shows one phase-and-percentage line without a RUNNING badge, and QUEUED/PAUSED keep theirs', async ({
  page,
}) => {
  test.setTimeout(420_000);
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

  // Both clips wait in a paused queue: the QUEUED badge is what says so.
  const queueSwitch = page.getByRole('switch', { name: 'Pause the queue' });
  await queueSwitch.click();
  await expect(queueSwitch).toHaveAttribute('aria-checked', 'true');
  await uploadClips(page, [RUNNING_ROW_CLIP, QUEUED_ROW_CLIP]);
  const running = rowOf(page, RUNNING_ROW_CLIP);
  const queued = rowOf(page, QUEUED_ROW_CLIP);
  await expect(running.getByText('QUEUED', { exact: true })).toBeVisible({ timeout: 60_000 });
  await expect(queued.getByText('QUEUED', { exact: true })).toBeVisible();

  // A queued task paused by hand keeps its PAUSED badge.
  await queued.getByRole('button', { name: `Pause ${QUEUED_ROW_CLIP}` }).click();
  await expect(queued.getByText('PAUSED', { exact: true })).toBeVisible();

  // Resume: the long clip starts. Its phase text arrives with no RUNNING badge beside it.
  await queueSwitch.click();
  await expect(queueSwitch).toHaveAttribute('aria-checked', 'false');
  await expect(running.getByText('Chunking', { exact: false })).toBeVisible({
    timeout: 120_000,
  });
  await expect(running.getByText('RUNNING', { exact: true })).toHaveCount(0);

  // While chunking, the bar is indeterminate — no value — and the row does not read 0 %.
  const transcription = running.getByRole('progressbar', { name: 'Transcription' });
  let chunkingRowText = '';
  await expect
    .poll(
      async () => {
        chunkingRowText = (await running.textContent()) ?? '';
        if (!/Chunking…/.test(chunkingRowText)) {
          return 'not chunking';
        }
        const value = await transcription.getAttribute('aria-valuenow');
        return value === null ? 'indeterminate' : `valued ${value}`;
      },
      { timeout: 180_000, intervals: [100] },
    )
    .toBe('indeterminate');
  expect(chunkingRowText).not.toContain('0%');

  // Transcribing: one visible line carries the phase and the percentage, and no RUNNING
  // badge stands beside it.
  const statusLine = running.getByText(/Transcribing… \d+%/);
  await expect(statusLine).toBeVisible({ timeout: 240_000 });
  await expect(running.getByText('RUNNING', { exact: true })).toHaveCount(0);
  const percent = async () => {
    const text = (await statusLine.textContent()) ?? '';
    return Number(/(\d+)%/.exec(text)?.[1] ?? -1);
  };
  const first = await percent();
  expect(first).toBeGreaterThanOrEqual(0);
  await expect.poll(percent, { timeout: 120_000, intervals: [500] }).toBeGreaterThan(first);
});
