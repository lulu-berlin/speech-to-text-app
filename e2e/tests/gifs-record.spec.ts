import { mkdirSync, renameSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Browser, type Page } from '@playwright/test';
import { MERGE_CLIP, PLAYBACK_CLIP, SHOT_CLIP, SHOT_UPLOAD_CLIP } from '../lib/backend.mjs';
import {
  DESKTOP_VIEWPORT,
  appLive,
  rowOf,
  turnTextOf,
  uploadAndOpen,
  uploadClips,
} from './helpers/ui';

// Source videos for the README's GIFs (roadmap item 35, test list item 6): the three
// agreed flows — uploading files and watching the queue progress, transcript playback
// with follow mode, and renaming and merging speakers — each recorded as one desktop,
// dark mode webm under e2e/screenshots/recordings/. Run standalone (`pnpm record:gifs`)
// against the real backend from global-setup.mjs; the rest of the suite never runs this
// spec (playwright.config.ts ignores it). The webm files are gitignored like the
// screenshots; the user converts them to the small GIFs the README shows.
const RECORDINGS_DIR = join('screenshots', 'recordings');
// The playback journey's short viewport (06-playback.spec.ts).
const PLAYBACK_VIEWPORT = { width: 1280, height: 500 };

// One recording context per flow: the video finalizes when the context closes, so the
// flow drives the page, the context closes, and the video is renamed to its stable name
// and must be a real, non-empty recording. The video has the flow's viewport size.
// The recordings are dark mode only (decided with the user), from the first frame: the
// context prefers dark and the user's stored theme choice (themeMode.ts, key
// stt-theme-mode) is seeded as dark before the app loads — clicking the theme button
// instead would pass through light (its cycle is auto -> light -> dark).
async function recordFlow(
  browser: Browser,
  name: string,
  viewport: { width: number; height: number },
  drive: (page: Page) => Promise<void>,
): Promise<string> {
  const context = await browser.newContext({
    viewport,
    colorScheme: 'dark',
    recordVideo: { dir: RECORDINGS_DIR, size: viewport },
  });
  await context.addInitScript(() => localStorage.setItem('stt-theme-mode', 'dark'));
  const page = await context.newPage();
  const video = page.video();
  expect(video).not.toBeNull();
  await drive(page);
  await expect(page.getByRole('button', { name: 'Theme: dark' })).toBeVisible();
  await context.close();
  const target = join(RECORDINGS_DIR, name);
  renameSync(await video!.path(), target);
  expect(statSync(target).size).toBeGreaterThan(0);
  return target;
}

