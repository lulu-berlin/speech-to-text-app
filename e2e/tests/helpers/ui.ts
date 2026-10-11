import { expect, type Locator, type Page } from '@playwright/test';
import { appUrl } from '../../lib/backend.mjs';

// The two widths the spec fixes: phone ~390 px, desktop ~1280 px.
export const PHONE_VIEWPORT = { width: 390, height: 844 };
export const DESKTOP_VIEWPORT = { width: 1280, height: 800 };

// The mascot (item 17): the stenographer SVG. It is decorative (aria-hidden, no role),
// so it is found by the artwork's fixed viewBox. The class is-typing is present only
// while a task runs.
export const MASCOT = 'svg[viewBox="55 61 293 293"]';

// The shell answered by the built frontend: the brand title as the page title and
// heading, the navigation — and no uncaught error while it renders.
export async function expectShellAt(page: Page) {
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
export async function expectLiveQueue(page: Page) {
  await expect(page.getByText('Live', { exact: true })).toBeVisible();
  await expect(
    page.getByText('No tasks yet — drop audio files here, or click to choose them'),
  ).toBeVisible();
}

// The mascot directly above a region's label. Two layouts: the loading regions and the
// History empty state put the label in a paragraph right after the mascot (`svg + p`),
// the queue drop zone's zone span contains the label text with the mascot as its direct
// child. Playwright's xpath chaining supports only `..`, so these CSS forms carry the
// structure. One locator with a comma union: whichever shape matches this label wins.
export function mascotAbove(page: Page, label: string): Locator {
  return page.locator(`svg + p:has-text("${label}"), span:has-text("${label}") > svg`);
}
