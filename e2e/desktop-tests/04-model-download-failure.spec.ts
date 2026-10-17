import { expect, test } from '@playwright/test';
import { createHash } from 'node:crypto';
import { existsSync, mkdtempSync, readdirSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import { launchDesktopApp, required, userPaths } from './helpers/app';
import { startModelServer, writeFixturePins } from './helpers/model-server';

// Roadmap item 50 (ADR 0046), test item 8 (discovered while implementing item 3): a model download
// that fails its sha256 check must never reach the backend. The download screen reports it, the
// corrupt bytes are discarded (no cached file, no partial file), and Retry re-runs provisioning —
// here against a server that serves the good bytes on the second attempt. The pins carry the real
// sha256 of the two models the harness mounts, so the first response is rejected for the right
// reason: one flipped byte.
const WHISPER_FILE = basename(required('TEST_WHISPER_TINY_EN_MODEL'));
const SPEAKER_FILE = basename(required('STT_SPEAKER_MODEL'));

test('a download that fails verification is reported, leaves no partial file, and retries', async () => {
  const home = mkdtempSync(join(tmpdir(), 'stt-desktop-retry-home-'));
  const server = await startModelServer([
    { name: WHISPER_FILE, path: required('TEST_WHISPER_TINY_EN_MODEL'), corruptFirst: true },
    { name: SPEAKER_FILE, path: required('STT_SPEAKER_MODEL') },
  ]);
  const pins = writeFixturePins(server.baseUrl);
  const models = userPaths(home).models;
  const app = await launchDesktopApp({ home, models: 'download', env: { STT_MODELS_FILE: pins } });
  try {
    const window = await app.firstWindow();

    // The screen names the failure and offers the retry instead of starting a backend whose model
    // files are not there.
    await expect(window.getByText('The speech analysis models could not be prepared.')).toBeVisible(
      {
        timeout: 60_000,
      },
    );
    const retry = window.getByRole('button', { name: 'Retry' });
    await expect(retry).toBeVisible();

    // The rejected download is gone entirely: not cached, and no partial file beside it.
    expect(existsSync(join(models, WHISPER_FILE))).toBe(false);
    expect(partialFiles(models)).toEqual([]);

    // Retry: the server serves the good bytes now, so provisioning completes and the app starts.
    await retry.click();
    await expect(window).toHaveURL(/^http:\/\/127\.0\.0\.1:\d+\/queue$/, { timeout: 300_000 });

    // Both models are in the cache, verified against the repository's pins.
    expect(
      createHash('sha256')
        .update(readFileSync(join(models, WHISPER_FILE)))
        .digest('hex'),
    ).toBe(required('TEST_WHISPER_TINY_EN_SHA256'));
    expect(
      createHash('sha256')
        .update(readFileSync(join(models, SPEAKER_FILE)))
        .digest('hex'),
    ).toBe(required('TEST_SPEAKER_SHA256'));
    expect(partialFiles(models)).toEqual([]);
  } finally {
    await app.close();
    await server.close();
  }
});

// The `.part` files a download leaves while it streams; a failed one must never survive.
function partialFiles(dir: string): string[] {
  return existsSync(dir) ? readdirSync(dir).filter((name) => name.endsWith('.part')) : [];
}
