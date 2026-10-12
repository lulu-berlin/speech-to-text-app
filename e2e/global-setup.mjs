// The suite's backend lifecycle (roadmap item 21): one real backend on a fresh temporary
// STT_DATA_DIR for the whole run. globalSetup runs before the tests; the returned
// function is the teardown, which Playwright calls after the run (it also runs when the
// suite fails, so a stray backend cannot survive a broken run).
import { copyFileSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  BROKEN2_CLIP,
  BROKEN_CLIP,
  FIXTURE_CLIPS,
  MERGE_CLIP,
  MERGE_SOURCE_IDS,
  checkPreconditions,
  choosePort,
  librispeechDir,
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
    { args: ['-stream_loop', '11'], file: FIXTURE_CLIPS[6] }, // the sample 12 times (~132 s)
    { args: ['-stream_loop', '19'], file: FIXTURE_CLIPS[8] }, // the sample 20 times (~220 s)
    { args: ['-ss', '6', '-t', '5'], file: FIXTURE_CLIPS[10] }, // 6-11s
    { args: ['-t', '3'], file: FIXTURE_CLIPS[11] }, // 0-3s, item 12
    { args: ['-ss', '7', '-t', '4'], file: FIXTURE_CLIPS[12] }, // 7-11s, item 12
    { args: ['-t', '7'], file: FIXTURE_CLIPS[13] }, // 0-7s, item 13
    { args: ['-ss', '4', '-t', '7'], file: FIXTURE_CLIPS[14] }, // 4-11s, item 13
    { args: ['-stream_loop', '7'], file: FIXTURE_CLIPS[16] }, // ~77s, screenshots
    { args: ['-t', '4'], file: FIXTURE_CLIPS[17] }, // 0-4s, screenshots' upload-in-flight
    { args: ['-t', '5'], file: FIXTURE_CLIPS[18] }, // 0-5s, screenshots' second running seed
    { args: ['-stream_loop', '8'], file: FIXTURE_CLIPS[19] }, // ~88s, the header/nav e2e (item 31)
    { args: ['-ss', '5', '-t', '2'], file: FIXTURE_CLIPS[20] }, // 5-7s, item 14's contract check
    { args: ['-ss', '1', '-t', '7'], file: FIXTURE_CLIPS[21] }, // 1-8s, the settings e2e (item 32)
    { args: ['-stream_loop', '13'], file: FIXTURE_CLIPS[22] }, // ~154s, item 36's follow-mode e2e
    { args: ['-ss', '3', '-t', '2'], file: FIXTURE_CLIPS[23] }, // 3-5s, item 37's completed player clip
    { args: ['-stream_loop', '5'], file: FIXTURE_CLIPS[24] }, // ~55s, item 37's background transcription
    { args: ['-stream_loop', '6'], file: FIXTURE_CLIPS[25] }, // ~66s, item 40's atomic-transitions race clip
    { args: ['-ss', '6', '-t', '3'], file: FIXTURE_CLIPS[26] }, // 6-9s, item 40's queue-is-live follow-up
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
  // The two-speaker recording: the three committed LibriSpeech clips back to back.
  execFileSync('ffmpeg', [
    '-y',
    '-loglevel',
    'error',
    ...MERGE_SOURCE_IDS.flatMap((id) => ['-i', join(librispeechDir, `${id}.flac`)]),
    '-filter_complex',
    '[0:a][1:a][2:a]concat=n=3:v=0:a=1',
    join(fixturesDir, MERGE_CLIP),
  ]);
  // A .wav whose bytes are not audio: it passes the upload's extension check and fails
  // when the engine decodes it (CHUNKING).
  writeFileSync(
    join(fixturesDir, BROKEN_CLIP),
    "not audio — the e2e suite's undecodable fixture\n".repeat(256),
  );
  writeFileSync(
    join(fixturesDir, BROKEN2_CLIP),
    "also not audio — the history test's own undecodable fixture\n".repeat(256),
  );
  const port = await choosePort();
  saveState({ port, dataDir, fixturesDir });
  const { pid } = await startBackend({ port, dataDir });
  saveState({ pid });
  console.log(`e2e: backend up on http://127.0.0.1:${port} (data dir ${dataDir})`);

  return async () => {
    const state = loadState();
    await stopBackend(state.pid, state.port, state.dataDir);
    rmSync(dataDir, { recursive: true, force: true });
    rmSync(fixturesDir, { recursive: true, force: true });
    console.log('e2e: backend stopped, data dir and fixtures removed');
  };
}
