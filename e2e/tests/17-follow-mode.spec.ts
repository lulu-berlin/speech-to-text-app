import { expect, test, type Locator } from '@playwright/test';
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

  // The transcript's opening renders inside the region's box, so following does not need
  // to scroll until the playhead passes the fold. Seek past it first (the slider's
  // PageUp jumps 10 s, as in the playback journey), then play through the part of the
  // transcript where keeping the active segment in view takes real scrolling every
  // segment or two.
  const seekBar = page.getByRole('slider', { name: 'Seek' });
  await seekBar.focus();
  for (let press = 0; press < 7; press += 1) {
    await page.keyboard.press('PageUp');
  }
  await expect.poll(() => activeIndex(region)).toBeGreaterThan(0);
  await page.getByRole('button', { name: 'Play' }).click();

  // Sample ~30 s of playback: on every sample the button stays hidden while the region
  // actually scrolls. The distinct scrollTop count proves several follow scrolls took
  // place (the view was not sitting still), and the highlight stays in view throughout.
  const scrollTops = new Set<number>();
  for (let sample = 0; sample < 30; sample += 1) {
    await expect(back).toBeHidden();
    scrollTops.add(await region.evaluate((element) => element.scrollTop));
    await expect.poll(() => activeInView(region)).toBe(true);
    await page.waitForTimeout(1_000);
  }
  expect(scrollTops.size).toBeGreaterThanOrEqual(5);
  await expect(back).toBeHidden();

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
