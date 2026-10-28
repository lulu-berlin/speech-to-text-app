import { expect, test } from '@playwright/test';
import { SETTINGS_CLIP, loadState, saveState, startBackend, stopBackend } from '../lib/backend.ts';
import { appLive, goTo, uploadClips } from './helpers/ui';

// The settings journey (item 32, ADR 0046): change the device, the thread count and the
// upload defaults on the Settings page; restart the backend on the same data directory —
// the choices are kept and the upload form starts with the new defaults; a task runs
// with the stored device. The device is set to the CPU (the suite must not assume a GPU:
// probes may or may not report one), and the thread count is set to 2, which whisper-cli
// logs as `-t 2`.
test('the settings the user saves survive a restart and the queue runs with them', async ({
  page,
}) => {
  test.setTimeout(300_000);

  // The Settings page is reached from the navigation.
  await appLive(page);
  await goTo(page, 'Settings');
  await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
  const device = page.locator('#device');
  await expect(device).toHaveValue('auto');
  // CPU and Auto are always offered; the probe's GPUs may be there too, labeled
  // "GPU N — <name>" (item 45).
  await expect
    .poll(async () => page.locator('#device option').allTextContents())
    .toEqual(expect.arrayContaining(['CPU', 'Auto']));
  const optionTexts = await page.locator('#device option').allTextContents();
  for (const text of optionTexts) {
    expect(text).toMatch(/^(CPU|Auto|GPU \d+( — .+)?)$/);
  }
  // Where the probe reported GPUs, each option is labeled with the probe's name for it
  // (item 45); with no GPU — this suite must not assume one — the loop is vacuous.
  const gpuNames = await page.evaluate(async () => {
    const settings = (await fetch('/api/settings').then((r) => r.json())) as {
      gpuNames: Record<string, string>;
    };
    return settings.gpuNames;
  });
  for (const [key, name] of Object.entries(gpuNames)) {
    expect(optionTexts).toContain(`GPU ${key.replace('gpu:', '')} — ${name}`);
  }

  // The page saves itself (ADR 0062): there is no Save button, a select commits on change,
  // and a typed field commits when it is left (or on Enter). The choices: CPU, 2 threads,
  // German, 3 fixed speakers, max 12.
  await expect(page.getByRole('button', { name: 'Save' })).toHaveCount(0);
  await device.selectOption('cpu');
  await page.locator('#threads').fill('2');
  await page.locator('#max-speakers').fill('12');
  // Leaving a typed field commits it; focus moves on to the language field.
  await page.getByRole('combobox', { name: 'Language' }).click();
  await page.getByRole('option', { name: /german/ }).click();
  // The count exists only with fixed: the mode is stored by the count's own commit.
  await page.locator('#speakers').selectOption('fixed');
  await page.locator('#speaker-count').fill('3');
  await page.locator('#speaker-count').press('Enter');
  await expect(device).toHaveValue('cpu');
  await expect(page.locator('#threads')).toHaveValue('2');

  // The write reached the backend: a fresh GET /api/settings carries it, so the page of
  // a reconnected tab starts from the saved settings.
  await expect
    .poll(
      async () => (await page.evaluate(() => fetch('/api/settings').then((r) => r.json()))).threads,
    )
    .toBe(2);

  // Hold the queue, so nothing restarts mid-task when the backend is killed; the engine
  // of the restarted process must restore the pause and the stored settings.
  const pause = page.getByRole('switch', { name: 'Pause the queue' });
  await goTo(page, 'Queue');
  await pause.click();
  await expect(pause).toBeChecked();
  await uploadClips(page, [SETTINGS_CLIP]);
  await expect
    .poll(
      async () => (await page.evaluate(() => fetch('/api/settings').then((r) => r.json()))).device,
    )
    .toBe('cpu');

  const { pid, port, dataDir } = loadState();
  await stopBackend(pid, port, dataDir);
  await expect(page.getByText('Reconnecting…')).toBeVisible({ timeout: 30_000 });

  const restarted = await startBackend({ port, dataDir });
  try {
    await expect(page.getByText('Live', { exact: true })).toBeVisible({ timeout: 60_000 });
    await expect(pause).toBeChecked();
    // The restart kept the settings: the page shows them and the upload form starts with them.
    await goTo(page, 'Settings');
    await expect(device).toHaveValue('cpu');
    await expect(page.locator('#threads')).toHaveValue('2');
    await expect(page.getByRole('combobox', { name: 'Language' })).toHaveValue('german');
    await goTo(page, 'Queue');
    // The upload options live in a disclosure below the drop zone (item 59).
    await page.getByRole('button', { name: 'Upload options' }).click();
    await expect(page.getByRole('combobox', { name: 'Language' })).toHaveValue('german');
    await expect(page.locator('#speakers')).toHaveValue('fixed');
    await page.getByRole('button', { name: /advanced/i }).click();
    await expect(page.locator('#max-speakers')).toHaveValue('12');

    // With the queue resumed, the queued clip runs — the same settings the engine reads
    // when it starts it.
    await pause.click();
    await expect(pause).not.toBeChecked();
    await expect(page.getByText(`${SETTINGS_CLIP} transcribed`)).toBeVisible({ timeout: 240_000 });
    // The run restored nothing later: the stored device is still the chosen CPU.
    await expect
      .poll(
        async () =>
          (await page.evaluate(() => fetch('/api/settings').then((r) => r.json()))).device,
      )
      .toBe('cpu');

    // Restore default settings (ADR 0062): the action asks first, then the server's defaults
    // replace every field. The reset also clears the CPU-fallback notice (there is none here)
    // and leaves the shared backend on the defaults for the later specs.
    await goTo(page, 'Settings');
    await page.getByRole('button', { name: 'Restore default settings' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toContainText('Restore default settings?');
    await dialog.getByRole('button', { name: 'Restore' }).click();
    await expect(device).toHaveValue('auto');
    await expect(page.locator('#threads')).toHaveValue('');
    await expect(page.getByRole('combobox', { name: 'Language' })).toHaveValue('auto-detect');
    await expect(page.locator('#speakers')).toHaveValue('auto');
    await expect(page.locator('#max-speakers')).toHaveValue('8');
    await expect
      .poll(
        async () =>
          (await page.evaluate(() => fetch('/api/settings').then((r) => r.json()))).device,
      )
      .toBe('auto');
  } finally {
    // Stop the restarted backend (the global teardown only knows the original pid), then
    // hand a fresh shared backend on the same socket and data dir to the later specs,
    // as 13-restart does.
    await stopBackend(restarted.pid, port, dataDir);
    const fresh = await startBackend({ port, dataDir });
    saveState({ pid: fresh.pid });
  }
});
