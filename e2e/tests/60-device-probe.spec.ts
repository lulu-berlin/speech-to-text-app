import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, join } from 'node:path';
import { expect, test } from './helpers/test';
import { removeWithRetry } from '../lib/cleanup.ts';
import {
  appModeDirs,
  appUrl,
  choosePort,
  loadState,
  refreshStoredSession,
  registerBrowserFrontend,
  startBackend,
  stopBackend,
} from '../lib/backend.ts';
import { commandDirectory } from '../lib/executable.ts';
import { signIn } from './helpers/ui';

// Item 60 (ADR 0046): a failed device probe is reported as a failed probe, never as "no GPU". The shared
// suite backend runs with whisper-cli, so the failure half starts its own backend on a free port with the
// directory holding whisper-cli removed from PATH; the shared backend is untouched, and its Settings page
// is the "whisper-cli present" half: no probe notice there.
test('a backend without whisper-cli reports the failed probe on the Settings page', async ({
  page,
}) => {
  // PATH lookup, not `which`: Windows has no `which`, separates PATH with ';' and names the binary
  // whisper-cli.exe (roadmap item 79).
  const whisperCliDir = commandDirectory('whisper-cli');
  expect(whisperCliDir, 'whisper-cli is on PATH for the shared backend').not.toBeNull();
  // Windows spells the variable `Path`; overriding another spelling would hand the child two.
  const pathKey = Object.keys(process.env).find((key) => key.toUpperCase() === 'PATH') ?? 'PATH';
  const pathWithoutWhisperCli = (process.env[pathKey] ?? '')
    .split(delimiter)
    .filter((dir) => dir !== whisperCliDir)
    .join(delimiter);

  const port = await choosePort();
  const dataDir = mkdtempSync(join(tmpdir(), 'stt-e2e-probe-'));
  const origin = `http://127.0.0.1:${port}`;
  const { pid } = await startBackend({ port, dataDir, env: { [pathKey]: pathWithoutWhisperCli } });
  const dirs = appModeDirs(dataDir);
  try {
    // App mode (roadmap item 66): the frontend is registered and the browser enters through the
    // launch handshake, and this client sends the service's secret as the bearer. A dev-mode server
    // (`E2E_BACKEND_CMD`) has neither, so the page and this request go without a credential.
    const appMode = process.env.E2E_BACKEND_CMD === undefined;
    if (appMode) registerBrowserFrontend(dirs.configDir);
    const headers: Record<string, string> = {};
    if (appMode) {
      const { secret } = JSON.parse(readFileSync(join(dirs.configDir, 'config.json'), 'utf8')) as {
        secret: string;
      };
      headers.Authorization = `Bearer ${secret}`;
    }
    const settings = await (await page.request.get(`${origin}/api/settings`, { headers })).json();
    expect(settings.deviceProbeError).toBeTruthy();
    expect(settings.availableDevices).toEqual(['cpu', 'auto']);
    expect(settings.gpuNames).toEqual({});

    // The handshake only sets the session cookie; the page it lands on is the shared queue.
    await signIn(page, dirs);
    await page.goto(`${origin}/settings`);
    const notice = page.getByRole('alert');
    await expect(notice).toContainText('The device probe failed');
    await expect(notice).toContainText(settings.deviceProbeError);
    await expect(page.getByLabel('Device')).toBeVisible();
  } finally {
    await stopBackend(pid, port, dataDir);
    // Windows can still hold the data directory for a moment after the port closes (roadmap item 84:
    // the global teardown's EPERM in run #475); a leftover is reported, not thrown.
    if (!(await removeWithRetry(dataDir))) {
      console.warn(`e2e: the temporary directory ${dataDir} could not be removed`);
    }
  }

  // The shared backend ran with whisper-cli: a successful probe shows no notice. It is a different
  // service process, so the browser enters its session again first (roadmap item 66).
  await signIn(page);
  // The second instance's restart left the stored session dead with it (roadmap item 66).
  await refreshStoredSession(appModeDirs(loadState().dataDir));
  await page.goto(appUrl('/settings'));
  await expect(page.getByText('The device probe failed')).toHaveCount(0);
});
