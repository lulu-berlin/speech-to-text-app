import { expect, test, type Page } from './helpers/test';
import { BAR_ROW_CLIP } from '../lib/backend.ts';
import { appLive, uploadClips, waitForTaskState } from './helpers/ui';

// Item 82: the phase line and the bar count belong to one page-side observation. Two separate
// checks raced the task's end — the `Diarizing…` text was visible, then the row left the queue
// before `toHaveCount(2)` could see the second bar (run 39978912415).
const PHASES = ['Chunking…', 'Transcribing…', 'Diarizing…'];
type BarObservation = {
  phase: string;
  bars: number;
  transcriptionBar: boolean;
  diarizationBar: boolean;
};
type BarObservationWindow = Window & {
  barObservation?: { observed: BarObservation[]; observer: MutationObserver };
};

// Installs a page-side collector for [filename]'s pending row: every DOM change samples the row
// once, so the phase text and the bars it carries are read together; a poll from the test would
// need two round trips and could see the row gone between them. The row is looked up on every
// sample, so the collector can start before the upload creates it.
async function startBarObservation(page: Page, filename: string) {
  await page.evaluate(
    ({ filename, phases }) => {
      const observed: BarObservation[] = [];
      const seen = new Set<string>();
      const sample = () => {
        // The queue shows an upload row and a pending-task row with the same filename while the
        // upload settles; only the pending task's row carries the phase line.
        const pendingTasks = document.querySelector('ul[aria-label="Pending tasks"]');
        const row = [...(pendingTasks ?? document).querySelectorAll('li')].find((item) =>
          item.textContent?.includes(filename),
        );
        if (row === undefined) {
          return;
        }
        const phase = phases.find((prefix) =>
          [...row.querySelectorAll('span')].some((span) =>
            (span.textContent ?? '').startsWith(prefix),
          ),
        );
        if (phase === undefined) {
          return;
        }
        const bars = [...row.querySelectorAll('[role="progressbar"]')];
        const observation = {
          phase,
          bars: bars.length,
          transcriptionBar: bars.some((bar) => bar.getAttribute('aria-label') === 'Transcription'),
          diarizationBar: bars.some(
            (bar) => bar.getAttribute('aria-label') === 'Speaker diarization',
          ),
        };
        const key = [
          observation.phase,
          observation.bars,
          observation.transcriptionBar,
          observation.diarizationBar,
        ].join('\u0000');
        if (seen.has(key)) {
          return;
        }
        seen.add(key);
        observed.push(observation);
      };
      sample();
      const observer = new MutationObserver(sample);
      observer.observe(document.body, { childList: true, characterData: true, subtree: true });
      (window as BarObservationWindow).barObservation = { observed, observer };
    },
    { filename, phases: PHASES },
  );
}

// Stops the page-side collector and returns the distinct observations it recorded.
async function stopBarObservation(page: Page): Promise<BarObservation[]> {
  return page.evaluate(() => {
    const state = (window as BarObservationWindow).barObservation;
    if (state === undefined) {
      throw new Error('no bar observation is running in this page');
    }
    state.observer.disconnect();
    delete (window as BarObservationWindow).barObservation;
    return state.observed;
  });
}

// Item 59, Proposal 3: a running row shows one visible progress bar, and the diarization bar
// joins only while the task is actually diarizing. The snapshot's TRANSCRIBING task carries
// diarizationProgress 0.0 with a null stage, so the row used to show a second, empty bar through
// chunking and transcription (queue-running-light-desktop.png).
test('a running task shows one progress bar until diarizing starts', async ({ page }) => {
  await appLive(page);

  await startBarObservation(page, BAR_ROW_CLIP);
  await uploadClips(page, [BAR_ROW_CLIP]);

  // The task's own finish signal: its row leaves the Pending tasks list once the task ends. The
  // observations below prove the row rendered at all, so nothing here reads the running row's text:
  // on a fast runner it can be gone between the state wait and the read (roadmap item 84, run
  // #481's class).
  const row = page
    .getByRole('list', { name: 'Pending tasks' })
    .getByRole('listitem')
    .filter({ hasText: BAR_ROW_CLIP });
  await waitForTaskState(page, BAR_ROW_CLIP, (task) => task.status === 'RUNNING');
  await waitForTaskState(page, BAR_ROW_CLIP);
  await expect(row).toHaveCount(0);
  const observed = await stopBarObservation(page);

  // One observation per fact: a task finishing between two checks cannot fail it.
  expect(
    observed.some(
      (entry) => entry.phase !== 'Diarizing…' && entry.bars === 1 && entry.transcriptionBar,
    ),
    `a chunking or transcribing observation with exactly the transcription bar: ${JSON.stringify(observed)}`,
  ).toBe(true);
  expect(
    observed.some(
      (entry) =>
        entry.phase === 'Diarizing…' &&
        entry.bars === 2 &&
        entry.transcriptionBar &&
        entry.diarizationBar,
    ),
    `a diarizing observation with both bars: ${JSON.stringify(observed)}`,
  ).toBe(true);
});
