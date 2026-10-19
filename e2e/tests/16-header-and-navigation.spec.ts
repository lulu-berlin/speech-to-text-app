import { expect, test } from '@playwright/test';
import { HEADER_CLIP } from '../lib/backend.mjs';
import {
  DESKTOP_VIEWPORT,
  MASCOT,
  PHONE_VIEWPORT,
  appLive,
  goTo,
  openTranscript,
  rowOf,
  uploadClips,
} from './helpers/ui';

// The acceptance of the finished shell (roadmap item 31, ADR 0046; item 59 for the brand
// and the compact header): the sticky header that stays in view and turns text-only on
// scroll, its static 40 px brand mark, the background-pill navigation and the brand's home
// link — with the new shell's screenshots (light/dark, phone/desktop), saved under
// e2e/screenshots/ for the user's visual approval. The suite runs this file after the
// queue journey: item 15 cancelled its seed and its mate finished, so the queue starts
// empty — this spec asserts exactly that as its precondition — and this spec's own long
// clip owns the rest of the state.
const THEME_STEP = 4;

test('the finished shell: sticky header, nav pills, brand home link, text-only compact header', async ({
  page,
}) => {
  test.setTimeout(420_000);

  const themeButton = page.getByRole('button', { name: /Theme:/ });
  const setTheme = async (mode: 'light' | 'dark' | 'system') => {
    // The button's accessible name is the current mode (AppHeader THEME_LABEL); the
    // cycle is system -> light -> dark -> system, so clicking until it shows is bounded.
    for (let guard = 0; guard < THEME_STEP; guard++) {
      if ((await themeButton.getAttribute('aria-label')) === `Theme: ${mode}`) return;
      await themeButton.click();
    }
  };
  const shot = async (
    name: string,
    width: 'phone' | 'desktop',
    theme: 'light' | 'dark',
    viewport: { width: number; height: number },
  ) => {
    await page.setViewportSize(viewport);
    await setTheme(theme);
    await expect(page.getByRole('button', { name: `Theme: ${theme}` })).toBeVisible();
    await page.screenshot({ path: `screenshots/${name}-${theme}-${width}.png` });
  };

  // The brand mark (item 59): the artwork's viewBox, whatever its size. Lucide icons in
  // the controls carry their own viewBox, so only the brand matches.
  const headerMascot = page.locator(`header ${MASCOT}`);

  // The empty queue: the placeholder shows the full illustration at the empty size, and
  // the header its static 40 px brand mark (item 59).
  await appLive(page);
  await expect(
    page.getByText('No tasks yet — drop audio files here, or click to choose them'),
  ).toBeVisible();
  const dropZoneMascot = page.locator(
    `span:has-text("No tasks yet — drop audio files here, or click to choose them") > ${MASCOT}`,
  );
  await expect(dropZoneMascot).toBeVisible();
  expect((await dropZoneMascot.boundingBox())?.width).toBe(px(8.75));
  await expect(headerMascot).toBeVisible();
  // Item 59: a 40 px icon; it types only while a task runs (none here).
  expect((await headerMascot.boundingBox())?.height).toBe(px(2.5));
  await expect(page.locator('header .is-typing')).toHaveCount(0);
  for (const width of ['desktop', 'phone'] as const) {
    for (const theme of ['light', 'dark'] as const) {
      await shot(
        'shell-empty',
        width,
        theme,
        width === 'desktop' ? DESKTOP_VIEWPORT : PHONE_VIEWPORT,
      );
    }
  }

  // The nav pill follows the route: the active link is the filled pill, the others are
  // bare until hovered. With History and Settings both inactive there are two of them
  // (item 32 added the Settings pill), so every inactive link is checked.
  const activePill = page.locator('nav a.active');
  await expect(activePill).toHaveText('Queue');
  expect(
    await activePill.evaluate((element) => getComputedStyle(element).backgroundColor),
  ).not.toBe('rgba(0, 0, 0, 0)');
  const inactivePillColors = await page
    .locator('nav a:not(.active)')
    .evaluateAll((elements) =>
      elements.map((element) => getComputedStyle(element).backgroundColor),
    );
  expect(inactivePillColors).not.toHaveLength(0);
  for (const color of inactivePillColors) {
    expect(color).toBe('rgba(0, 0, 0, 0)');
  }
  await goTo(page, 'History');
  await expect(activePill).toHaveText('History');
  await expect(page.getByRole('heading', { name: 'History' })).toBeVisible();

  // The mascot's home link returns to the queue — by click, and by Enter on the focused
  // link (the browser's native activation, which jsdom cannot synthesize).
  const home = page.getByRole('link', { name: 'Speech-to-Text home' });
  await expect(home).toHaveAttribute('href', '/');
  await home.click();
  await expect(page.getByRole('heading', { name: 'Queue' })).toBeVisible();
  await goTo(page, 'History');
  await expect(page.getByRole('heading', { name: 'History' })).toBeVisible();
  await home.focus();
  await home.press('Enter');
  await expect(page.getByRole('heading', { name: 'Queue' })).toBeVisible();

  // This spec's own long clip: while it transcribes, the queue is not empty, so the
  // header shows its brand mark again — typing, while the task runs (item 59, reversed
  // 2026-10-18: the 40 px mark carries the animation).
  await uploadClips(page, [HEADER_CLIP]);
  const running = rowOf(page, HEADER_CLIP);
  await expect(running.getByText('Transcribing')).toBeVisible({ timeout: 120_000 });
  await page.evaluate(() => window.scrollTo(0, 0));
  await expect(headerMascot).toBeVisible();
  expect((await headerMascot.boundingBox())?.height).toBe(px(2.5));
  await expect(page.locator('header .is-typing')).toHaveCount(1);
  for (const width of ['desktop', 'phone'] as const) {
    for (const theme of ['light', 'dark'] as const) {
      await shot(
        'shell-queue-running',
        width,
        theme,
        width === 'desktop' ? DESKTOP_VIEWPORT : PHONE_VIEWPORT,
      );
    }
  }
  await expect(page.getByText(`${HEADER_CLIP} transcribed`)).toBeVisible({ timeout: 240_000 });

  // The transcript: short viewports so the page itself scrolls (the window scroll
  // drives the header's compact form; the transcript region scrolls internally
  // otherwise). Scrolling down keeps the sticky header in view and drops its
  // illustration — the compact header is text-only (item 59) — and the mark returns at
  // the top.
  await openTranscript(page, HEADER_CLIP);
  // The heading renders before the turns load; only the region makes the page tall
  // enough to scroll, so wait for it.
  await expect(page.getByRole('region', { name: 'Transcript' })).toBeVisible();
  for (const width of ['desktop', 'phone'] as const) {
    const viewport =
      width === 'desktop' ? { width: 1280, height: 600 } : { width: 390, height: 650 };
    await page.setViewportSize(viewport);
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollHeight > window.innerHeight))
      .toBe(true);
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    await expect(headerMascot).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'Speech-to-Text' })).toBeInViewport();
    for (const theme of ['light', 'dark'] as const) {
      await shot('shell-transcript-scrolled', width, theme, viewport);
    }
  }
  await page.setViewportSize(DESKTOP_VIEWPORT);
  await expect(headerMascot).toBeVisible();
});

// The shell's icon size (item 59) and the empty drop zone's illustration, as the browser
// reports bounding boxes in CSS pixels at the default 16 px root font size.
function px(rem: number): number {
  return rem * 16;
}
