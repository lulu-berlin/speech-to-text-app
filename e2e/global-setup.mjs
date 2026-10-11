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
  // The journey's clips: the committed LibriSpeech sample (FIXTURE_CLIPS[0]) plus two
  // distinct trims of it. The backend rejects duplicate audio by content sha256, so the
  // queued tasks need different bytes; the trims are still real speech for the tiny
  // model.
  const [clipA, clipB, clipC] = FIXTURE_CLIPS;
  copyFileSync(process.env.TEST_JFK_WAV, join(fixturesDir, clipA));
  execFileSync('ffmpeg', [
    '-y',
    '-loglevel',
    'error',
    '-i',
    process.env.TEST_JFK_WAV,
    '-t',
    '6',
    join(fixturesDir, clipB),
  ]);
  execFileSync('ffmpeg', [
    '-y',
    '-loglevel',
    'error',
    '-ss',
    '3',
    '-t',
    '7',
    '-i',
    process.env.TEST_JFK_WAV,
    join(fixturesDir, clipC),
  ]);
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
