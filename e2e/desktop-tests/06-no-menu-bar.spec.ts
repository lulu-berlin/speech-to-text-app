import { expect, test } from '@playwright/test';
import { launchDesktopApp } from './helpers/app';

// Roadmap item 50 (ADR 0046), discovered item (the user, after trying `nix run .#desktop`): the
// window is the app's UI and nothing else — no File/Edit/View/Window/Help menu bar above it.
// Electron installs that default menu whenever an app sets none, and on Linux it is a visible bar,
// so the main process is asked directly: no application menu, and the window shows no menu bar.
test('the app window has no menu bar', async () => {
  const app = await launchDesktopApp();
  try {
    const window = await app.firstWindow();
    await expect(window.getByText('Live', { exact: true })).toBeVisible({ timeout: 120_000 });

    // Runs in Electron's main process; e2e/ has no Electron types, so the shape used is spelled out.
    const menu = await app.evaluate(({ BrowserWindow, Menu }) => {
      const windows: { isMenuBarVisible(): boolean }[] = BrowserWindow.getAllWindows();
      return {
        applicationMenu: Menu.getApplicationMenu() !== null,
        menuBarsVisible: windows.map((each) => each.isMenuBarVisible()),
      };
    });
    expect(menu).toEqual({ applicationMenu: false, menuBarsVisible: [false] });
  } finally {
    await app.close();
  }
});
