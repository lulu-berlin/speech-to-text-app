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
// The recordings are watched as GIFs: the flows pause between steps so a viewer can
// follow them (machine speed was too fast and jumpy — user, 2026-10-11).
const VIEWER_PAUSE = 1_500;
const STEP_PAUSE = 700;

// Uploads a clip and waits for its transcript outside any recording, so a flow's video
// starts on the finished transcript instead of the upload and the wait for it; returns
// the transcript's URL.
async function transcriptUrl(browser: Browser, clip: string): Promise<string> {
  const page = await browser.newPage();
  await uploadAndOpen(page, clip);
  const url = page.url();
  await page.close();
  return url;
}

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

  // Flow 2 — transcript playback with follow mode, at a viewer's pace: the player and
  // the transcript fill the frame, playing advances the time and the highlight, a click
  // on the last visible segment seeks there and plays on, and as the highlight passes the
  // region's bottom edge following scrolls it back into view (its own scroll does not
  // stop following). A slow hand scroll stops following until "Back to playing position"
  // resumes it. A short viewport, as in the playback journey: the transcript region
  // (max-height 60vh) must be far shorter than the long clip's transcript, or following
  // has nothing to scroll.
  const playbackUrl = await transcriptUrl(browser, PLAYBACK_CLIP);
  await recordFlow(browser, 'gif-playback-follow.webm', PLAYBACK_VIEWPORT, async (page) => {
    await page.goto(playbackUrl);
    const region = page.getByRole('region', { name: 'Transcript' });
    await expect(region.getByRole('button').first()).toBeVisible();
    expect(
      await region.evaluate((element) => element.scrollHeight > element.clientHeight + 50),
    ).toBe(true);
    const back = page.getByRole('button', { name: 'Back to playing position' });
    const times = page.getByText(/^\d\d:\d\d \/ \d\d:\d\d$/);
    await page
      .getByRole('slider', { name: 'Seek' })
      .evaluate((element) => element.scrollIntoView({ block: 'start' }));
    await page.waitForTimeout(VIEWER_PAUSE);

    await page.getByRole('button', { name: 'Play' }).click();
    await expect(page.getByRole('button', { name: 'Pause' })).toBeVisible();
    const started = await times.textContent();
    await expect.poll(() => times.textContent(), { timeout: 10_000 }).not.toBe(started);
    await page.waitForTimeout(STEP_PAUSE);

    const lastVisible = await region.evaluate((element) => {
      const box = element.getBoundingClientRect();
      return [...element.querySelectorAll('[role="button"]')].findLastIndex((segment) => {
        const rect = segment.getBoundingClientRect();
        return rect.top >= box.top && rect.bottom <= box.bottom;
      });
    });
    expect(lastVisible).toBeGreaterThan(0);
    await region.getByRole('button').nth(lastVisible).click();
    await expect
      .poll(() =>
        region.evaluate((element) =>
          [...element.querySelectorAll('[role="button"]')].findIndex((segment) =>
            segment.hasAttribute('data-active'),
          ),
        ),
      )
      .toBeGreaterThanOrEqual(lastVisible);
    await expect(page.getByRole('button', { name: 'Pause' })).toBeVisible();
    await expect
      .poll(() => region.evaluate((element) => element.scrollTop), { timeout: 30_000 })
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
    await page.waitForTimeout(4_000);
    await expect(back).toBeHidden();

    // A slow hand scroll up, no further than the region has scrolled: past its top the
    // wheel would scroll the page instead and jump the frame to the header.
    const handScroll = Math.min(await region.evaluate((element) => element.scrollTop), 150);
    expect(handScroll).toBeGreaterThan(0);
    await region.hover();
    for (let moved = 0; moved < handScroll; moved += 25) {
      await page.mouse.wheel(0, -Math.min(25, handScroll - moved));
      await page.waitForTimeout(100);
    }
    await expect(back).toBeVisible();
    await page.waitForTimeout(VIEWER_PAUSE);
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
    await page.waitForTimeout(VIEWER_PAUSE);
    await expect(back).toBeHidden();
  });

  // Flow 3 — speaker rename and merge on item 09's two-speaker fixture, whose pinned
  // labelling gives three turns: Speaker 1 → Speaker 2 → Speaker 1. At a viewer's pace,
  // Speaker 1 is renamed to Alice (typed, saved on Enter, both of its turns follow), then
  // Alice and Speaker 2 are merged, keeping Alice, so the three turns join into one and
  // Speaker 2's field is gone.
  const mergeUrl = await transcriptUrl(browser, MERGE_CLIP);
  await recordFlow(browser, 'gif-speaker-rename-merge.webm', DESKTOP_VIEWPORT, async (page) => {
    await page.goto(mergeUrl);
    const nameField = page.getByRole('textbox', { name: 'Name of speaker 1' });
    await expect(nameField).toHaveValue('Speaker 1');
    await page.waitForTimeout(VIEWER_PAUSE);
    await nameField.click();
    await nameField.press('ControlOrMeta+a');
    await nameField.pressSequentially('Alice', { delay: 150 });
    await page.waitForTimeout(STEP_PAUSE);
    await nameField.press('Enter');
    await expect(turnTextOf(page, 'Alice')).toHaveCount(2);
    await expect(turnTextOf(page, 'Speaker 1')).toHaveCount(0);
    await expect(turnTextOf(page, 'Speaker 2')).toHaveCount(1);
    await page.waitForTimeout(VIEWER_PAUSE);

    await page.getByRole('button', { name: 'Merge speakers…' }).click();
    await page.waitForTimeout(STEP_PAUSE);
    await page.getByRole('checkbox', { name: 'Alice' }).check();
    await page.waitForTimeout(STEP_PAUSE);
    await page.getByRole('checkbox', { name: 'Speaker 2' }).check();
    await page.waitForTimeout(STEP_PAUSE);
    await page.getByRole('combobox', { name: 'Keep' }).selectOption({ label: 'Alice' });
    await page.waitForTimeout(STEP_PAUSE);
    await page.getByRole('button', { name: 'Merge', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Merge speakers?' });
    await expect(dialog).toContainText('Merge Speaker 2 into Alice?');
    await page.waitForTimeout(VIEWER_PAUSE);
    await dialog.getByRole('button', { name: 'Merge', exact: true }).click();

    await expect(page.getByRole('textbox', { name: 'Name of speaker 2' })).toHaveCount(0);
    await expect(turnTextOf(page, 'Alice')).toHaveCount(1);
    await expect(turnTextOf(page, 'Speaker 2')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Reset all merges' })).toBeVisible();
    await page.waitForTimeout(2 * VIEWER_PAUSE);
  });
});