test('records the three GIF source videos in dark mode', async ({ browser }) => {
  test.setTimeout(900_000);
  mkdirSync(RECORDINGS_DIR, { recursive: true });

  // Flow 1 — upload and queue progress: a long seed runs (its transcription progress is
  // the queue at work), a second clip queues behind it, the seed is canceled, and the
  // mate transcribes to completion.
  await recordFlow(browser, 'gif-upload-queue.webm', DESKTOP_VIEWPORT, async (page) => {
    await appLive(page);
    await uploadClips(page, [SHOT_CLIP]);
    const running = rowOf(page, SHOT_CLIP);
    await expect(running.getByText('RUNNING', { exact: true })).toBeVisible({ timeout: 120_000 });
    await expect(running.getByText(/Chunking|Transcribing|Diarizing/)).toBeVisible();
    await expect(running.getByRole('progressbar', { name: 'Transcription' })).toBeVisible();
    await expect(running.getByText(/\d+%/)).toBeVisible();

    await uploadClips(page, [SHOT_UPLOAD_CLIP]);
    await expect(rowOf(page, SHOT_UPLOAD_CLIP).getByText('QUEUED', { exact: true })).toBeVisible();

    await running.getByRole('button', { name: `Cancel ${SHOT_CLIP}` }).click();
    await page
      .getByRole('dialog', { name: `Cancel ${SHOT_CLIP}?` })
      .getByRole('button', { name: 'Cancel task' })
      .click();
    await expect(page.getByText(`${SHOT_UPLOAD_CLIP} transcribed`)).toBeVisible({
      timeout: 240_000,
    });
    await page.waitForTimeout(1_000);
  });

  // Flow 2 — transcript playback with follow mode: playing advances the time and the
  // highlight; seeking makes the highlight follow (scrolling into view, and following
  // must hold through its own scroll), and a hand scroll stops following until "Back to
  // playing position" resumes it. A short viewport, as in the playback journey: the
  // transcript region (max-height 60vh) must be far shorter than the long clip's
  // transcript, or following has nothing to scroll.
  await recordFlow(browser, 'gif-playback-follow.webm', PLAYBACK_VIEWPORT, async (page) => {
    await uploadAndOpen(page, PLAYBACK_CLIP);
    const region = page.getByRole('region', { name: 'Transcript' });
    await expect(region.getByRole('button').first()).toBeVisible();
    expect(
      await region.evaluate((element) => element.scrollHeight > element.clientHeight + 50),
    ).toBe(true);
    const back = page.getByRole('button', { name: 'Back to playing position' });
    const seekBar = page.getByRole('slider', { name: 'Seek' });
    const times = page.getByText(/^\d\d:\d\d \/ \d\d:\d\d$/);

    await page.getByRole('button', { name: 'Play' }).click();
    await expect(page.getByRole('button', { name: 'Pause' })).toBeVisible();
    const started = await times.textContent();
    await expect.poll(() => times.textContent(), { timeout: 10_000 }).not.toBe(started);

    await seekBar.focus();
    // As many presses as the playback journey uses: the seek lands far below the
    // region's visible part, so following has to scroll.
    for (let press = 0; press < 12; press += 1) {
      await page.keyboard.press('PageUp');
    }
    // The highlight followed the seek: a later segment is the active one, its scroll
    // (scrollTop > 0) kept it in view, and the follow's own scroll did not stop it.
    await expect
      .poll(() =>
        region.evaluate((element) =>
          [...element.querySelectorAll('[role="button"]')].findIndex((segment) =>
            segment.hasAttribute('data-active'),
          ),
        ),
      )
      .toBeGreaterThan(0);
    await expect
      .poll(() =>
        region.evaluate((element) => {
          const active = element.querySelector('[data-active]');
          if (active === null) {
            return false;
          }
          const box = element.getBoundingClientRect();
          const segment = active.getBoundingClientRect();
          return segment.top >= box.top - 1 && segment.bottom <= box.bottom + 1;
        }),
      )
      .toBe(true);
    const farDown = await region.evaluate((element) => element.scrollTop);
    expect(farDown).toBeGreaterThan(0);
    await page.waitForTimeout(500);
    await expect(back).toBeHidden();

    await page.keyboard.press('Home');
    await expect
      .poll(() =>
        region.evaluate((element) =>
          [...element.querySelectorAll('[role="button"]')].findIndex((segment) =>
            segment.hasAttribute('data-active'),
          ),
        ),
      )
      .toBe(0);
    await expect
      .poll(() =>
        region.evaluate((element) => {
          const active = element.querySelector('[data-active]');
          if (active === null) {
            return false;
          }
          const box = element.getBoundingClientRect();
          const segment = active.getBoundingClientRect();
          return segment.top >= box.top - 1 && segment.bottom <= box.bottom + 1;
        }),
      )
      .toBe(true);
    expect(await region.evaluate((element) => element.scrollTop)).toBeLessThan(farDown);
    await page.waitForTimeout(1_500);
    await expect(back).toBeHidden();

    const atTop = await region.evaluate((element) => element.scrollTop === 0);
    await region.hover();
    await page.mouse.wheel(0, atTop ? 400 : -400);
    await expect(back).toBeVisible();
    await back.click();
    await expect
      .poll(() =>
        region.evaluate((element) => {
          const active = element.querySelector('[data-active]');
          if (active === null) {
            return false;
          }
          const box = element.getBoundingClientRect();
          const segment = active.getBoundingClientRect();
          return segment.top >= box.top - 1 && segment.bottom <= box.bottom + 1;
        }),
      )
      .toBe(true);
    await page.waitForTimeout(500);
    await expect(back).toBeHidden();
  });

  // Flow 3 — speaker rename and merge on item 09's two-speaker fixture, whose pinned
  // labelling gives three turns: Speaker 1 → Speaker 2 → Speaker 1. Speaker 1 is renamed
  // to Alice (saved on Enter, both of its turns follow), then Alice and Speaker 2 are
  // merged, keeping Alice, so the three turns join into one and Speaker 2's field is gone.
  await recordFlow(browser, 'gif-speaker-rename-merge.webm', DESKTOP_VIEWPORT, async (page) => {
    await uploadAndOpen(page, MERGE_CLIP);
    const nameField = page.getByRole('textbox', { name: 'Name of speaker 1' });
    await expect(nameField).toHaveValue('Speaker 1');
    await nameField.fill('Alice');
    await nameField.press('Enter');
    await expect(turnTextOf(page, 'Alice')).toHaveCount(2);
    await expect(turnTextOf(page, 'Speaker 1')).toHaveCount(0);
    await expect(turnTextOf(page, 'Speaker 2')).toHaveCount(1);

    await page.getByRole('button', { name: 'Merge speakers…' }).click();
    await page.getByRole('checkbox', { name: 'Alice' }).check();
    await page.getByRole('checkbox', { name: 'Speaker 2' }).check();
    await page.getByRole('combobox', { name: 'Keep' }).selectOption({ label: 'Alice' });
    await page.getByRole('button', { name: 'Merge', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Merge speakers?' });
    await expect(dialog).toContainText('Merge Speaker 2 into Alice?');
    await dialog.getByRole('button', { name: 'Merge', exact: true }).click();

    await expect(page.getByRole('textbox', { name: 'Name of speaker 2' })).toHaveCount(0);
    await expect(turnTextOf(page, 'Alice')).toHaveCount(1);
    await expect(turnTextOf(page, 'Speaker 2')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Reset all merges' })).toBeVisible();
    await page.waitForTimeout(1_000);
  });
});
