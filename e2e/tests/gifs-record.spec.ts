import { execFileSync } from 'node:child_process';
import { mkdirSync, renameSync, rmSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Browser, type Page } from '@playwright/test';
import { CANCEL_CLIP, MERGE_CLIP, SHOT_CLIP, SHOT_UPLOAD_CLIP, appUrl } from '../lib/backend.mjs';
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
// The playback flow's frame. The transcript region is 60vh tall, follow mode uses
// scrollIntoView({ block: 'nearest' }) (which scrolls the page too whenever the target
// is not wholly on screen), and "Back to playing position" appears right under the
// region. So the player, the region and that button's spot must all fit the viewport,
// while the region stays far shorter than the transcript. Measured at 1280 px wide:
// the region starts ~222 px below the player's top and the button is 42 px. At 860 px
// the region is 516 px; with the button's spot (BUTTON_SPOT) below the region this
// leaves ~56 px above the player — the follow scrolls of the setup nudge the page up
// by ~20 px, and if the player is clipped the browser's focus-scroll on the next mouse
// click snaps the page to the top.
const PLAYBACK_VIEWPORT = { width: 1280, height: 860 };
const BUTTON_SPOT = 66;
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
// flow drives the page, the context closes, and the video gets its stable name and must
// be a real, non-empty recording. The video has the flow's viewport size. A flow whose
// setup should not be seen calls `startShowing` when the scene is ready: everything
// recorded before it is cut off (re-encoded with the devenv's ffmpeg, as global setup
// prepares the fixtures).
// The recordings are dark mode only (decided with the user), from the very first frame:
// the context prefers dark, the stored theme choice (themeMode.ts, key stt-theme-mode)
// is seeded as dark before the app loads, and the document canvas is dark too —
// otherwise the app's first paint (before React mounts and applies the theme) is a
// white flash in the recordings.
async function recordFlow(
  browser: Browser,
  name: string,
  viewport: { width: number; height: number },
  drive: (page: Page, startShowing: () => void) => Promise<void>,
  cutHeadMs = 0,
): Promise<string> {
  const context = await browser.newContext({
    viewport,
    colorScheme: 'dark',
    recordVideo: { dir: RECORDINGS_DIR, size: viewport },
  });
  await context.addInitScript(() => {
    localStorage.setItem('stt-theme-mode', 'dark');
    // The app's pre-mount frames: paint the root with the dark theme's background token
    // (darkTheme.colors.background) so the boot is dark instead of the white canvas
    // flash; styled-components covers it once the app mounts (user, 2026-10-11).
    document.documentElement.style.backgroundColor = '#14161a';
  });
  const page = await context.newPage();
  const recordingStarted = Date.now();
  let showStarted = recordingStarted;
  const video = page.video();
  expect(video).not.toBeNull();
  await drive(page, () => {
    showStarted = Date.now();
  });
  await expect(page.getByRole('button', { name: 'Theme: dark' })).toBeVisible();
  await context.close();
  const target = join(RECORDINGS_DIR, name);
  const recorded = await video!.path();
  // A flow cuts its recording from the start (`cutHeadMs`, the app's boot and its white
  // first paint, user 2026-10-11) or from when it calls `startShowing` (setup).
  const cutMs = Math.max(cutHeadMs, showStarted - recordingStarted);
  if (cutMs === 0) {
    renameSync(recorded, target);
  } else {
    const cut = (cutMs / 1000).toFixed(2);
    // Output-side seek (after -i): the re-encode would otherwise occasionally start
    // with a couple of white frames at the cut point.
    execFileSync('ffmpeg', [
      '-y',
      '-loglevel',
      'error',
      '-i',
      recorded,
      '-ss',
      cut,
      '-c:v',
      'libvpx',
      '-crf',
      '8',
      '-b:v',
      '4M',
      target,
    ]);
    rmSync(recorded);
  }
  expect(statSync(target).size).toBeGreaterThan(0);
  // No white flash: the first frame's average color must be dark (the app paints its
  // pre-theme background before React mounts; the head cuts trim it, and this guard
  // keeps the recordings honest — user, 2026-10-11).
  const firstPixel = execFileSync(
    'ffmpeg',
    [
      '-loglevel',
      'error',
      '-i',
      target,
      '-frames:v',
      '1',
      '-vf',
      'scale=1:1,format=rgb24',
      '-f',
      'rawvideo',
      '-',
    ],
    { maxBuffer: 1024 },
  );
  for (const channel of firstPixel) {
    expect(channel).toBeLessThan(0x80);
  }
  return target;
}

