import { readFileSync } from 'node:fs';
import { appUrl } from '../lib/backend.ts';
import { expect, test } from './helpers/test';
import { appLive, setTheme } from './helpers/ui';

// Roadmap item 67, the theme's e2e line (ADR 0068): a mode chosen in the browser is the service's
// setting - a reload paints it and `GET /api/settings` reports it - not the page's own state.
const tokens = JSON.parse(
  readFileSync(new URL('../../ui-theme/colors.json', import.meta.url), 'utf8'),
) as { colors: { background: Record<'light' | 'dark', string> } };

/// `#rrggbb` as the browser's computed `rgb(r, g, b)`.
function rgb(hex: string): string {
  const value = hex.replace('#', '');
  const channel = (at: number) => Number.parseInt(value.slice(at, at + 2), 16);
  return `rgb(${channel(0)}, ${channel(2)}, ${channel(4)})`;
}

test('a mode chosen in the browser survives a reload and is what the service reports', async ({
  page,
  request,
}) => {
  await appLive(page);
  const painted = () => page.evaluate(() => getComputedStyle(document.body).backgroundColor);

  for (const mode of ['light', 'dark'] as const) {
    await setTheme(page, mode);
    await expect(page.getByRole('button', { name: `Theme: ${mode}` })).toBeVisible();

    // The choice is the service's, not the page's: its settings answer carries it.
    await expect
      .poll(async () => {
        const settings = await request.get(appUrl('/api/settings'));
        return ((await settings.json()) as { themeMode?: string }).themeMode;
      })
      .toBe(mode);

    // The page paints that mode's background token.
    await expect.poll(painted).toBe(rgb(tokens.colors.background[mode]));

    // A reload paints it again from the stored settings, and the header shows the stored answer.
    await page.reload();
    await expect(page.getByText('Live', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: `Theme: ${mode}` })).toBeVisible();
    await expect.poll(painted).toBe(rgb(tokens.colors.background[mode]));
  }

  // The spec leaves the shared service as it found it (`themeReset` also guards every spec).
  await setTheme(page, 'system');
});
