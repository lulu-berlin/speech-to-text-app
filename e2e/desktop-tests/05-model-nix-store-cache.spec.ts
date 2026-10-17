import { expect, test, type Page } from '@playwright/test';
import { existsSync, mkdtempSync, readdirSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import {
  jfkClip,
  launchDesktopApp,
  processes,
  required,
  transcribeAndOpenTranscript,
  userPaths,
} from './helpers/app';
import { startModelServer, writeFixturePins } from './helpers/model-server';

// Roadmap item 50 (ADR 0053), test item "First run with the pinned models already in the Nix
// store": the models are `fetchurl` fixed-output derivations, whose store path follows from the
// file name and sha256 a pin carries. So when the pins name the files the way fetchurl named them,
// the app finds the models the harness mounts from the host's /nix/store — and must use them
// instead of downloading. A pin whose file name has no store path behind it is downloaded as
// before. The fixture server serves every name, so a cache miss shows up as a request, not as a
// broken first run.
const WHISPER_IN_STORE = required('TEST_WHISPER_TINY_EN_MODEL');
const SPEAKER_IN_STORE = required('STT_SPEAKER_MODEL');
// fetchurl's name for a model: its store basename after the 32-character hash.
const WHISPER_NAME = basename(WHISPER_IN_STORE).replace(/^[0-9a-z]{32}-/, '');
const SPEAKER_NAME = basename(SPEAKER_IN_STORE).replace(/^[0-9a-z]{32}-/, '');
// The same whisper model under a name nothing in the store carries: it has to be downloaded.
const WHISPER_NOT_IN_STORE = 'ggml-tiny.en.not-in-store.bin';

// The STT_* model variables the running backend was started with, from its /proc environ.
function backendModels(): { whisper?: string; speaker?: string } {
  const [backend] = processes(/stt\.MainKt/);
  if (!backend) return {};
  const variables = Object.fromEntries(
    readFileSync(`/proc/${backend.pid}/environ`, 'utf8')
      .split('\0')
      .map((line) => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]),
  );
  return { whisper: variables.STT_WHISPER_MODEL, speaker: variables.STT_SPEAKER_MODEL };
}

function cachedFiles(models: string): string[] {
  return existsSync(models) ? readdirSync(models) : [];
}

// The download screen stays up after provisioning while the JVM starts, so a model taken from the
// store is visible as a row that is complete without having been downloaded.
async function expectCompleteRow(window: Page, file: string): Promise<void> {
  await expect(window.locator('#files li').filter({ hasText: file })).toContainText('100%');
}

test('a first run takes the pinned models from the Nix store and downloads only what is missing', async () => {
  test.skip(
    !WHISPER_IN_STORE.startsWith('/nix/store/') || !SPEAKER_IN_STORE.startsWith('/nix/store/'),
    'needs the pinned models in a Nix store: the container harness or the CI leg with Nix',
  );
  test.skip(
    process.env.STT_DESKTOP_PACKAGE === 'bundle',
    "the bundle binds its own store at /nix, so the host's store is hidden by design (ADR 0053)",
  );
  const server = await startModelServer([
    { name: WHISPER_NAME, path: WHISPER_IN_STORE },
    { name: SPEAKER_NAME, path: SPEAKER_IN_STORE },
    { name: WHISPER_NOT_IN_STORE, path: WHISPER_IN_STORE },
  ]);
  try {
    // Both models in the store: nothing is fetched, nothing is copied into the per-user cache, the
    // screen shows both complete, and the backend transcribes with the store files themselves.
    const home = mkdtempSync(join(tmpdir(), 'stt-desktop-store-cache-'));
    const pins = writeFixturePins(server.baseUrl, { whisper: WHISPER_NAME, speaker: SPEAKER_NAME });
    const bothInStore = await launchDesktopApp({
      home,
      models: 'download',
      env: { STT_MODELS_FILE: pins },
    });
    try {
      const window = await bothInStore.firstWindow();
      await expect(window).toHaveURL(/progress\.html$/);
      await expectCompleteRow(window, WHISPER_NAME);
      await expectCompleteRow(window, SPEAKER_NAME);
      await expect(window).toHaveURL(/^http:\/\/127\.0\.0\.1:\d+\/queue$/, { timeout: 120_000 });
      await expect(window.getByText('Live', { exact: true })).toBeVisible({ timeout: 120_000 });
      expect(server.requestCount()).toBe(0);
      expect(cachedFiles(userPaths(home).models)).toEqual([]);
      expect(backendModels()).toEqual({ whisper: WHISPER_IN_STORE, speaker: SPEAKER_IN_STORE });
      await transcribeAndOpenTranscript(window, jfkClip());
    } finally {
      await bothInStore.close();
    }

    // Only the speaker model in the store: the whisper model is downloaded into the cache (one
    // request), the speaker model still comes from the store.
    const secondHome = mkdtempSync(join(tmpdir(), 'stt-desktop-store-cache-'));
    const mixedPins = writeFixturePins(server.baseUrl, {
      whisper: WHISPER_NOT_IN_STORE,
      speaker: SPEAKER_NAME,
    });
    const speakerInStore = await launchDesktopApp({
      home: secondHome,
      models: 'download',
      env: { STT_MODELS_FILE: mixedPins },
    });
    try {
      const window = await speakerInStore.firstWindow();
      await expect(window).toHaveURL(/^http:\/\/127\.0\.0\.1:\d+\/queue$/, { timeout: 300_000 });
      await expect(window.getByText('Live', { exact: true })).toBeVisible({ timeout: 120_000 });
      const models = userPaths(secondHome).models;
      expect(server.requestCount()).toBe(1);
      expect(cachedFiles(models)).toEqual([WHISPER_NOT_IN_STORE]);
      expect(backendModels()).toEqual({
        whisper: join(models, WHISPER_NOT_IN_STORE),
        speaker: SPEAKER_IN_STORE,
      });
    } finally {
      await speakerInStore.close();
    }
  } finally {
    await server.close();
  }
});
