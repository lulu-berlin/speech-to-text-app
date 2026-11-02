import { expect, test } from '@playwright/test';
import { browserLaunchUrl, registerFrontend, startAppModeService } from './helpers/service';
import { goTo } from './helpers/ui';

// Roadmap item 66, e2e line E3 (ADR 0046): the Settings page offers exactly the frontends the
// registry holds as default-frontend choices, and the chosen default and the terminal command
// survive a reload — they are the service's settings, not the page's state.
test('the Settings page lists the registered frontends and keeps the default and terminal command', async ({
  page,
}) => {
  test.setTimeout(600_000);
  const service = await startAppModeService();
  try {
    registerFrontend(service, { id: 'browser', name: 'Browser', kind: 'browser', url: '/' });
    registerFrontend(service, {
      id: 'tui',
      name: 'Terminal TUI',
      kind: 'terminal',
      command: 'stt-tui',
      args: [],
    });

    await page.goto(browserLaunchUrl(service));
    await expect(page.getByText('Live', { exact: true })).toBeVisible();
    await goTo(page, 'Settings');

    const defaultFrontend = page.getByRole('combobox', { name: 'Default frontend' });
    await expect
      .poll(async () => (await defaultFrontend.locator('option').allTextContents()).join('|'))
      .toBe('None|Browser|Terminal TUI');

    await defaultFrontend.selectOption('tui');
    const terminal = page.getByRole('textbox', { name: 'Terminal command' });
    await terminal.fill('wezterm start --');
    await terminal.blur();

    // Both writes are the service's: its settings answer carries them.
    await expect
      .poll(async () =>
        page.evaluate(async () => {
          const settings = (await fetch('/api/settings').then((r) => r.json())) as {
            defaultFrontend: string | null;
            terminalCommand: string | null;
          };
          return `${settings.defaultFrontend ?? 'none'}|${settings.terminalCommand ?? 'none'}`;
        }),
      )
      .toBe('tui|wezterm start --');

    await page.reload();
    await expect(page.getByRole('combobox', { name: 'Default frontend' })).toHaveValue('tui');
    await expect(page.getByRole('textbox', { name: 'Terminal command' })).toHaveValue(
      'wezterm start --',
    );
  } finally {
    await service.stop();
  }
});
