import { join } from 'node:path';
import { expect, type Locator, type Page, type Response } from '@playwright/test';
import { appUrl, loadState } from '../../lib/backend.ts';
import { awaitTaskState } from '../../lib/await-task.ts';
import type { TaskSnapshot } from '../../lib/await-task.ts';

// The two widths the spec fixes: phone ~390 px, desktop ~1280 px.
export const PHONE_VIEWPORT = { width: 390, height: 844 };
export const DESKTOP_VIEWPORT = { width: 1280, height: 800 };

// The mascot (item 17): the stenographer SVG. It is decorative (aria-hidden, no role),
// so it is found by the artwork's fixed viewBox. The class is-typing is present only
// while a task runs.
export const MASCOT = 'svg[viewBox="55 61 293 293"]';

// The shell answered by the built frontend: the brand title as the page title and
// heading, the navigation — and no uncaught error while it renders.
export async function expectShellAt(page: Page) {
  const pageErrors: string[] = [];
  const onError = (error: Error) => pageErrors.push(error.message);
  page.on('pageerror', onError);
  await page.goto(appUrl('/'));
  await expect(page).toHaveTitle('Speech-to-Text');
  await expect(page.getByRole('heading', { name: 'Speech-to-Text' })).toBeVisible();
  await expect(page.getByRole('navigation')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Queue' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'History' })).toBeVisible();
  page.off('pageerror', onError);
  expect(pageErrors).toEqual([]);
}

// The app only counts as loaded against the real backend once its event stream has
// connected and the first snapshot arrived: the header's indicator turns "Live" and the
// queue's empty state offers the drop zone.
export async function expectLiveQueue(page: Page) {
  await expect(page.getByText('Live', { exact: true })).toBeVisible();
  await expect(
    page.getByText('No tasks yet — drop audio files here, or click to choose them'),
  ).toBeVisible();
}

// The mascot directly above a region's label. Two layouts: the loading regions and the
// History empty state put the label in a paragraph right after the mascot (`svg + p`),
// the queue drop zone's zone span contains the label text with the mascot as its direct
// child. Playwright's xpath chaining supports only `..`, so these CSS forms carry the
// structure. One locator with a comma union: whichever shape matches this label wins.
export function mascotAbove(page: Page, label: string): Locator {
  return page.locator(`svg + p:has-text("${label}"), span:has-text("${label}") > svg`);
}

// Loads the app and waits for the event stream: the header indicator reads "Live".
export async function appLive(page: Page) {
  await page.goto(appUrl('/'));
  await expect(page.getByText('Live', { exact: true })).toBeVisible();
}

// Uploads the named fixture clips through the queue's file input (the drop zone), and waits for the
// server's answer to each one: a spec must not go on to wait for a task the server refused, or for an
// upload the browser aborted — that is what spent a whole stall window in CI run #465, where the POST
// answered -1 after the spec had stopped the backend under it. The answer must be a created task
// unless the spec uploads, on purpose, something the server should refuse.
export async function uploadClips(
  page: Page,
  names: string[],
  { expectAcceptance = true }: { expectAcceptance?: boolean } = {},
): Promise<void> {
  const { fixturesDir } = loadState();
  // One answer per file. Equal `waitForResponse` predicates would not do: every pending waiter
  // resolves on the first matching answer, so the later files would go unchecked. The collector
  // counts them, and the helper waits until each file has one before judging them.
  const answers: Response[] = [];
  const collect = (response: Response) => {
    if (response.request().method() === 'POST' && response.url().endsWith('/api/tasks')) {
      answers.push(response);
    }
  };
  page.on('response', collect);
  try {
    await page.setInputFiles(
      'input[type="file"]',
      names.map((name) => join(fixturesDir, name)),
    );
    await expect
      .poll(() => answers.length, { timeout: 120_000 })
      .toBeGreaterThanOrEqual(names.length);
  } finally {
    page.off('response', collect);
  }
  if (!expectAcceptance) {
    return;
  }
  for (const answer of answers) {
    if (answer.status() !== 201) {
      throw new Error(`an upload was refused: ${answer.status()} ${await answer.text()}`);
    }
  }
}

// The quote of the JFK sample, as two phrases (the model's punctuation is not pinned).
export const JFK_QUOTE = [
  /ask not what your country can do for you/i,
  /ask what you can do for your country/i,
];

// Opens a completed task's transcript from History by its filename; the transcript page
// shows the filename as its heading.
export async function openTranscript(page: Page, filename: string) {
  await appLive(page);
  await page.getByRole('link', { name: 'History' }).click();
  await page.getByRole('link', { name: filename }).click();
  await expect(page.getByRole('heading', { name: filename })).toBeVisible();
}

// A completed transcript to read against the shared backend: a full journey usually has the clip
// completed already (the earlier specs uploaded it), while a run of one file alone starts from the
// fresh backend with the fixtures — so the clip is uploaded only when no task of that name exists.
// The task list is the authority, read over the API: the History page's render lags it, and a task
// the reset canceled is not a transcript (roadmap item 84 — the old 5 s page probe could miss a
// completed row on a loaded runner, and the re-upload it then made is refused as a duplicate).
export async function ensureTranscript(page: Page, clip: string) {
  await appLive(page);
  const statuses = async () => {
    const body = (await (await page.request.get(appUrl('/api/tasks'))).json()) as {
      tasks: { filename: string; status: string }[];
    };
    return body.tasks.filter((task) => task.filename === clip).map((task) => task.status);
  };

  const known = await statuses();
  if (known.length > 0) {
    if (!known.includes('COMPLETED')) {
      throw new Error(`the task ${clip} is ${known.join(', ')}, not a completed transcript`);
    }
    return;
  }

  await goTo(page, 'Queue');
  // A refusal is tolerated: another spec may hold these bytes, and the wait below then names the
  // state that task is in.
  await uploadClips(page, [clip], { expectAcceptance: false });
  await waitForTaskState(page, clip);
  await expect(page.getByText(`${clip} transcribed`)).toBeVisible();
}

// Waits for [clip]'s task to reach [until] (the default is its completion), by the task's own state
// (roadmap item 84, load): a loaded runner can take minutes, so no 240 s ceiling ends this wait —
// only the task reaching the state, its failure, or a queue that stops progressing for the stall
// window does.
export async function waitForTaskState(
  page: Page,
  clip: string,
  until?: (task: TaskSnapshot) => boolean,
  options: { after?: number | null; stallMs?: number; intervalMs?: number } = {},
): Promise<void> {
  await awaitTaskState(
    async () => {
      const response = await page.request.get(appUrl('/api/tasks'));
      const body = (await response.json()) as { tasks: TaskSnapshot[] };
      return body.tasks;
    },
    { clip, until, ...options },
  );
}

// Uploads one clip, waits until its finish notice says it is transcribed, and opens its
// transcript from that notice.
export async function uploadAndOpen(page: Page, clip: string) {
  await appLive(page);
  await uploadClips(page, [clip]);
  await waitForTaskState(page, clip);
  const notice = page.getByText(`${clip} transcribed`);
  await expect(notice).toBeVisible();
  await notice.getByRole('link', { name: 'open' }).click();
  await expect(page.getByRole('heading', { name: clip })).toBeVisible();
}

// The text of each turn labelled with `speaker` in the transcript: the speaker's name sits
// in a span right above the turn's text paragraph.
export function turnTextOf(page: Page, speaker: string): Locator {
  return page.locator(`span:text-is("${speaker}") + p`);
}

// The positions of the first pending-queue row containing each name, in DOM order. The list is
// scoped: the uploads list is a list of list items too, and an upload row survives until its task
// reaches the live state, so an unscoped scan could read upload order instead of queue order. A
// missing name is -1, so a poller can distinguish "not yet rendered".
export async function rowOrder(page: Page, names: string[]): Promise<number[]> {
  const items = await page
    .getByRole('list', { name: 'Pending tasks' })
    .getByRole('listitem')
    .allTextContents();
  return names.map((name) => items.findIndex((text) => text.includes(name)));
}

/**
 * Moves [clip]'s row one place down through its actions menu and waits for the server's answer. The
 * queue view applies the move optimistically, so a reload or a backend stop right after the click
 * would abort the PUT and leave the old order behind (roadmap item 84).
 */
export async function moveRowDown(page: Page, clip: string): Promise<void> {
  const saved = page.waitForResponse(
    (response) =>
      response.request().method() === 'PUT' && response.url().endsWith('/api/queue/order'),
    { timeout: 30_000 },
  );
  await page.getByRole('button', { name: `Actions for ${clip}` }).click();
  await page.getByRole('menuitem', { name: 'Move down' }).click();
  const answer = await saved;
  if (answer.status() !== 200) {
    throw new Error(`the reorder was refused: ${answer.status()} ${await answer.text()}`);
  }
}

// A History card (or queue row) by its filename.
export function rowOf(page: Page, filename: string): Locator {
  return page.getByRole('listitem').filter({ hasText: filename });
}

// The terminal badge a task legitimately has when a command raced its finish: COMPLETED (the run won) or
// CANCELED (the command won). FAILED, and a task that never left the queue, fail the assertion.
export async function expectCompletedOrCanceled(
  page: Page,
  filename: string,
  timeout = 120_000,
): Promise<void> {
  const card = rowOf(page, filename);
  await expect(
    card.getByText('COMPLETED', { exact: true }).or(card.getByText('CANCELED', { exact: true })),
  ).toBeVisible({ timeout });
}

// Runs a row action a task finishing can legally interrupt: [step] is attempted, and a failure is accepted
// only if [row] has left the queue by then (its absence is asserted, so anything else stays loud). Returns
// whether the step ran, so a caller can end a loop on a legal completion.
export async function unlessGone(row: Locator, step: () => Promise<unknown>): Promise<boolean> {
  try {
    await step();
    return true;
  } catch {
    await expect(row).toHaveCount(0);
    return false;
  }
}

// The task id behind a History card, from its link to the transcript.
export async function taskIdOf(page: Page, filename: string): Promise<string> {
  const href = await rowOf(page, filename)
    .getByRole('link', { name: filename })
    .getAttribute('href');
  return href?.split('/').pop() ?? '';
}

// The task id of `filename` on the shared backend, from the task list API: an upload that
// is still in the queue has no History card yet, so taskIdOf cannot read it from the DOM.
export async function apiTaskIdOf(page: Page, filename: string): Promise<number> {
  const list = await (await page.request.get(appUrl('/api/tasks'))).json();
  const task = list.tasks.find(
    (candidate: { filename: string }) => candidate.filename === filename,
  );
  expect(task, `task ${filename} to exist`).toBeTruthy();
  return task.id;
}

// Switches view through the header navigation (finish notices carry their own links).
export async function goTo(page: Page, view: 'Queue' | 'History' | 'Settings') {
  await page.getByRole('navigation').getByRole('link', { name: view }).click();
}
