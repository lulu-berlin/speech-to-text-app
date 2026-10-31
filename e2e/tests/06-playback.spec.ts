import { expect, test, type Locator } from '@playwright/test';
import { PLAYBACK_CLIP } from '../lib/backend.ts';
import { SEGMENTS, activeIndex, activeInView, audioSecond } from './helpers/transcript';
import { uploadAndOpen } from './helpers/ui';

// The seconds the player's display shows ("00:12 / 01:39", the first field; 0 when unparsable).
function secondsOf(text: string): number {
  const [minutes = '0', seconds = '0'] = (text.split(' / ')[0] ?? '').split(':');
  return Number(minutes) * 60 + Number(seconds);
}

// The seconds the player's display shows right now.
async function displayedSeconds(times: Locator): Promise<number> {
  return secondsOf((await times.textContent()) ?? '');
}

// The index of a segment fully visible in the region that is not the highlighted one.
async function visibleOtherSegment(region: Locator): Promise<number> {
  return region.evaluate((element, segments) => {
    const box = element.getBoundingClientRect();
    return [...element.querySelectorAll(segments)].findIndex((segment) => {
      const rect = segment.getBoundingClientRect();
      return (
        !segment.hasAttribute('data-active') && rect.top >= box.top && rect.bottom <= box.bottom
      );
    });
  }, SEGMENTS);
}

// The waiting rule (roadmap item 84): every wait here is an awaited outcome — an element's state, a
// display value, a scroll position — never a window in which nothing must happen.
test('playback: the highlight follows seeking and pausing, a double-click seeks and plays, and follow mode holds', async ({
  page,
}) => {
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
  await expect(back).toBeHidden();
  await page.keyboard.press('Home');
  await expect.poll(() => activeIndex(region)).toBe(0);
  await expect.poll(() => activeInView(region)).toBe(true);
  expect(await scrollTop()).toBeLessThan(farDown);
  await expect(back).toBeHidden();

  // The highlight follows pausing: paused is a state no later movement can escape, so the display
  // and the highlight must agree with the element's frozen position.
  await page.getByRole('button', { name: 'Play', exact: true }).click();
  const started = await times.textContent();
  await expect.poll(() => times.textContent(), { timeout: 10_000 }).not.toBe(started);
  await page.getByRole('button', { name: 'Pause' }).click();
  const pausedPosition = await audioSecond(page);
  const pausedIndex = await activeIndex(region);
  expect(
    await page.locator('audio').evaluate((audio: HTMLAudioElement) => audio.paused),
    'the click paused the element, so nothing can move the playhead again',
  ).toBe(true);
  await expect
    .poll(
      async () => Math.abs((await displayedSeconds(times)) - (await audioSecond(page))) <= 1.05,
      { timeout: 10_000 },
    )
    .toBe(true);
  expect(Math.abs((await audioSecond(page)) - pausedPosition)).toBeLessThanOrEqual(0.05);
  expect(await activeIndex(region)).toBe(pausedIndex);
  await expect(back).toBeHidden();

  // Double-clicking a visible segment plays from it (item 52: a single click only moves the
  // player there): it becomes the highlighted one, and the player shows Pause (playing).
  const target = await visibleOtherSegment(region);
  expect(target).toBeGreaterThanOrEqual(0);
  await region.locator(SEGMENTS).nth(target).dblclick();
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
  // The resumed follow scroll must not stop following once more. The window is playback's own
  // progress — the display advancing three seconds — not a sleep, so a stop that lands later than a
  // short hold-off is caught too (roadmap item 84).
  const resumedAt = await displayedSeconds(times);
  await expect
    .poll(
      async () => {
        expect(
          await back.isHidden(),
          `following held at display ${await times.textContent()}`,
        ).toBe(true);
        return displayedSeconds(times);
      },
      { timeout: 30_000 },
    )
    .toBeGreaterThan(resumedAt + 2);
});