test('records the three GIF source videos in dark mode', async ({ browser }) => {
  test.setTimeout(900_000);
  mkdirSync(RECORDINGS_DIR, { recursive: true });

  // Flow 1 — upload and queue progress: a long seed runs (its transcription progress is
  // the queue at work), a second clip queues behind it, the seed is canceled, and the
  // mate transcribes to completion.
  await recordFlow(
    browser,
    'gif-upload-queue.webm',
    DESKTOP_VIEWPORT,
    async (page) => {
      await appLive(page);
      await uploadClips(page, [SHOT_CLIP]);
      const running = rowOf(page, SHOT_CLIP);
      await expect(running.getByText('RUNNING', { exact: true })).toBeVisible({ timeout: 120_000 });
      await expect(running.getByText(/Chunking|Transcribing|Diarizing/)).toBeVisible();
      await expect(running.getByRole('progressbar', { name: 'Transcription' })).toBeVisible();
      await expect(running.getByText(/\d+%/)).toBeVisible();

      await uploadClips(page, [SHOT_UPLOAD_CLIP]);
      await expect(
        rowOf(page, SHOT_UPLOAD_CLIP).getByText('QUEUED', { exact: true }),
      ).toBeVisible();

      await running.getByRole('button', { name: `Cancel ${SHOT_CLIP}` }).click();
      await page
        .getByRole('dialog', { name: `Cancel ${SHOT_CLIP}?` })
        .getByRole('button', { name: 'Cancel task' })
        .click();
      await expect(page.getByText(`${SHOT_UPLOAD_CLIP} transcribed`)).toBeVisible({
        timeout: 240_000,
      });
      await page.waitForTimeout(1_000);
    },
    600,
  );

  // Flow 2 — transcript playback with follow mode. Setup, cut from the video: the player
  // and the transcript region are framed, and the playhead is put on a segment deep in
  // the transcript (following scrolls the region so it sits at the bottom edge), paused.
  // Shown: playing moves the highlight past the region's bottom edge and following
  // scrolls the region line by line (not the page — the player stays in view; its own
  // scroll does not stop following). A slow hand scroll up carries the highlight out of
  // view, so following stops and "Back to playing position" appears; clicking it scrolls
  // back to the highlight. The clip is the ~220 s loop: its transcript overflows the
  // region by hundreds of pixels on any machine (the ~132 s playback clip's overflowed by
  // 58 px here and by under 50 on the user's).
  const playbackUrl = await transcriptUrl(browser, CANCEL_CLIP);
  await recordFlow(
    browser,
    'gif-playback-follow.webm',
    PLAYBACK_VIEWPORT,
    async (page, startShowing) => {
      await page.goto(playbackUrl);
      const region = page.getByRole('region', { name: 'Transcript' });
      await expect(region.getByRole('button').first()).toBeVisible();
      const overflow = await region.evaluate(
        (element) => [element.scrollHeight, element.clientHeight] as const,
      );
      expect(overflow[0]).toBeGreaterThan(overflow[1] + 200);
      const back = page.getByRole('button', { name: 'Back to playing position' });
      const regionScrollTop = () => region.evaluate((element) => element.scrollTop);
      const pageScrollY = () => page.evaluate(() => window.scrollY);
      const activeInView = () =>
        region.evaluate((element) => {
          const active = element.querySelector('[data-active]');
          if (active === null) {
            return false;
          }
          const box = element.getBoundingClientRect();
          const segment = active.getBoundingClientRect();
          return segment.top >= box.top - 1 && segment.bottom <= box.bottom + 1;
        });

      // The frame: the region's bottom BUTTON_SPOT above the viewport's bottom puts the
      // whole player above it. The page ends only 24 px under the region, too short to
      // scroll that far, so the body gets bottom padding — it lies below the frame, and
      // without it Playwright scrolled the page to reach the button. The framing is
      // repeated after the pause click: the setup's follow scrolls nudge the page, and
      // the frame must be the steady state the video opens on (the header's
      // shrink-on-scroll transition also settles).
      const reframe = async () => {
        await page.evaluate(() => {
          document.body.style.paddingBottom = '100px';
        });
        await region.evaluate((element) => element.scrollIntoView({ block: 'end' }));
        await page.evaluate((spot) => window.scrollBy(0, spot), BUTTON_SPOT);
        await page.waitForTimeout(500);
      };
      await reframe();
      const seekBar = page.getByRole('slider', { name: 'Seek' });
      expect((await seekBar.boundingBox())!.y).toBeGreaterThanOrEqual(0);
      const framedAt = await pageScrollY();

      // The playhead on the last segment that lies below the region's visible part but
      // still has at least 60 px of transcript after it: following scrolls into that rest
      // (the region cannot scroll past its bottom), and the video runs the transcript to its
      // end, so the last segment is the highlighted one when it closes (user: "never shows
      // the last segment highlighted"). A DOM click seeks there and plays (a Playwright
      // click would scroll it into view first), and pausing at once leaves it there.
      const deep = await region.evaluate((element) => {
        const box = element.getBoundingClientRect();
        const segments = [...element.querySelectorAll<HTMLElement>('[role="button"]')];
        const index = segments.findLastIndex((segment) => {
          const rect = segment.getBoundingClientRect();
          return (
            rect.top - box.top >= element.clientHeight &&
            rect.bottom - box.top <= element.scrollHeight - 60
          );
        });
        segments[index]?.click();
        return index;
      });
      expect(deep).toBeGreaterThan(0);
      await page.getByRole('button', { name: 'Pause' }).click();
      await reframe();
      await expect(page.getByRole('button', { name: 'Play', exact: true })).toBeVisible();
      await expect.poll(activeInView).toBe(true);
      // This clip's segments run ~8 s, so the playhead moves to 2 s before the deep
      // segment's end (its times from the turns API, whose segments are in the
      // transcript's order): the highlight stays put, and soon after Play the next segment,
      // below the visible part, makes following scroll.
      const turns: { turns: { segments: { endMs: number }[] }[] } = await (
        await page.request.get(appUrl(`/api/tasks/${playbackUrl.split('/').pop()}/turns`))
      ).json();
      const deepEndMs = turns.turns.flatMap((turn) => turn.segments)[deep].endMs;
      await page.locator('audio').evaluate(
        (audio: HTMLAudioElement, seconds) => {
          audio.currentTime = seconds;
        },
        deepEndMs / 1000 - 2,
      );
      const startTop = await regionScrollTop();
      expect(startTop).toBeGreaterThan(0);
      await expect(back).toBeHidden();
      expect(await pageScrollY()).toBe(framedAt);

      startShowing();
      await page.waitForTimeout(VIEWER_PAUSE);
      await page.getByRole('button', { name: 'Play', exact: true }).click();
      await expect(page.getByRole('button', { name: 'Pause' })).toBeVisible();
      await expect.poll(regionScrollTop, { timeout: 5_000 }).toBeGreaterThan(startTop);
      await expect.poll(activeInView).toBe(true);
      await page.waitForTimeout(4_000);
      await expect(back).toBeHidden();
      expect(await pageScrollY()).toBe(framedAt);
      expect((await seekBar.boundingBox())!.y).toBeGreaterThanOrEqual(0);
      // Run the playback to its end, so the video closes on the last highlighted
      // segment (user: "never shows the last segment highlighted").
      const lastIndex = await region.evaluate(
        (element) => element.querySelectorAll('[role="button"]').length - 1,
      );
      await expect
        .poll(
          () =>
            region.evaluate((element) =>
              [...element.querySelectorAll('[role="button"]')].findIndex((segment) =>
                segment.hasAttribute('data-active'),
              ),
            ),
          { timeout: 60_000 },
        )
        .toBe(lastIndex);
      await expect(back).toBeHidden();
      expect(await pageScrollY()).toBe(framedAt);
      await page.waitForTimeout(1_500);
      await expect(back).toBeHidden();
    },
  );

  // Flow 3 — speaker rename and merge on item 09's two-speaker fixture, whose pinned
  // labelling gives three turns: Speaker 1 → Speaker 2 → Speaker 1. At a viewer's pace,
  // Speaker 1 is renamed to Alice (typed, saved on Enter, both of its turns follow), then
  // Alice and Speaker 2 are merged, keeping Alice, so the three turns join into one and
  // Speaker 2's field is gone.
  const mergeUrl = await transcriptUrl(browser, MERGE_CLIP);
  await recordFlow(
    browser,
    'gif-speaker-rename-merge.webm',
    DESKTOP_VIEWPORT,
    async (page) => {
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
    },
    1_000,
  );
});
