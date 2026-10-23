import { expect, test, type Locator, type Page } from '@playwright/test';
import { PLAYBACK_CLIP } from '../lib/backend.mjs';
import { uploadAndOpen } from './helpers/ui';

// The index of the highlighted segment among the transcript's segment buttons (-1: none).
async function activeIndex(region: Locator): Promise<number> {
  return region.evaluate((element) =>
    [...element.querySelectorAll('[role="button"]')].findIndex((segment) =>
      segment.hasAttribute('data-active'),
    ),
  );
}

// The audio element's playback position in seconds: the pause signal, independent of when the display
// renders the last timeupdate (see the paused-time check below).
async function audioPosition(page: Page): Promise<number> {
  return page.locator('audio').evaluate((audio: HTMLAudioElement) => audio.currentTime);
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

// The index of a segment fully visible in the region that is not the highlighted one.
async function visibleOtherSegment(region: Locator): Promise<number> {
  return region.evaluate((element) => {
    const box = element.getBoundingClientRect();
    return [...element.querySelectorAll('[role="button"]')].findIndex((segment) => {
      const rect = segment.getBoundingClientRect();
      return (
        !segment.hasAttribute('data-active') && rect.top >= box.top && rect.bottom <= box.bottom
      );
    });
  });
}

test('playback: the highlight follows seeking and pausing, a click seeks and plays, and follow mode holds', async ({
  page,
}) => {
  test.setTimeout(300_000);
  await uploadAndOpen(page, PLAYBACK_CLIP);

  // A short viewport: the transcript region (max-height 60vh) is then far shorter than the
  // long clip's transcript, so keeping the highlight in view takes real scrolling.
  await page.setViewportSize({ width: 1280, height: 500 });
  const region = page.getByRole('region', { name: 'Transcript' });
  await expect(region.getByRole('button').first()).toBeVisible();
  expect(await region.evaluate((element) => element.scrollHeight > element.clientHeight + 50)).toBe(
    true,
  );
  const back = page.getByRole('button', { name: 'Back to playing position' });
  const seekBar = page.getByRole('slider', { name: 'Seek' });
  const times = page.getByText(/^\d\d:\d\d \/ \d\d:\d\d$/);

  const scrollTop = () => region.evaluate((element) => element.scrollTop);

  // The highlight follows seeking: 12 × PageUp (10 s each) lands near the end of the clip,
  // far below the region's visible part, then Home returns to the start, far above it. Each
  // seek makes following scroll — asserted via scrollTop — and following must hold through
  // its own scroll: the scroll event of the view's scrollIntoView must not read as a hand
  // scroll (the hold-off check carried from item 15), so "Back to playing position" stays
  // hidden after each follow scroll.
  await seekBar.focus();
  for (let press = 0; press < 12; press += 1) {
    await page.keyboard.press('PageUp');
  }
  await expect.poll(() => activeIndex(region)).toBeGreaterThan(0);
  await expect.poll(() => activeInView(region)).toBe(true);
  const farDown = await scrollTop();
  expect(farDown).toBeGreaterThan(0);
  await page.waitForTimeout(500);
  await expect(back).toBeHidden();
  await page.keyboard.press('Home');
  await expect.poll(() => activeIndex(region)).toBe(0);
  await expect.poll(() => activeInView(region)).toBe(true);
  expect(await scrollTop()).toBeLessThan(farDown);
  await page.waitForTimeout(500);
  await expect(back).toBeHidden();

  // The highlight follows pausing: the audio element's position is frozen (0.05 s tolerates one
  // stalled sample, not the one-second advance a still-playing element makes), and once the display
  // has caught up it stops changing together with the highlight. The display can render one more
  // timeupdate after the click, so binding the freeze to the element — not to a text read taken right
  // after Pause — is what makes the check race-free (PR #46).
  await page.getByRole('button', { name: 'Play' }).click();
  const started = await times.textContent();
  await expect.poll(() => times.textContent(), { timeout: 10_000 }).not.toBe(started);
  await page.getByRole('button', { name: 'Pause' }).click();
  const pausedPosition = await audioPosition(page);
  await page.waitForTimeout(1_500);
  expect(Math.abs((await audioPosition(page)) - pausedPosition)).toBeLessThanOrEqual(0.05);
  const pausedTime = await times.textContent();
  const pausedIndex = await activeIndex(region);
  await page.waitForTimeout(500);
  expect(await times.textContent()).toBe(pausedTime);
  expect(await activeIndex(region)).toBe(pausedIndex);
  await expect(back).toBeHidden();

  // Clicking a visible segment seeks to it and plays: it becomes the highlighted one, and
  // the player shows Pause (playing).
  const target = await visibleOtherSegment(region);
  expect(target).toBeGreaterThanOrEqual(0);
  await region.getByRole('button').nth(target).click();
  await expect.poll(() => activeIndex(region)).toBe(target);
  await expect(page.getByRole('button', { name: 'Pause' })).toBeVisible();

  // A hand scroll stops following: "Back to playing position" appears. It resumes following,
  // which scrolls the highlight back into view — and that follow scroll, too, must not stop
  // following by itself.
  const atTop = await region.evaluate((element) => element.scrollTop === 0);
  await region.hover();
  await page.mouse.wheel(0, atTop ? 400 : -400);
  await expect(back).toBeVisible();
  await back.click();
  await expect.poll(() => activeInView(region)).toBe(true);
  await page.waitForTimeout(500);
  await expect(back).toBeHidden();
});
