import { expect, test, type Locator } from '@playwright/test';
import { COLOR_ROW_CLIP, appUrl } from '../lib/backend.mjs';
import { appLive, rowOf, uploadClips } from './helpers/ui';

// Item 59, Proposal 3: the running status line's phase is shown by color — chunking,
// transcribing and diarizing each read differently, so the row says what it is doing
// before the percentage exists.
test('the running status line changes color between chunking and transcribing', async ({
  page,
}) => {
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

  await uploadClips(page, [COLOR_ROW_CLIP]);
  const row = rowOf(page, COLOR_ROW_CLIP);

  // The status line's color read in one DOM pass, keyed by its phase prefix (the elapsed
  // suffix means an exact text match would race the tick).
  const lineColor = (prefix: string) =>
    row.evaluate((listItem, text) => {
      const line = [...listItem.querySelectorAll('span')].find((element) =>
        element.textContent?.startsWith(text),
      );
      return line === undefined ? null : getComputedStyle(line).color;
    }, prefix);

  let chunkingColor: string | null = null;
  await expect
    .poll(async () => (chunkingColor = await lineColor('Chunking…')), {
      timeout: 120_000,
      intervals: [100],
    })
    .not.toBeNull();
  let transcribingColor: string | null = null;
  await expect
    .poll(async () => (transcribingColor = await lineColor('Transcribing…')), {
      timeout: 240_000,
      intervals: [100],
    })
    .not.toBeNull();

  expect(transcribingColor).not.toBe(chunkingColor);
});
