import { expect, test } from '@playwright/test';
import { rowOf } from '../tests/helpers/ui';
import { APP_PROCESSES, jfkClip, launchDesktopApp, survivors } from './helpers/app';

// Roadmap item 50 (ADR 0046), test item 2: quitting the app stops the backend and its processes —
// the JVM, whisper-cli and ffmpeg are the ones the app owns, and none of them may outlive it
// (item 26's shutdown semantics, driven from Electron). desktop/run-in-docker.ts runs this
// against the packaged app in a stock Ubuntu container, so the machine holds nothing else of
// ours. The shared launch setup is `helpers/app.ts`.
// backend.ts asks the backend to stop (SIGTERM) and SIGKILLs it after 10 s, so a clean quit is
// over well inside this window.
const STOP_GRACE_MS = 15_000;

test('quitting the app while a task runs leaves no backend, whisper or ffmpeg behind', async () => {
  const clip = jfkClip();
  const app = await launchDesktopApp();
  const window = await app.firstWindow();
  await expect(window.getByText('Live', { exact: true })).toBeVisible({ timeout: 120_000 });

  // A task makes the app own child processes — the JVM, and whisper-cli/ffmpeg once the engine
  // picks the task up. Quitting has to take them along.
  await window.setInputFiles('input[type="file"]', clip);
  await expect(rowOf(window, 'jfk.wav')).toBeVisible({ timeout: 60_000 });

  await app.close();

  expect(await survivors(APP_PROCESSES, STOP_GRACE_MS)).toEqual([]);
});
