import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { expect, test } from '@playwright/test';
import { appUrl, choosePort, startBackend, stopBackend } from '../lib/backend.ts';

// Item 60 (ADR 0046): a failed device probe is reported as a failed probe, never as "no GPU". The shared
// suite backend runs with whisper-cli, so the failure half starts its own backend on a free port with the
// directory holding whisper-cli removed from PATH; the shared backend is untouched, and its Settings page
// is the "whisper-cli present" half: no probe notice there.
test('a backend without whisper-cli reports the failed probe on the Settings page', async ({
  page,
}) => {
  test.setTimeout(360_000);
  const whisperCliDir = dirname(
    execFileSync('which', ['whisper-cli'], { encoding: 'utf8' }).trim(),
  );
  const pathWithoutWhisperCli = (process.env.PATH ?? '')
    .split(':')
    .filter((dir) => dir !== whisperCliDir)
    .join(':');

  const port = await choosePort();
  const dataDir = mkdtempSync(join(tmpdir(), 'stt-e2e-probe-'));
  const origin = `http://127.0.0.1:${port}`;
  const { pid } = await startBackend({ port, dataDir, env: { PATH: pathWithoutWhisperCli } });
  try {
    const settings = await (await page.request.get(`${origin}/api/settings`)).json();
    expect(settings.deviceProbeError).toBeTruthy();
    expect(settings.availableDevices).toEqual(['cpu', 'auto']);
    expect(settings.gpuNames).toEqual({});

    await page.goto(`${origin}/settings`);
    const notice = page.getByRole('alert');
    await expect(notice).toContainText('The device probe failed');
    await expect(notice).toContainText(settings.deviceProbeError);
    await expect(page.getByLabel('Device')).toBeVisible();
  } finally {
    await stopBackend(pid, port, dataDir);
    rmSync(dataDir, { recursive: true, force: true });
  }

  // The shared backend ran with whisper-cli: a successful probe shows no notice.
  await page.goto(appUrl('/settings'));
  await expect(page.getByText('The device probe failed')).toHaveCount(0);
});
