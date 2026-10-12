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

// The acceptance of the finished shell (roadmap item 31, ADR 0046): the sticky header
// that stays in view and shrinks on scroll, the background-pill navigation, the mascot's
// home link, and the one-mascot rule — with the new shell's screenshots (light/dark,
// phone/desktop), saved under e2e/screenshots/ for the user's visual approval. The suite
// runs this file last in its one-worker journey: item 15 cancelled its seed and its mate
// finished, so the queue starts empty — this spec asserts exactly that as its
// precondition — and this spec's own long clip owns the rest of the state.
const THEME_STEP = 4;

test('the finished shell: sticky shrinking header, nav pills, mascot home link, one mascot', async ({
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

  const headerMascot = page.locator(`header ${MASCOT}`);
  const headerMascotWidth = async () => (await headerMascot.boundingBox())?.width;

  // The empty queue: the placeholder shows the mascot at the empty size and the header
  // the small static logo at the compact size — the animated mascot appears only once.
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
  // The compact size is reached through an entrance animation, so poll until it settles
  // (the same idiom the transcript-scrolled check below uses).
  await expect.poll(headerMascotWidth).toBe(px(4));
  await expect(page.locator('.is-typing')).toHaveCount(0);
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
  // header shows the full-size animated mascot again (it types, too — a task runs).
  await uploadClips(page, [HEADER_CLIP]);
  const running = rowOf(page, HEADER_CLIP);
  await expect(running.getByText('Transcribing')).toBeVisible({ timeout: 120_000 });
  await page.evaluate(() => window.scrollTo(0, 0));
  await expect(headerMascot).toBeVisible();
  expect(await headerMascotWidth()).toBe(px(7.5));
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
  // drives the header's shrink; the transcript region scrolls internally otherwise).
  // Scrolling down keeps the sticky header in view and shrinks its mascot to the
  // compact size; it grows back at the top. The compact mascot keeps rest (nothing is
  // running here), and the shrink transition is settled by the poll.
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
    await expect.poll(headerMascotWidth).toBe(px(4));
    await expect(headerMascot).toBeInViewport();
    for (const theme of ['light', 'dark'] as const) {
      await shot('shell-transcript-scrolled', width, theme, viewport);
    }
  }
  await page.setViewportSize(DESKTOP_VIEWPORT);
  await page.evaluate(() => window.scrollTo(0, 0));
  await expect.poll(headerMascotWidth).toBe(px(7.5));
});

// The compact (4rem) and full (7.5rem) header mascot sizes, as the browser reports
// bounding boxes in CSS pixels at the default 16 px root font size.
function px(rem: number): number {
  return rem * 16;
}
