import { expect, test, type Page } from '@playwright/test';
import { COLOR_ROW_CLIP, appUrl } from '../lib/backend.ts';
import { appLive, expectCompletedOrCanceled, goTo, uploadClips } from './helpers/ui';

// Item 65: the status line's phase prefixes; the collector records each distinct
// (prefix, computed color) pair the row shows.
const PHASE_PREFIXES = ['Chunking…', 'Transcribing…', 'Diarizing…'];
type PhaseColor = { phase: string; color: string };
type PhaseObservation = { observed: PhaseColor[]; observer: MutationObserver };
type PhaseObservationWindow = Window & { phaseObservation?: PhaseObservation };

// Installs a page-side collector for [filename]'s queue row: a MutationObserver samples the
// row on every DOM change, so a phase that renders for a single frame is recorded too — a
// poll from the test would race it. The row is looked up on every sample, so the collector
// can be started before the upload creates it.
async function startPhaseObservation(page: Page, filename: string) {
  await page.evaluate(
    ({ filename, prefixes }) => {
      const observed: PhaseColor[] = [];
      const seen = new Set<string>();
      const sample = () => {
        // The queue shows an upload row and a pending-task row with the same filename while
        // the upload settles; only the pending task's row carries the phase line.
        const pendingTasks = document.querySelector('ul[aria-label="Pending tasks"]');
        const row = [...(pendingTasks ?? document).querySelectorAll('li')].find((item) =>
          item.textContent?.includes(filename),
        );
        if (row === undefined) {
          return;
        }
        for (const span of row.querySelectorAll('span')) {
          const text = span.textContent ?? '';
          const phase = prefixes.find((prefix) => text.startsWith(prefix));
          if (phase === undefined) {
            continue;
          }
          const color = getComputedStyle(span).color;
          const pair = `${phase}\u0000${color}`;
          if (seen.has(pair)) {
            continue;
          }
          seen.add(pair);
          observed.push({ phase, color });
        }
      };
      sample();
      const observer = new MutationObserver(sample);
      observer.observe(document.body, { childList: true, characterData: true, subtree: true });
      (window as PhaseObservationWindow).phaseObservation = {
        observed,
        observer,
      };
    },
    { filename, prefixes: PHASE_PREFIXES },
  );
}

// Stops the page-side collector and returns the distinct pairs it recorded, in the order
// the row first showed them.
async function stopPhaseObservation(page: Page): Promise<PhaseColor[]> {
  return page.evaluate(() => {
    const state = (window as PhaseObservationWindow).phaseObservation;
    if (state === undefined) {
      throw new Error('no phase observation is running in this page');
    }
    state.observer.disconnect();
    delete (window as PhaseObservationWindow).phaseObservation;
    return state.observed;
  });
}

// The one color a phase showed. A phase with no sample or with two colors is an ambiguous
// reading, so it fails with the whole observation — every pair the row showed — in the
// message.
function phaseColor(observed: PhaseColor[], phase: string): string {
  const colors = observed.filter((entry) => entry.phase === phase).map((entry) => entry.color);
  if (colors.length !== 1) {
    throw new Error(`${phase} showed ${colors.length} colors: ${JSON.stringify(observed)}`);
  }
  return colors[0]!;
}

// Item 59, Proposal 3: the running status line's phase is shown by color — chunking,
// transcribing and diarizing each read differently, so the row says what it is doing
// before the percentage exists.
test('the running status line reads differently in each phase', async ({ page }) => {
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

  // Item 65: observe from before the upload, so no phase can fall between two round trips.
  await startPhaseObservation(page, COLOR_ROW_CLIP);
  await uploadClips(page, [COLOR_ROW_CLIP]);

  // The task's own finish signal: its row leaves the Pending tasks list once the task ends
  // (the list filter already excludes the Uploads row with the same filename). Waiting for
  // the running line's ellipsis first keeps the count check from passing before the row
  // exists.
  const row = page
    .getByRole('list', { name: 'Pending tasks' })
    .getByRole('listitem')
    .filter({ hasText: COLOR_ROW_CLIP });
  await expect(row).toContainText('…', { timeout: 120_000 });
  await expect(row).toHaveCount(0, { timeout: 240_000 });
  const observed = await stopPhaseObservation(page);

  await goTo(page, 'History');
  await expectCompletedOrCanceled(page, COLOR_ROW_CLIP);
  const chunkingColor = phaseColor(observed, 'Chunking…');
  const transcribingColor = phaseColor(observed, 'Transcribing…');
  const diarizingColor = phaseColor(observed, 'Diarizing…');
  expect(transcribingColor, JSON.stringify(observed)).not.toBe(chunkingColor);
  expect(diarizingColor, JSON.stringify(observed)).not.toBe(chunkingColor);
  expect(diarizingColor, JSON.stringify(observed)).not.toBe(transcribingColor);
});

