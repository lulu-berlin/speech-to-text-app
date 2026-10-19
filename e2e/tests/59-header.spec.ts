import { expect, test, type Page } from '@playwright/test';
import { HEADER_CLIP } from '../lib/backend.mjs';
import { appLive, ensureTranscript, goTo, openTranscript } from './helpers/ui';

// Item 59, finding 1: the brand is a small static icon in the header corner (at most 40 px,
// padded inside the band, no pastel backdrop), the band is no taller than the icon plus its
// padding, and once the page scrolls the compact header is text-only — no illustration at
// all. The transcript still keeps the playing segment clear of the sticky header.
//
// The artwork's height is measured in the browser: the icon cap is 40 px (2.5 rem at the
// default 16 px root), the band pads it by spacing.xs (0.25 rem = 4 px) on each side and
// carries the 1 px bottom border — 49 px in all. The title and nav line are shorter than
// the icon, so the icon plus its padding and border is the band's height.
const ICON_MAX_PX = 40;
const PADDING_PX = 4;
const BORDER_PX = 1;
const BAND_MAX_PX = ICON_MAX_PX + 2 * PADDING_PX + BORDER_PX;

// The header brand: the artwork's fixed viewBox, whatever the size token. Decorative, so
// this is the only way to locate it.
function brandOf(page: Page) {
  return page.locator('header svg[viewBox="55 61 293 293"]');
}

// The measured header brand and band on the current view: the icon is at most 40 px tall,
// static, inset from every band edge, and the band is no taller than the icon plus its
// padding on both sides.
async function expectBrandInBand(page: Page, view: string) {
  const header = page.locator('header');
  const brand = brandOf(page);
  await expect(brand, `${view}: the header shows exactly one brand icon`).toHaveCount(1);
  await expect(brand).toBeVisible();

  const headerBox = await header.boundingBox();
  const brandBox = await brand.boundingBox();
  expect(headerBox, `${view}: the header has a box`).not.toBeNull();
  expect(brandBox, `${view}: the brand has a box`).not.toBeNull();

  const measured = `${view}: brand height ${brandBox!.height}px`;
  expect(brandBox!.height, measured).toBeLessThanOrEqual(ICON_MAX_PX);
  const band = `${view}: band height ${headerBox!.height}px`;
  expect(headerBox!.height, band).toBeLessThanOrEqual(BAND_MAX_PX);

  // Padding inside the band: the icon touches no edge.
  expect(brandBox!.x - headerBox!.x, `${view}: left padding`).toBeGreaterThan(0);
  expect(brandBox!.y - headerBox!.y, `${view}: top padding`).toBeGreaterThan(0);
  expect(
    headerBox!.y + headerBox!.height - (brandBox!.y + brandBox!.height),
    `${view}: bottom padding`,
  ).toBeGreaterThan(0);
}

test('the header brand is a padded ≤ 40 px static icon, text-only once scrolled, and never covers the playing segment', async ({
  page,
}) => {
  test.setTimeout(480_000);
  await ensureTranscript(page, HEADER_CLIP);

  // Every view: the same compact brand at the top of the page. The queue is reached by the
  // home link, so its own icon is checked after the app's live load.
  await appLive(page);
  await expect(page.getByRole('heading', { name: 'Queue' })).toBeVisible();
  await page.evaluate(() => window.scrollTo(0, 0));
  await expectBrandInBand(page, 'Queue');
  for (const view of ['History', 'Settings'] as const) {
    await goTo(page, view);
    await expect(page.getByRole('heading', { name: view })).toBeVisible();
    await page.evaluate(() => window.scrollTo(0, 0));
    await expectBrandInBand(page, view);
  }

  // The transcript opens with the same corner mark; it is also the page tall enough to
  // scroll in a short viewport, where the compact header must be text-only.
  await openTranscript(page, HEADER_CLIP);
  await page.evaluate(() => window.scrollTo(0, 0));
  await expectBrandInBand(page, 'Transcript');
  await page.setViewportSize({ width: 1280, height: 500 });
  await expect(page.getByRole('region', { name: 'Transcript' })).toBeVisible();
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollHeight > window.innerHeight))
    .toBe(true);
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(0);
  await expect(brandOf(page), 'the compact header shows no illustration').toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Speech-to-Text' })).toBeVisible();
  await expect(page.getByRole('navigation')).toBeVisible();

  // Follow a playing transcript from the top of the page: the playing segment is kept in
  // view and never slips under the sticky header. Sampling spans several segment changes.
  await page.evaluate(() => window.scrollTo(0, 0));
  const region = page.getByRole('region', { name: 'Transcript' });
  await expect(region.locator('[role="button"]').first()).toBeVisible();
  await page.getByRole('button', { name: 'Play' }).click();
  const active = region.locator('[data-active]');
  const firstActive = await active
    .first()
    .textContent()
    .catch(() => null);
  const gaps: number[] = [];
  const header = page.locator('header');
  for (let sample = 0; sample < 40; sample += 1) {
    const activeBox = await active
      .first()
      .boundingBox()
      .catch(() => null);
    const headerBox = await header.boundingBox();
    if (activeBox !== null && headerBox !== null) {
      gaps.push(activeBox.y - (headerBox.y + headerBox.height));
    }
    await page.waitForTimeout(250);
  }
  expect(gaps.length, 'the playing segment was sampled').toBeGreaterThan(0);
  for (const gap of gaps) {
    expect(gap, 'the playing segment stays below the header').toBeGreaterThanOrEqual(-1);
  }
  expect(
    await active
      .first()
      .textContent()
      .catch(() => null),
  ).not.toBe(firstActive);
});

// Discovered while checking item 1: the phone header wraps, so its compact form is taller
// than the desktop one, and the phone scroll margin is a token of its own. This guards
// that the margin still reserves more than the measured header — otherwise a followed
// playing segment could slip under it there.
test('the phone layout’s compact header fits inside the transcript’s phone scroll margin', async ({
  page,
}) => {
  await ensureTranscript(page, HEADER_CLIP);
  await openTranscript(page, HEADER_CLIP);
  await expect(page.getByRole('region', { name: 'Transcript' })).toBeVisible();

  await page.setViewportSize({ width: 390, height: 650 });
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollHeight > window.innerHeight))
    .toBe(true);
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(0);
  await expect(brandOf(page), 'the phone compact header shows no illustration').toHaveCount(0);

  const headerBox = await page.locator('header').boundingBox();
  const margin = await page
    .getByRole('region', { name: 'Transcript' })
    .locator('[role="button"]')
    .first()
    .evaluate((segment) => Number.parseFloat(getComputedStyle(segment).scrollMarginTop));
  expect(headerBox, 'the compact header has a box').not.toBeNull();
  expect(
    margin,
    `the phone scroll margin (${margin}px) reserves the compact header (${headerBox!.height}px)`,
  ).toBeGreaterThanOrEqual(headerBox!.height);
});
