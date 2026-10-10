import { expect, test, type Page } from '@playwright/test';
import { appUrl } from '../lib/backend.mjs';

// The two widths the spec fixes: phone ~390 px, desktop ~1280 px.
const PHONE_VIEWPORT = { width: 390, height: 844 };
const DESKTOP_VIEWPORT = { width: 1280, height: 800 };

// The shell answered by the built frontend: the brand title as the page title and
// heading, the navigation — and no uncaught error while it renders.
async function expectShellAt(page: Page) {
  const pageErrors: string[] = [];
  const onError = (error: Error) => pageErrors.push(error.message);
  page.on('pageerror', onError);
  await page.goto(appUrl('/'));
  await expect(page).toHaveTitle('Speech-to-Text');
  await expect(page.getByRole('heading', { name: 'Speech-to-Text' })).toBeVisible();
  await expect(page.getByRole('navigation')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Queue' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'History' })).toBeVisible();
  page.off('pageerror', onError);
  expect(pageErrors).toEqual([]);
}

// The app only counts as loaded against the real backend once its event stream has
// connected and the first snapshot arrived: the header's indicator turns "Live" and the
// queue's empty state offers the drop zone.
async function expectLiveQueue(page: Page) {
  await expect(page.getByText('Live', { exact: true })).toBeVisible();
  await expect(
    page.getByText('No tasks yet — drop audio files here, or click to choose them'),
  ).toBeVisible();
}

test('starts the real backend and loads the built app at phone and desktop widths', async ({
  page,
}) => {
  for (const [name, viewport] of [
    ['desktop width', DESKTOP_VIEWPORT],
    ['phone width', PHONE_VIEWPORT],
  ] as const) {
    await test.step(name, async () => {
      await page.setViewportSize(viewport);
      await expectShellAt(page);
      await expectLiveQueue(page);
      // Loaded without horizontal overflow at the page level (the layout must fit this
      // width; item 25's geometry tests assert the CSS rules, this checks the result).
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
      );
      expect(overflow).toBeLessThanOrEqual(0);
    });
  }
});
