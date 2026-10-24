import { expect, test, type Page } from '@playwright/test';
import { QUEUE_CLIPS } from '../lib/backend.ts';
import { appLive, expectLiveQueue, uploadClips } from './helpers/ui';

// What one pending row showed at one moment: its text and the names of its progress bars.
interface RowState {
  text: string;
  bars: string[];
}

// The pending list at one moment: each watched row's index among the list items (-1 while it is
// not rendered) and its state (null while it is not pending).
interface QueueState {
  order: number[];
  rows: (RowState | null)[];
}

declare global {
  interface Window {
    // Every distinct state of the watched rows the recorder below has seen, in order.
    queueStates: QueueState[];
    // The distinct transcription percentages the recorder has seen for the first watched row.
    transcriptionValues: Set<string>;
    // The watched files whose finish notice has appeared, in the order they appeared.
    noticeOrder: string[];
  }
}

// Records what the pending rows for `names` show, from before their tasks start until the test
// reads it: the sampler runs in the page on every DOM change, so it sees each row's whole life. A
// check that waits for a state to be on screen can arrive after a short clip has already left it —
// CI run 38661372993 saw a single transcription percentage that way, and run 38848511391 missed
// the diarization stage label of a clip diarized in 0.3 s — so the assertions are claims about what
// the rows showed, not about when the test looked. A bar stuck at 0 or at 100 still records one
// percentage and still fails.
async function recordQueue(page: Page, names: string[]): Promise<void> {
  await page.evaluate((watched) => {
    window.queueStates = [];
    window.transcriptionValues = new Set<string>();
    window.noticeOrder = [];
    // A pending row's marker: its badge for QUEUED/PAUSED, or the running status line
    // (item 59 dropped the RUNNING badge in favour of "Transcribing… 42%").
    const pending = /QUEUED|PAUSED|Chunking…|Transcribing…|Diarizing…/;
    let last = '';
    const sample = () => {
      const items = [...document.querySelectorAll('li')];
      const rows = watched.map((name) => {
        const item = items.find(
          (li) => li.textContent?.includes(name) && pending.test(li.textContent),
        );
        if (item === undefined) {
          return null;
        }
        const bars = [...item.querySelectorAll('[role="progressbar"]')].map(
          (bar) => bar.getAttribute('aria-label') ?? '',
        );
        if (name === watched[0]) {
          const transcription = item.querySelector(
            '[role="progressbar"][aria-label="Transcription"]',
          );
          if (transcription !== null) {
            window.transcriptionValues.add(transcription.getAttribute('aria-valuenow') ?? '');
          }
        }
        return { text: item.textContent ?? '', bars };
      });
      const order = watched.map((name) =>
        items.findIndex((li) => li.textContent?.includes(name) ?? false),
      );
      const state = { order, rows };
      const key = JSON.stringify(state);
      if (key !== last) {
        last = key;
        window.queueStates.push(state);
      }
      const text = document.body.textContent ?? '';
      for (const name of watched) {
        if (!window.noticeOrder.includes(name) && text.includes(`${name} transcribed`)) {
          window.noticeOrder.push(name);
        }
      }
    };
    new MutationObserver(sample).observe(document.documentElement, {
      subtree: true,
      childList: true,
      characterData: true,
      attributes: true,
      attributeFilter: ['aria-valuenow'],
    });
    sample();
  }, QUEUE_CLIPS);
}

test('uploads several clips, queues them in order, and shows status, phase and both progress bars live', async ({
  page,
}) => {
  await appLive(page);
  await recordQueue(page, QUEUE_CLIPS);
  await uploadClips(page, QUEUE_CLIPS);

  // The queue drains in order (the engine runs one task at a time): each finish notice appears
  // when its task completes — a's, then b's, then c's. Once the queue is empty, the drop zone
  // returns to its empty state.
  await expect
    .poll(() => page.evaluate(() => window.noticeOrder), { timeout: 240_000 })
    .toEqual(QUEUE_CLIPS);
  await expectLiveQueue(page);

  // What the pending list showed on the way there, from the recorder.
  const states = await page.evaluate(() => window.queueStates);
  const saw = (holds: (rows: (RowState | null)[], order: number[]) => boolean) =>
    states.some(({ rows, order }) => holds(rows, order));
  const shows = (row: RowState | null | undefined, text: string | RegExp) =>
    row != null && (typeof text === 'string' ? row.text.includes(text) : text.test(row.text));

  // The dropped files are worked through one at a time, so the queue got them in order; the
  // pending list kept that order.
  expect(
    saw((_, order) => order.join() === '0,1,2'),
    'the three rows pending in upload order',
  ).toBe(true);
  // While the first clip ran, the second waited: the engine walks the queue in order. The
  // running row's phase-with-percentage line is its running state (item 59: no badge).
  expect(
    saw(
      (rows) =>
        shows(rows[0], /Chunking…|Transcribing… \d+%|Diarizing… \d+%/) && shows(rows[1], 'QUEUED'),
    ),
    'jfk-a.wav running while jfk-b.wav was QUEUED',
  ).toBe(true);
  expect(
    saw((rows) => shows(rows[0], 'RUNNING')),
    'no RUNNING badge on the row',
  ).toBe(false);
  // Its transcription bar is there, and the status line carries the percentage.
  expect(
    saw(([a]) => a?.bars.includes('Transcription') === true && shows(a, /\d+%/)),
    'jfk-a.wav with its status line and its transcription bar',
  ).toBe(true);
  // The progress was live: more than one percentage for a's bar — 0 → 25 → … → 100, or 0 → 100
  // for a one-chunk clip — so a bar stuck at 0 or at 100 fails.
  expect(await page.evaluate(() => window.transcriptionValues.size)).toBeGreaterThan(1);
  // The diarization bar joined the row once it applied, and showed its stage label.
  expect(
    saw(
      ([a]) =>
        a?.bars.includes('Speaker diarization') === true &&
        shows(a, /Embedding \d+%|Clustering \d+%/),
    ),
    'jfk-a.wav with its diarization bar and stage label',
  ).toBe(true);
});
