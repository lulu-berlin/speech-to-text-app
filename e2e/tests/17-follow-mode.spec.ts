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

// Whether playback has run to the end of the audio (ended, or currentTime at the
// duration). The transcribed turns can extend past the joined audio's tail, so a segment
// the audio never reaches is not a failure of following — the rounds must stop then.
async function playbackFinished(page: Page): Promise<boolean> {
  return page
    .locator('audio')
    .evaluate(
      (audio: HTMLAudioElement) =>
        audio.ended || (audio.duration > 0 && audio.currentTime >= audio.duration - 0.05),
    );
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
    // The playhead must reach the out-of-view segment on its own; a "Back to playing
    // position" button at any moment is an automatic stop and fails the round here. If
    // playback finishes first — the joined audio can end before its last transcribed
    // segment starts — the rounds stop: there is nothing left to follow into.
    const deadline = Date.now() + (round === 0 ? 90_000 : 45_000);
    let reached = false;
    for (;;) {
      await expect(back).toBeHidden();
      if ((await activeIndex(region)) >= target) {
        reached = true;
        break;
      }
      if (await playbackFinished(page)) {
        break;
      }
      if (Date.now() > deadline) {
        const position = await page
          .locator('audio')
          .evaluate((audio: HTMLAudioElement) => `${audio.currentTime}/${audio.duration}s`);
        throw new Error(
          `playback never reached segment ${target} (still at ${await activeIndex(region)}; audio at ${position})`,
        );
      }
      await page.waitForTimeout(500);
    }
    if (!reached) {
      break; // the audio ended before this segment: nothing more to follow into.
    }
    // The follow scroll brought the formerly out-of-view segment in, moving the region.
    await expect.poll(() => activeInView(region), { timeout: 5_000 }).toBe(true);
    scrolled += 1;
    expect(await region.evaluate((element) => element.scrollTop)).toBeGreaterThan(startTop);
    await expect(back).toBeHidden();
  }
  expect(scrolled).toBeGreaterThanOrEqual(3);

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