// Item 65: the observation cannot depend on a poll catching a phase — chunking lasts well
// under a second. A phase that exists for one frame is still recorded.
test('the phase observation records a phase that renders for a single frame', async ({ page }) => {
  await page.setContent(`
    <ul>
      <li>
        <span>single-frame.wav</span>
        <span id="phase"></span>
      </li>
    </ul>
  `);
  await startPhaseObservation(page, 'single-frame.wav');

  await page.evaluate(async () => {
    const line = document.getElementById('phase')!;
    line.textContent = 'Chunking… · 0:01';
    line.style.color = 'rgb(10, 20, 30)';
    await new Promise(requestAnimationFrame);
    line.textContent = 'Transcribing… 42% · 0:02';
    line.style.color = 'rgb(40, 50, 60)';
    await new Promise(requestAnimationFrame);
  });

  expect(await stopPhaseObservation(page)).toEqual([
    { phase: 'Chunking…', color: 'rgb(10, 20, 30)' },
    { phase: 'Transcribing…', color: 'rgb(40, 50, 60)' },
  ]);
});

// Item 65: the collector reads the uploaded task's row only — a sibling row's phase line
// must not leak into the observation.
test('the phase observation only records the uploaded row', async ({ page }) => {
  await page.setContent(`
    <ul>
      <li>
        <span>target.wav</span>
        <span id="target-phase"></span>
      </li>
      <li>
        <span>sibling.wav</span>
        <span id="sibling-phase"></span>
      </li>
    </ul>
  `);
  await startPhaseObservation(page, 'target.wav');

  await page.evaluate(async () => {
    const target = document.getElementById('target-phase')!;
    target.textContent = 'Transcribing… 42%';
    target.style.color = 'rgb(40, 50, 60)';
    const sibling = document.getElementById('sibling-phase')!;
    sibling.textContent = 'Transcribing… 7%';
    sibling.style.color = 'rgb(200, 10, 10)';
    await new Promise(requestAnimationFrame);
  });

  expect(await stopPhaseObservation(page)).toEqual([
    { phase: 'Transcribing…', color: 'rgb(40, 50, 60)' },
  ]);
});

// Item 65 (discovered): one phase must read as exactly one color. A phase that rendered in
// two colors is an ambiguous reading, not a first match — the failure names every pair.
test('a phase that renders in two colors is rejected', async ({ page }) => {
  await page.setContent(`
    <ul>
      <li>
        <span>two-color.wav</span>
        <span id="phase"></span>
      </li>
    </ul>
  `);
  await startPhaseObservation(page, 'two-color.wav');

  await page.evaluate(async () => {
    const line = document.getElementById('phase')!;
    line.textContent = 'Chunking… · 0:01';
    line.style.color = 'rgb(10, 20, 30)';
    await new Promise(requestAnimationFrame);
    line.textContent = 'Chunking… · 0:02';
    line.style.color = 'rgb(40, 50, 60)';
    await new Promise(requestAnimationFrame);
  });

  const observed = await stopPhaseObservation(page);
  expect(observed).toEqual([
    { phase: 'Chunking…', color: 'rgb(10, 20, 30)' },
    { phase: 'Chunking…', color: 'rgb(40, 50, 60)' },
  ]);
  expect(() => phaseColor(observed, 'Chunking…')).toThrow(
    /Chunking… showed 2 colors: .*rgb\(10, 20, 30\).*rgb\(40, 50, 60\)/,
  );
});
