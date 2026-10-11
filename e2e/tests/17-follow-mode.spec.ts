import { expect, test, type Locator, type Page } from '@playwright/test';
import { FOLLOW_CLIP } from '../lib/backend.mjs';
import { uploadAndOpen } from './helpers/ui';

// The index of the highlighted segment among the transcript's segment buttons (-1: none).
async function activeIndex(region: Locator): Promise<number> {
  return region.evaluate((element) =>
    [...element.querySelectorAll('[role="button"]')].findIndex((segment) =>
      segment.hasAttribute('data-active'),
    ),
  );
}

// Whether the highlighted segment lies inside the transcript region's visible box.
async function activeInView(region: Locator): Promise<boolean> {
  return region.evaluate((element) => {
    const active = element.querySelector('[data-active]');
    if (active === null) {
      return false;
    }
    const box = element.getBoundingClientRect();
    const segment = active.getBoundingClientRect();
    return segment.top >= box.top - 1 && segment.bottom <= box.bottom + 1;
  });
}

// The first segment after the highlighted one that starts below the region's visible box
// — the next content the playhead runs into that following has to scroll to (-1: none).
async function nextOutOfViewSegment(region: Locator): Promise<number> {
  return region.evaluate((element) => {
    const segments = [...element.querySelectorAll('[role="button"]')];
    const activeIndex = segments.findIndex((segment) => segment.hasAttribute('data-active'));
    const box = element.getBoundingClientRect();
    return segments.findIndex((segment, index) => {
      if (index <= activeIndex) {
        return false;
      }
      return segment.getBoundingClientRect().top >= box.bottom - 1;
    });
  });
}

// The audio element's currentTime in seconds. The playhead's progress signal: when it
// stops advancing the rounds must stop — the joined flac of this clip stalls before its
// transcript ends, so a segment the audio never reaches is not a failure of following.
async function audioSecond(page: Page): Promise<number> {
  return page.locator('audio').evaluate((audio: HTMLAudioElement) => audio.currentTime);
}

// Waits for the playhead to enter `target` with the user's hands off, returning false if
// the audio stops advancing first (played out, or this clip's joined flac stalling
// before its transcript ends). A "Back to playing position" button at any moment is an
// automatic stop and fails the wait immediately.
async function playheadReaches(
  page: Page,
  region: Locator,
  back: Locator,
  target: number,
): Promise<boolean> {
  let lastSecond = await audioSecond(page);
  let lastAdvanceMs = Date.now();
  for (;;) {
    await expect(back).toBeHidden();
    if ((await activeIndex(region)) >= target) {
      return true;
    }
    const at = await audioSecond(page);
    if (at > lastSecond) {
      lastSecond = at;
      lastAdvanceMs = Date.now();
    } else if (Date.now() - lastAdvanceMs > 8_000) {
      return false;
    }
    await page.waitForTimeout(500);
  }
}

// Item 36, ADR 0046: real playback follows the moving playhead through many follow
// scrolls and "Back to playing position" never appears without a hand scroll — the race
// this item fixes made it appear on its own while recording item 35's playback GIF. A
// hand scroll still stops following, and resuming holds through its own follow scroll.
test('follow mode: long playback never shows "Back to playing position" without a hand scroll; a wheel still shows it', async ({
  page,
}) => {
  test.setTimeout(300_000);
  await uploadAndOpen(page, FOLLOW_CLIP);

  // A short viewport makes the long transcript scroll for real as the playhead moves.
  await page.setViewportSize({ width: 1280, height: 500 });
  const region = page.getByRole('region', { name: 'Transcript' });
  const back = page.getByRole('button', { name: 'Back to playing position' });
  await expect(region.getByRole('button').first()).toBeVisible();
  expect(await region.evaluate((element) => element.scrollHeight > element.clientHeight + 50)).toBe(
    true,
  );

  // Play once; from here the user's hands stay off — every following scroll below is
  // driven by playback alone. Each round: take the first segment after the highlighted
  // one that is out of view, wait for playback to reach it, and check that following
  // scrolled it into view. A limited number of rounds bounds the run.
  await page.getByRole('button', { name: 'Play' }).click();
  let scrolled = 0;
  for (let round = 0; round < 4; round += 1) {
    const target = await nextOutOfViewSegment(region);
    if (target === -1) {
      break; // nothing left below the fold: the transcript is fully followed.
    }
    const startTop = await region.evaluate((element) => element.scrollTop);
    // Wait for playback to reach the out-of-view segment on its own; if the audio stops
    // advancing first, there is nothing more to follow into and the rounds end.
    if (!(await playheadReaches(page, region, back, target))) {
      break;
    }
    // The follow scroll brought the formerly out-of-view segment in, moving the region.
    await expect.poll(() => activeInView(region), { timeout: 5_000 }).toBe(true);
    scrolled += 1;
    expect(await region.evaluate((element) => element.scrollTop)).toBeGreaterThan(startTop);
    await expect(back).toBeHidden();
  }
  expect(
    scrolled,
    'hands-off playback must scroll through at least 3 out-of-view segments',
  ).toBeGreaterThanOrEqual(3);

  // A hand scroll (wheel) still stops following: the button appears.
  await region.hover();
  await page.mouse.wheel(0, 400);
  await expect(back).toBeVisible();

  // Resuming follows again, and its own follow scroll does not stop it once more.
  await back.click();
  await expect.poll(() => activeInView(region)).toBe(true);
  await page.waitForTimeout(500);
  await expect(back).toBeHidden();
});
