import { expect, test, type Page } from '@playwright/test';
import { MASCOT, appLive } from './helpers/ui';

// Item 59, finding 1: the brand used to carry the illustration's pastel backdrop into the
// header. That decision was implemented in the compact-header cycle; this screenshot check
// locks it in. A backdrop fills the whole artwork box, so every corner of the rendered
// icon would be a backdrop colour; without one, the corners show the header behind the
// mark. Both themes are compared.
async function setTheme(page: Page, mode: 'light' | 'dark') {
  const button = page.getByRole('button', { name: /Theme:/ });
  for (let step = 0; step < 4; step += 1) {
    if ((await button.getAttribute('aria-label')) === `Theme: ${mode}`) {
      return;
    }
    await button.click();
  }
  throw new Error(`the theme did not reach ${mode}`);
}

test('the header brand icon has no pastel backdrop in either theme', async ({ page }) => {
  await appLive(page);
  await page.evaluate(() => window.scrollTo(0, 0));
  const brand = page.locator(`header ${MASCOT}`);
  await expect(brand).toBeVisible();
  const box = await brand.boundingBox();
  expect(box, 'the brand has a box').not.toBeNull();

  for (const mode of ['light', 'dark'] as const) {
    await setTheme(page, mode);
    const background = await page
      .locator('header')
      .evaluate((header) => getComputedStyle(header).backgroundColor);
    const shot = await page.screenshot({
      clip: { x: box!.x, y: box!.y, width: box!.width, height: box!.height },
      type: 'png',
    });
    const transparent = await page.evaluate(
      async ({ png, background }) => {
        const image = new Image();
        image.src = `data:image/png;base64,${png}`;
        await image.decode();
        const canvas = document.createElement('canvas');
        canvas.width = image.width;
        canvas.height = image.height;
        const context = canvas.getContext('2d');
        if (context === null) {
          throw new Error('no 2d context');
        }
        context.drawImage(image, 0, 0);
        const pixels = context.getImageData(0, 0, image.width, image.height).data;
        let matching = 0;
        for (let index = 0; index < pixels.length; index += 4) {
          if (`rgb(${pixels[index]}, ${pixels[index + 1]}, ${pixels[index + 2]})` === background) {
            matching += 1;
          }
        }
        return matching;
      },
      { png: shot.toString('base64'), background },
    );

    expect(
      transparent,
      `${mode}: the artwork leaves header-coloured pixels around it (not a backdrop block)`,
    ).toBeGreaterThan(0);
  }
});
