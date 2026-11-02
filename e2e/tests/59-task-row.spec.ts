import { expect, test } from './helpers/test';
import { QUEUED_ROW_CLIP, RUNNING_ROW_CLIP } from '../lib/backend.ts';
import { appLive, rowOf, uploadClips, waitForTaskState } from './helpers/ui';

// Item 59, finding 3: a running row showed the phase text and an all-caps RUNNING badge
// next to two thin unlabeled bars, so it read as stuck. Decided behaviour: one human status
// line ("Transcribing… 43%"), no RUNNING badge; QUEUED and PAUSED keep their badge, where
// nothing else says the state; while chunking the bar is indeterminate instead of 0 %.
//
// The phases a row passes through are short — on a fast runner the whole task can finish
// between two polls, which is how run #481 failed with "completed before reaching the state
// the wait wanted" — so the row is observed page-side: every DOM change samples it once, and
// the assertions read that record (the pattern 59-row-bars and 59-row-color use).
type RowSample = { text: string; transcription: string | null };
type SampleWindow = { samples: RowSample[]; observer: MutationObserver };
declare global {
  interface Window {
    rowSamples?: SampleWindow;
  }
}

async function startRowSamples(page: Parameters<typeof appLive>[0], filename: string) {
  await page.evaluate((name) => {
    const samples: RowSample[] = [];
    const sample = () => {
      // The queue shows an upload row and a pending-task row with the same filename while the
      // upload settles; only the pending task's row carries the phase line.
      const pending = document.querySelector('ul[aria-label="Pending tasks"]');
      const row = [...(pending ?? document).querySelectorAll('li')].find((item) =>
        item.textContent?.includes(name),
      );
      if (row === undefined) {
        return;
      }
      const bar = row.querySelector('[role="progressbar"][aria-label="Transcription"]');
      samples.push({
        text: (row.textContent ?? '').replace(/\s+/g, ' ').trim(),
        transcription: bar?.getAttribute('aria-valuenow') ?? null,
      });
    };
    const observer = new MutationObserver(sample);
    observer.observe(document.body, {
      childList: true,
      characterData: true,
      subtree: true,
      attributes: true,
    });
    window.rowSamples = { samples, observer };
  }, filename);
}

async function stopRowSamples(page: Parameters<typeof appLive>[0]): Promise<RowSample[]> {
  return page.evaluate(() => {
    const state = window.rowSamples;
    state?.observer.disconnect();
    // The run ends with the row gone; the last sample per distinct text is enough.
    const seen = new Set<string>();
    const distinct: RowSample[] = [];
    for (const sample of state?.samples ?? []) {
      if (seen.has(sample.text)) {
        continue;
      }
      seen.add(sample.text);
      distinct.push(sample);
    }
    return distinct;
  });
}

test('a running task shows one phase-and-percentage line without a RUNNING badge, and QUEUED/PAUSED keep theirs', async ({
  page,
}) => {
  await appLive(page);

  // Both clips wait in a paused queue: the QUEUED badge is what says so.
  const queueSwitch = page.getByRole('switch', { name: 'Pause the queue' });
  await queueSwitch.click();
  await expect(queueSwitch).toHaveAttribute('aria-checked', 'true');
  await uploadClips(page, [RUNNING_ROW_CLIP, QUEUED_ROW_CLIP]);
  const running = rowOf(page, RUNNING_ROW_CLIP);
  const queued = rowOf(page, QUEUED_ROW_CLIP);
  await waitForTaskState(page, RUNNING_ROW_CLIP, (task) => task.status === 'QUEUED');
  await expect(running.getByText('QUEUED', { exact: true })).toBeVisible();
  await expect(queued.getByText('QUEUED', { exact: true })).toBeVisible();

  // A queued task paused by hand keeps its PAUSED badge.
  await queued.getByRole('button', { name: `Pause ${QUEUED_ROW_CLIP}` }).click();
  await expect(queued.getByText('PAUSED', { exact: true })).toBeVisible();

  // Resume: the long clip starts and runs to its end while the page-side collector watches it.
  await startRowSamples(page, RUNNING_ROW_CLIP);
  await queueSwitch.click();
  await expect(queueSwitch).toHaveAttribute('aria-checked', 'false');
  await waitForTaskState(page, RUNNING_ROW_CLIP);
  const samples = await stopRowSamples(page);

  // Chunking: a human phase line, no RUNNING badge beside it, and an indeterminate bar that
  // does not read 0 %.
  const chunking = samples.filter((sample) => /Chunking…/.test(sample.text));
  expect(chunking, 'the row rendered its chunking phase').not.toHaveLength(0);
  for (const sample of chunking) {
    expect(sample.text, 'no RUNNING badge while the phase line says the state').not.toMatch(
      /\bRUNNING\b/,
    );
    expect(sample.text, 'chunking does not read 0 %').not.toContain('0%');
    expect(sample.transcription, 'the chunking bar is indeterminate').toBeNull();
  }

  // Transcribing: one line carries the phase and the percentage, no RUNNING badge, and the
  // percentage advances as the task does.
  const percents = samples
    .map((sample) => /Transcribing… (\d+)%/.exec(sample.text))
    .filter((match): match is RegExpExecArray => match !== null)
    .map((match) => Number(match[1]));
  expect(percents.length, 'the row rendered its transcribing phase').toBeGreaterThan(0);
  for (const sample of samples.filter((s) => /Transcribing…/.test(s.text))) {
    expect(sample.text, 'no RUNNING badge while the phase line says the state').not.toMatch(
      /\bRUNNING\b/,
    );
  }
  // The record holds one sample per DOM change, so "advances" means the row rendered more than one
  // percentage and the last one is past the first — not merely that it reached a non-zero value.
  const distinct = [...new Set(percents)].sort((left, right) => left - right);
  expect(distinct.length, 'the percentage was rendered more than once').toBeGreaterThan(1);
  expect(
    Math.max(...percents),
    `the percentage advances (${distinct.join(', ')} %)`,
  ).toBeGreaterThan(Math.min(...percents));
});
