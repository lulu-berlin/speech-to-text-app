import { join } from 'node:path';
import { expect, type Locator, type Page } from '@playwright/test';
import { appUrl, loadState } from '../../lib/backend.ts';
import { awaitTranscription } from '../../lib/await-task.ts';
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

// Uploads the named fixture clips through the queue's file input (the drop zone).
export async function uploadClips(page: Page, names: string[]) {
  const { fixturesDir } = loadState();
  await page.setInputFiles(
    'input[type="file"]',
    names.map((name) => join(fixturesDir, name)),
  );
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

// A completed transcript to read against the shared backend: a full journey usually has
// the clip completed already (the earlier specs uploaded it), while a run of one file
// alone starts from the fresh backend with the fixtures — so the clip is uploaded only
// when History does not have it yet (re-uploading the same content answers 409).
export async function ensureTranscript(page: Page, clip: string) {
  await appLive(page);
  await goTo(page, 'History');
  // The list loads after the navigation, so wait briefly before deciding the clip is absent
  // (an immediate isVisible() check raced the load in the full journey and triggered a
  // duplicate upload).
  const completed = rowOf(page, clip).getByText('COMPLETED', { exact: true });
  const known = await completed
    .waitFor({ state: 'visible', timeout: 5_000 })
    .then(() => true)
    .catch(() => false);
  if (known) {
    return;
  }
  await goTo(page, 'Queue');
  await uploadClips(page, [clip]);
  await waitForTranscription(page, clip);
  const transcribed = page.getByText(`${clip} transcribed`);
  const duplicate = page.getByText(`${clip} Already uploaded`);
  await expect(transcribed.or(duplicate)).toBeVisible();
  // A duplicate upload proves the task exists; it must be the completed transcript the
  // caller expects.
  if (await duplicate.isVisible()) {
    await goTo(page, 'History');
    await expect(rowOf(page, clip).getByText('COMPLETED', { exact: true })).toBeVisible({
      timeout: 30_000,
    });
  }
}

// Waits for [clip]'s task to finish, by the task's own state (roadmap item 84, load): a loaded
// runner can take minutes, so no 240 s ceiling ends this wait — only the task's completion, its
// failure, or a queue that stops progressing for the stall window does.
export async function waitForTranscription(page: Page, clip: string): Promise<void> {
  await awaitTranscription(
    async () => {
      const response = await page.request.get(appUrl('/api/tasks'));
      const body = (await response.json()) as { tasks: TaskSnapshot[] };
      return body.tasks;
    },
    { clip },
  );
}

// Uploads one clip, waits until its finish notice says it is transcribed, and opens its
// transcript from that notice.
export async function uploadAndOpen(page: Page, clip: string) {
  await appLive(page);
  await uploadClips(page, [clip]);
  await waitForTranscription(page, clip);
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

// The positions of the first list item containing each name, in DOM order — the pending
// queue's order (and the upload list's order while uploads are in flight). A missing
// name is -1, so a poller can distinguish "not yet rendered".
export async function rowOrder(page: Page, names: string[]): Promise<number[]> {
  const items = await page.getByRole('listitem').allTextContents();
  return names.map((name) => items.findIndex((text) => text.includes(name)));
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
