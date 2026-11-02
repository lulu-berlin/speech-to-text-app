import { expect, test } from './helpers/test';
import { ELAPSED_ROW_CLIP } from '../lib/backend.ts';
import { appLive, rowOf, uploadClips, waitForTaskState } from './helpers/ui';

// Item 59, Proposal 3: a running task's status line carries its elapsed time, so the row
// says how long the work has been going — the analysis's "no elapsed time" gap. The line
// is "Transcribing… 43% · 00:07"; the time ticks while the task runs.
test('a running task shows an advancing elapsed time on its status line', async ({ page }) => {
  await appLive(page);

  await uploadClips(page, [ELAPSED_ROW_CLIP]);
  const row = rowOf(page, ELAPSED_ROW_CLIP);
  const statusLine = row.getByText(/Transcribing… \d+% · \d{2}:\d{2}/);
  await waitForTaskState(
    page,
    ELAPSED_ROW_CLIP,
    (task) => task.status === 'RUNNING' && task.phase === 'TRANSCRIBING',
  );
  await expect(statusLine).toBeVisible();

  const elapsedSeconds = async () => {
    const text = (await statusLine.textContent()) ?? '';
    const match = /· (\d{2}):(\d{2})$/.exec(text);
    return match === null ? -1 : Number(match[1]) * 60 + Number(match[2]);
  };
  const first = await elapsedSeconds();
  expect(first, 'the elapsed field parses').toBeGreaterThanOrEqual(0);
  await expect.poll(elapsedSeconds, { timeout: 60_000, intervals: [500] }).toBeGreaterThan(first);
});
