import { expect, test, type Locator, type Page } from '@playwright/test';
import { FOLLOW_CLIP } from '../lib/backend.ts';
import { SEGMENTS, activeIndex, activeInView, audioSecond } from './helpers/transcript';
import { uploadAndOpen } from './helpers/ui';

// The first segment after the highlighted one that starts below the region's visible box
// — the next content the playhead runs into that following has to scroll to (-1: none).
async function nextOutOfViewSegment(region: Locator): Promise<number> {
  return region.evaluate((element, selector) => {
    const segments = [...element.querySelectorAll(selector)];
    const activeIndex = segments.findIndex((segment) => segment.hasAttribute('data-active'));
    const box = element.getBoundingClientRect();
    return segments.findIndex((segment, index) => {
      if (index <= activeIndex) {
        return false;
      }
      return segment.getBoundingClientRect().top >= box.bottom - 1;
    });
  }, SEGMENTS);
}

// Whether the playhead moves on from `from` within a bound. A frozen playhead returns false rather
// than throwing, so the caller can tell the item-38 stall from the clip playing out.
async function playheadAdvanced(page: Page, from: number): Promise<boolean> {
  try {
    await expect.poll(() => audioSecond(page), { timeout: 20_000 }).toBeGreaterThan(from);
    return true;
  } catch {
    return false;
  }
}

// Waits for the playhead to enter `target` with the user's hands off, returning false
// only if the audio plays out (ended) before the segment is reached — the joined flac
// of this clip plays through to the end, so a stop that is not the end is the item-38
// stall and fails with the position it froze at. A "Back to playing position" button at
// any moment is an automatic stop and fails the wait too. Progress is the audio element's
// own currentTime advancing; nothing here sleeps for a fixed spell (roadmap item 84).
async function playheadReaches(
  page: Page,
  region: Locator,
  back: Locator,
  target: number,
): Promise<boolean> {
  for (;;) {
    await expect(back).toBeHidden();
    if ((await activeIndex(region)) >= target) {
      return true;
    }
    const from = await audioSecond(page);
    if (await playheadAdvanced(page, from)) {
      continue;
    }
    if (await page.locator('audio').evaluate((audio: HTMLAudioElement) => audio.ended)) {
      return false; // played out before this segment: nothing more to follow into.
    }
    throw new Error(`playback stalled at ${from.toFixed(1)}s before segment ${target}`);
  }
}

// Item 36, ADR 0046: real playback follows the moving playhead through many follow
// scrolls and "Back to playing position" never appears without a hand scroll — the race
// this item fixes made it appear on its own while recording item 35's playback GIF. A
// hand scroll still stops following, and resuming holds through its own follow scroll.
test('follow mode: long playback never shows "Back to playing position" without a hand scroll; a wheel still shows it', async ({
  page,
}) => {
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
  await page.getByRole('button', { name: 'Play', exact: true }).click();
  let scrolled = 0;
  for (let round = 0; round < 4; round += 1) {
    const target = await nextOutOfViewSegment(region);
    if (target === -1) {
      break; // nothing left below the fold: the transcript is fully followed.
    }
    const startTop = await region.evaluate((element) => element.scrollTop);
    // Wait for playback to reach the out-of-view segment on its own; a freeze before the
    // segment fails as the joined-FLAC stall (item 38), so the rounds only end when the
    // audio plays out or the transcript runs out of segments.
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
  // The hands-off rounds crossed the former stall point: the joined flac must play past
  // ~90 s of the 154 s clip (item 38), or this fails instead of forgiving a stall.
  expect(await audioSecond(page)).toBeGreaterThan(90);

  // A hand scroll (wheel) still stops following: the button appears.
  await region.hover();
  await page.mouse.wheel(0, 400);
  await expect(back).toBeVisible();

  // Resuming follows again, and its own follow scroll does not stop it once more. The window is
  // playback's own progress — the playhead advancing three seconds — not a sleep, so a stop that
  // lands after a short hold-off is caught too (roadmap item 84).
  await back.click();
  await expect.poll(() => activeInView(region)).toBe(true);
  const resumedAt = await audioSecond(page);
  await expect
    .poll(
      async () => {
        expect(
          await back.isHidden(),
          `following held at ${(await audioSecond(page)).toFixed(1)}s`,
        ).toBe(true);
        return audioSecond(page);
      },
      { timeout: 30_000 },
    )
    .toBeGreaterThan(resumedAt + 2);

  // The clip plays through to its end (item 38): the rounds above left playback near
  // ~130 s of the 154 s clip, and it must keep going and fire `ended` on its own — the
  // joined-FLAC stall this item was filed for (playhead frozen at ~90 s, `ended` never
  // firing) would fail right here. The clip is long, so a short file cannot fake it.
  const duration = await page
    .locator('audio')
    .evaluate((audio: HTMLAudioElement) => audio.duration);
  expect(duration).toBeGreaterThan(90);
  await expect
    .poll(() => page.locator('audio').evaluate((audio: HTMLAudioElement) => audio.ended), {
      timeout: 90_000,
    })
    .toBe(true);
});
