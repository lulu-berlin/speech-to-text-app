import { expect, test } from '@playwright/test';
import { existsSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import {
  jfkClip,
  launchDesktopApp,
  required,
  transcribeAndOpenTranscript,
  userPaths,
} from './helpers/app';
import { startModelServer, writeFixturePins } from './helpers/model-server';

// Roadmap item 50 (ADR 0046), test item 3: the first run on a clean machine provisions the
// per-user data directory and the model cache, and a restart reuses both. The two models are the
// ones the harness already mounts, served over loopback with the *real* sha256 of the repository's
// pins — the harness passes those two values (TEST_*_SHA256), so the production whisper pin
// (~570 MB) never enters a test, and the app is verified against versions.json rather than against
// a hash the test computed from the very file it serves.
const WHISPER_FILE = basename(required('TEST_WHISPER_TINY_EN_MODEL'));
const SPEAKER_FILE = basename(required('STT_SPEAKER_MODEL'));

test('the first run provisions the models and the data directory, and a restart reuses them', async () => {
  const home = mkdtempSync(join(tmpdir(), 'stt-desktop-first-run-'));
  // hold: the spec asserts the download screen while the model responses hang (see the helper).
  // The whisper file is served through one redirect hop, so the app's own redirect following —
  // which checks every hop against the host allowlist (ADR 0055) — is exercised too.
  const server = await startModelServer(
    [
      {
        name: WHISPER_FILE,
        path: required('TEST_WHISPER_TINY_EN_MODEL'),
        redirectFirst: true,
      },
      { name: SPEAKER_FILE, path: required('STT_SPEAKER_MODEL') },
    ],
    { hold: true },
  );
  const pins = writeFixturePins(server.baseUrl);
  const paths = userPaths(home);
  const clip = jfkClip();
  const launch = () =>
    launchDesktopApp({ home, models: 'download', env: { STT_MODELS_FILE: pins } });

  const firstRun = await launch();
  try {
    const window = await firstRun.firstWindow();
    // The download screen comes before the backend exists. Its model responses hang until
    // release(), so the screen is up while the app waits for them.
    await expect(window.getByText('Preparing the speech analysis models')).toBeVisible({
      timeout: 60_000,
    });
    await expect(window).toHaveURL(/progress\.html$/);

    server.release();

    // The same window then shows the UI the bundled backend serves on the loopback interface:
    // both models were downloaded, verified and handed to the backend.
    await expect(window).toHaveURL(/^http:\/\/127\.0\.0\.1:\d+\/queue$/, { timeout: 300_000 });
    await expect(window.getByText('Live', { exact: true })).toBeVisible({ timeout: 120_000 });

    // The per-user directories the app decided on (2026-10-14): the database in the data
    // directory, both verified files in the model cache.
    expect(existsSync(join(paths.data, 'stt.db'))).toBe(true);
    expect(existsSync(join(paths.models, WHISPER_FILE))).toBe(true);
    expect(existsSync(join(paths.models, SPEAKER_FILE))).toBe(true);

    // The downloaded models work: the shared upload → transcript journey.
    await transcribeAndOpenTranscript(window, clip);
  } finally {
    await firstRun.close();
  }

  // A restart with the same HOME: the models are already in the cache, so no model request
  // reaches the server — and the transcript of the first run is still there.
  const requestsAfterFirstRun = server.requestCount();
  const secondRun = await launch();
  try {
    const window = await secondRun.firstWindow();
    await expect(window).toHaveURL(/^http:\/\/127\.0\.0\.1:\d+\/queue$/, { timeout: 120_000 });
    await window.getByRole('navigation').getByRole('link', { name: 'History' }).click();
    await expect(window.getByRole('link', { name: 'jfk.wav' })).toBeVisible();
    expect(server.requestCount()).toBe(requestsAfterFirstRun);
  } finally {
    await secondRun.close();
    await server.close();
  }
});
