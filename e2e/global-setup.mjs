// The suite's backend lifecycle (roadmap item 21): one real backend on a fresh temporary
// STT_DATA_DIR for the whole run. globalSetup runs before the tests; the returned
// function is the teardown, which Playwright calls after the run (it also runs when the
// suite fails, so a stray backend cannot survive a broken run).
import { copyFileSync, mkdtempSync, rmSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  FIXTURE_CLIPS,
  checkPreconditions,
  choosePort,
  loadState,
  saveState,
  startBackend,
  stopBackend,
} from './lib/backend.mjs';

export default async function globalSetup() {
  checkPreconditions();
  const dataDir = mkdtempSync(join(tmpdir(), 'stt-e2e-'));
  const fixturesDir = mkdtempSync(join(tmpdir(), 'stt-e2e-fixtures-'));
  // The journey's clips: the committed LibriSpeech sample plus distinct trims of it —
  // every fixture must have unique bytes (the backend deduplicates by content sha256 and
  // the tests share one backend). Short trims still transcribe as real speech.
  const recipes = [
    { args: [], file: FIXTURE_CLIPS[0] }, // the full sample
    { args: ['-t', '6'], file: FIXTURE_CLIPS[1] }, // 0-6s
    { args: ['-ss', '3', '-t', '7'], file: FIXTURE_CLIPS[2] }, // 3-10s
    { args: ['-t', '2'], file: FIXTURE_CLIPS[3] }, // 0-2s
    { args: ['-ss', '2', '-t', '2'], file: FIXTURE_CLIPS[4] }, // 2-4s
    { args: ['-ss', '4', '-t', '2'], file: FIXTURE_CLIPS[5] }, // 4-6s
  ];
  for (const { args, file } of recipes) {
    const target = join(fixturesDir, file);
    if (args.length === 0) {
      copyFileSync(process.env.TEST_JFK_WAV, target);
    } else {
      execFileSync('ffmpeg', [
        '-y',
        '-loglevel',
        'error',
        ...args,
        '-i',
        process.env.TEST_JFK_WAV,
        target,
      ]);
    }
  }
  const port = await choosePort();
  saveState({ port, dataDir, fixturesDir });
  const { pid } = await startBackend({ port, dataDir });
  saveState({ pid });
  console.log(`e2e: backend up on http://127.0.0.1:${port} (data dir ${dataDir})`);

  return async () => {
    const state = loadState();
    await stopBackend(state.pid, state.port);
    rmSync(dataDir, { recursive: true, force: true });
    rmSync(fixturesDir, { recursive: true, force: true });
    console.log('e2e: backend stopped, data dir and fixtures removed');
  };
}
