import { expect, test } from '@playwright/test';
import { appLive, goTo } from './helpers/ui';

// Item 72's error surfaces in a journey (ADR 0046): a rejected command and a failed settings
// load are visible to the user, and Retry recovers the settings form.

test('a rejected queue pause says so beside the switch and leaves it unpaused', async ({
  page,
}) => {
  await page.route('**/api/queue/pause', (route) =>
    route.fulfill({
      status: 409,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'queue-busy', message: 'The queue is busy' }),
    }),
  );
  await appLive(page);

  const pause = page.getByRole('switch', { name: 'Pause the queue' });
  await pause.click();

  const alert = page.getByRole('alert').filter({ hasText: 'Could not pause the queue' });
  await expect(alert).toBeVisible();
  await expect(alert).toContainText('The queue is busy');
  await expect(pause).not.toBeChecked();
  await page.unroute('**/api/queue/pause');
});

test('a failed settings load offers Retry, which fetches the stored settings', async ({ page }) => {
  let gets = 0;
  await page.route('**/api/settings', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue();
      return;
    }
    gets += 1;
    if (gets === 1) {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({
          error: 'internal',
          message: 'The settings could not be read',
        }),
      });
      return;
    }
    await route.continue();
  });
  await appLive(page);
  await goTo(page, 'Settings');

  const alert = page.getByRole('alert').filter({ hasText: 'The settings could not be read' });
  await expect(alert).toBeVisible();
  await expect(page.locator('#device')).toBeDisabled();

  await alert.getByRole('button', { name: 'Retry' }).click();
  await expect(page.locator('#device')).toBeEnabled();
  await expect(page.locator('#threads')).toBeVisible();
  await expect(alert).toHaveCount(0);
  await page.unroute('**/api/settings');
});
