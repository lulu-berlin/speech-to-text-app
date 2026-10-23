// Backend lifecycle for the e2e suite (roadmap item 21). The whole suite runs against
// one real backend on a fresh temporary STT_DATA_DIR; the shared state (port, data dir,
// backend pid) lives in .e2e-state.json so global setup, the tests and the teardown all
// see the same process.
//
// The backend runs as `gradle --no-daemon run`. Even so, gradle forks a single-use daemon
// JVM that actually launches the app's JVM, and that daemon is outside the group of the
// gradle launcher pid the harness holds — so stops find and kill the app JVM by its
// arguments (lib/processes.mjs), not by the group alone.
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createWriteStream, existsSync, readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { delimiter, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ancestors, findProcess, processes } from './processes.mjs';

export const e2eRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
export const repoRoot = join(e2eRoot, '..');
export const backendDir = join(repoRoot, 'backend');
export const frontendDist = join(repoRoot, 'frontend', 'dist');
export const BACKEND_LOG_FILE = join(e2eRoot, '.e2e-backend.log');

const STATE_FILE = join(e2eRoot, '.e2e-state.json');
const healthUrl = (port) => `http://127.0.0.1:${port}/api/health`;

// The journey's fixture clips, created by global-setup.mjs: from TEST_JFK_WAV the full
// sample, its distinct trims and loops (every clip has its own bytes); from item 08's
// committed LibriSpeech clips a two-speaker recording; and a .wav that cannot be decoded.
// Uploaded tasks are deduplicated by content sha256, and every test shares one backend per
// run — so each test that uploads needs its own bytes, or the second upload of the same
// content answers 409 duplicate-audio (the delete test re-uploads by design). QUEUE_CLIPS
// feeds the queue journey, the reorder test uses REORDER_CLIPS, the playback test
// PLAYBACK_CLIP (long enough that its transcript scrolls in a short viewport), the merge
// test MERGE_CLIP, the cancel/retry test CANCEL_CLIP (long enough to cancel while it
// transcribes) and BROKEN_CLIP (fails in chunking), the delete test DELETE_CLIP, which it
// uploads twice on purpose, and the contract check CONTRACT_CLIP (the history test leaves
// its completed clips behind, so 14 must not re-use them).
export const FIXTURE_CLIPS = [
  'jfk-a.wav',
  'jfk-b.wav',
  'jfk-c.wav',
  'jfk-d.wav',
  'jfk-e.wav',
  'jfk-f.wav',
  'jfk-long.wav',
  'librispeech-1089-1188-1089.wav',
  'jfk-cancel.wav',
  'broken.wav',
  'jfk-delete.wav',
  'jfk-g.wav', // 0-3s
  'jfk-h.wav', // 7-11s
  'jfk-i.wav', // 0-7s
  'jfk-j.wav', // 4-11s
  'broken-2.wav',
  'jfk-k.wav', // the sample 8 times, ~88s
  'jfk-l.wav', // 0-4s
  'jfk-m.wav', // 0-5s
  'jfk-n.wav', // the sample 9 times, ~99s
  'jfk-o.wav', // 5-7s, the contract check (item 16)
  'jfk-p.wav', // 1-8s, item 32's settings e2e
  'jfk-follow.wav', // the sample looped 14x, ~154s (item 36)
  'jfk-q.wav', // 3-5s, item 37's completed player clip
  'jfk-r.wav', // the sample 6 times, ~66s, item 37's background transcription
  'jfk-s.wav', // the sample 10 times, ~110s, item 40's atomic-transitions race clip
  'jfk-u.wav', // 6-9s, item 40's queue-is-live follow-up
  'jfk-v.wav', // the sample looped 11x, ~121s, item 40's pause-queued running clip
  'jfk-w.wav', // 2-6s, item 40's pause-queued clip
  'jfk-stability.wav', // 5-11s, item 42's queue-survives-a-failure clip
  'broken-stability.wav', // item 42's undecodable fixture
  'jfk-race.wav', // 8-11s, item 41's concurrent-upload race
  'jfk-x.wav', // 2-8s, item 59's favicon typing clip
  'jfk-y.wav', // 3-6s, item 59's upload-options clip
  'jfk-aa.wav', // the sample looped 16x, ~176s, item 59's running-row clip
  'jfk-ab.wav', // 1-3s, item 59's queued-row clip
  'jfk-ac.wav', // 9-11s, item 59's history-row clip
  'broken-59.wav', // item 59's history-row retry fixture
  'jfk-ad.wav', // the sample looped 4x, ~44s, item 59's row-controls clip
  'jfk-ae.wav', // 7-9s, item 59's row-controls second clip
  'jfk-af.wav', // the sample looped 3x, ~33s, item 59's single-bar row clip
  'jfk-ag.wav', // the sample looped 13x, ~143s, item 59's elapsed-time row clip
  'jfk-ah.wav', // the sample looped 5x, ~55s, item 59's phase-color row clip
  'broken-59-status.wav', // item 59's status-page delete fixture
  'jfk-ai.wav', // the sample looped 17x, ~187s, item 59's header-typing clip
  'jfk-metal.wav', // the sample twice (~22s), item 48's Metal app e2e
];
export const QUEUE_CLIPS = FIXTURE_CLIPS.slice(0, 3);
export const REORDER_CLIPS = FIXTURE_CLIPS.slice(3, 6);
export const PLAYBACK_CLIP = FIXTURE_CLIPS[6];
export const MERGE_CLIP = FIXTURE_CLIPS[7];
export const CANCEL_CLIP = FIXTURE_CLIPS[8];
export const BROKEN_CLIP = FIXTURE_CLIPS[9];
export const DELETE_CLIP = FIXTURE_CLIPS[10];
// The contract check's own trim: the history test leaves its completed clips in the
// shared backend, so re-using HISTORY_CLIPS would answer 409 on the first upload.
export const CONTRACT_CLIP = FIXTURE_CLIPS[20];
// The history and restart tests' own trims (unique bytes, so they still upload when the
// shared run's clips are finished tasks) — four trims partition the sample, two per
// test, so 12's uploads cannot be 13's already-finished tasks.
export const HISTORY_CLIPS = [FIXTURE_CLIPS[11], FIXTURE_CLIPS[12]]; // 0-3s, 7-11s
export const RESTART_CLIPS = [FIXTURE_CLIPS[13], FIXTURE_CLIPS[14]]; // 0-7s, 4-11s
// The history test's own undecodable fixture — the cancel test's broken.wav is already
// a FAILED task by the time this file runs, so a second upload would be 409.
export const BROKEN2_CLIP = FIXTURE_CLIPS[15];
// The screenshot test's own long seed (unique bytes; it cancels the task mid-run, so
// the queue is left unpaused and the clip never reaches COMPLETED).
export const SHOT_CLIP = FIXTURE_CLIPS[16];
export const SHOT_UPLOAD_CLIP = FIXTURE_CLIPS[17];
// The header/navigation test's own long clip (unique bytes; looped, so its transcript
// scrolls in a short viewport — item 31).
export const HEADER_CLIP = FIXTURE_CLIPS[19];
// The settings e2e's own trim (unique bytes; 1-8s, so it runs quickly once the queue
// reaches it — item 32, ADR 0046).
export const SETTINGS_CLIP = FIXTURE_CLIPS[21];
// Item 36's follow-mode journey's own long clip (unique bytes — the playback test's
// jfk-long.wav is already a finished task by the time this file runs, and the backend
// deduplicates by content sha256, so re-uploading it would answer 409).
export const FOLLOW_CLIP = FIXTURE_CLIPS[22];
// Item 37's audio-while-transcribing journey (ADR 0046): a short clip that completes
// quickly, and a long loop that keeps the engine transcribing while the completed one's
// player is checked. Unique bytes: FOLLOW_CLIP is item 36's loop, and the other trims
// are other tests' clips, so these cannot answer 409.
export const COMPLETED_PLAYER_CLIP = FIXTURE_CLIPS[23]; // 3-5s
export const TRANSCRIBING_BACKGROUND_CLIP = FIXTURE_CLIPS[24]; // the sample 6 times, ~66s

// Item 40's atomic-transition journey (ADR 0046): a long clip for the rapid
// pause/resume/cancel race, and a short trim whose completion proves the queue kept
// working. Unique bytes: jfk-r (6x), jfk-k (8x), jfk-n (9x), jfk-long (12x) and
// jfk-follow (14x) are other tests' loops, and 6-9s is no existing trim.
export const ATOMIC_RACE_CLIP = FIXTURE_CLIPS[25]; // the sample 10 times, ~110s
export const ATOMIC_NEXT_CLIP = FIXTURE_CLIPS[26]; // 6-9s
// Item 40's pause-a-queued-upload journey (the review): a long clip that keeps the engine
// transcribing, and a short trim queued behind it that the test pauses. Unique bytes: no
// other test loops the sample 11x, and 2-6s is no existing trim.
export const PAUSE_QUEUED_RUNNING_CLIP = FIXTURE_CLIPS[27]; // the sample looped 11x, ~121s
export const PAUSE_QUEUED_CLIP = FIXTURE_CLIPS[28]; // 2-6s

// Item 42's queue-survives-a-failure journey (ADR 0046): the sample's 5-11s trim, which is no other
// test's clip, and its own undecodable fixture (a second upload of broken.wav or broken-2.wav would be
// 409 once those are FAILED tasks).
export const STABILITY_CLIP = FIXTURE_CLIPS[29];
export const STABILITY_BROKEN_CLIP = FIXTURE_CLIPS[30];

// Item 41's upload-hardening race (ADR 0046): the sample's 8-11s trim, which is no other test's clip.
// The spec uploads it twice at once and then once more through the UI, for the duplicate link.
export const UPLOAD_RACE_CLIP = FIXTURE_CLIPS[31];

// Item 59's favicon journey (ADR 0046): a short trim that starts and finishes quickly, so
// the page's favicon can be watched switching while it runs and back when the queue is
// idle; 2-8s is no other test's trim.
export const FAVICON_CLIP = FIXTURE_CLIPS[32];

// Item 59's upload-options journey: a short trim whose task exposes the batch options the
// queue sent with it; 3-6s is no other test's trim.
export const QUEUE_OPTIONS_CLIP = FIXTURE_CLIPS[33];

// Item 59's task-row journey: a long clip that stays RUNNING long enough to watch its
// phase line and percentage, and a short one queued behind it to pause.
export const RUNNING_ROW_CLIP = FIXTURE_CLIPS[34];
export const QUEUED_ROW_CLIP = FIXTURE_CLIPS[35];

// Item 59's history-row journey: a short clip that reaches History, and an undecodable
// file of its own to retry from there (the earlier broken fixtures are already FAILED
// tasks by then, so a second upload would answer 409).
export const HISTORY_ROW_CLIP = FIXTURE_CLIPS[36];
export const HISTORY_BROKEN_CLIP = FIXTURE_CLIPS[37];

// Item 59's row-controls journey: a long clip to leave RUNNING, and a short one behind it
// whose pause/resume changes the allowed commands.
export const ROW_CONTROLS_CLIP = FIXTURE_CLIPS[38];
export const ROW_CONTROLS_SECOND_CLIP = FIXTURE_CLIPS[39];

// Item 59's row-bars journey: a clip long enough to reach the diarizing phase while the
// test watches the row's progress bars; 3x is no other test's loop count.
export const BAR_ROW_CLIP = FIXTURE_CLIPS[40];

// Item 59's row-elapsed journey: a long clip that stays running long enough for its
// elapsed field to tick; 13x is no other test's loop count.
export const ELAPSED_ROW_CLIP = FIXTURE_CLIPS[41];

// Item 59's row-color journey: a clip whose chunking and transcribing phases are both
// observable on one row; 5x is no other test's loop count.
export const COLOR_ROW_CLIP = FIXTURE_CLIPS[42];

// Item 59's status-page journey: an undecodable file of its own, so the failed status
// page (and its Delete menu) can be exercised without colliding with the earlier
// broken fixtures' FAILED tasks.
export const STATUS_BROKEN_CLIP = FIXTURE_CLIPS[43];

// Item 59's header-typing journey: a long clip that keeps a task RUNNING while the header
// icon is watched, then cancelled so it does not hold up the later specs.
export const HEADER_TYPING_CLIP = FIXTURE_CLIPS[44];

// Item 48's app-level Metal e2e: a unique clip (the sample twice) whose transcript must
// carry the JFK quote while the task runs through MTL0 on the macOS runner.
export const METAL_CLIP = FIXTURE_CLIPS[45];

// Item 09's two-speaker fixture (LibriSpeechDiarizationSpec): speakers 1089, 1188, 1089.
export const MERGE_SOURCE_IDS = ['1089-134686-0002', '1188-133604-0025', '1089-134691-0023'];
export const librispeechDir = join(backendDir, 'src', 'test', 'resources', 'librispeech');

export function loadState() {
  if (!existsSync(STATE_FILE)) {
    throw new Error(
      `no e2e state at ${STATE_FILE} — the suite must run through globalSetup (pnpm --dir e2e test)`,
    );
  }
  return JSON.parse(readFileSync(STATE_FILE, 'utf8'));
}

export function saveState(patch) {
  const state = existsSync(STATE_FILE) ? JSON.parse(readFileSync(STATE_FILE, 'utf8')) : {};
  writeFileSync(STATE_FILE, JSON.stringify({ ...state, ...patch }));
}

// The app's origin for this run; the tests reach the backend through it (the app itself
// talks to same-origin /api, so no port ever appears in frontend code).
export function appUrl(path = '/') {
  return `http://127.0.0.1:${loadState().port}${path}`;
}

// Without Nix, `./gradlew e2e` sets all of these (roadmap item 33).
export function checkPreconditions() {
  const setBy = 'run inside the devenv shell (devenv.nix sets it) or use ./gradlew e2e';
  if (!process.env.TEST_WHISPER_TINY_EN_MODEL) {
    throw new Error(`TEST_WHISPER_TINY_EN_MODEL is unset — ${setBy}`);
  }
  if (!process.env.STT_SPEAKER_MODEL) {
    throw new Error(`STT_SPEAKER_MODEL is unset — ${setBy}`);
  }
  if (!process.env.TEST_JFK_WAV) {
    throw new Error(`TEST_JFK_WAV is unset — ${setBy}`);
  }
  if (!process.env.E2E_BACKEND_CMD && !existsSync(join(frontendDist, 'index.html'))) {
    throw new Error(
      `no built frontend at ${frontendDist} — build it first: pnpm --dir frontend install && pnpm --dir frontend build`,
    );
  }
}

export async function choosePort() {
  return await new Promise((resolve, reject) => {
    const probe = createServer();
    probe.unref();
    probe.once('error', reject);
    probe.listen(0, '127.0.0.1', () => {
      const address = probe.address();
      if (address !== null && typeof address === 'object') {
        probe.close(() => resolve(address.port));
      } else {
        reject(new Error('choosePort: probe server gave no port'));
      }
    });
  });
}

// The scratch the backend keeps for a task outside the database: one directory per data directory under
// the OS temp dir (`java.io.tmpdir/stt-<fingerprint of STT_DATA_DIR>`), one subdirectory per task. It holds
// the chunker's scratch, whisper's inputs and the clip decoded for diarization, and it is removed when the
// task ends — every task's audio lives in the database (item 44).
export function scratchDirPath(dataDir, taskId) {
  const fingerprint = createHash('sha256').update(dataDir).digest('hex').slice(0, 16);
  return join(tmpdir(), `stt-${fingerprint}`, String(taskId));
}

// Waits until /api/health answers (startup) or, with `down: true`, until it stops
// answering (shutdown, and the restart test's kill).
export async function waitForHealth(port, { down = false, timeoutMs = 600_000 } = {}) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    let up = false;
    try {
      const response = await fetch(healthUrl(port), { signal: AbortSignal.timeout(2_000) });
      up = response.ok;
    } catch {
      up = false;
    }
    if ((!down && up) || (down && !up)) return;
    if (Date.now() > deadline) {
      throw new Error(
        `backend on :${port} ${down ? 'still answering after' : 'did not answer within'} ${timeoutMs} ms`,
      );
    }
    const { promise, resolve } = Promise.withResolvers();
    setTimeout(resolve, 300);
    await promise;
  }
}

// Stops a backend running on [port]. `gradle run` launches through two JVMs — a
// launcher and a single-use daemon — that are outside this harness's process group by
// now; find the app JVM by its args and SIGKILL the whole chain, so nothing survives to
// hold the port or linger.
export async function stopBackend(pid, port, dataDir) {
  const rows = processes();
  const jvm = findProcess(rows, ['stt.MainKt', dataDir]);
  if (jvm !== null) {
    for (const chainPid of ancestors(rows, jvm.pid)) {
      try {
        process.kill(chainPid, 'SIGTERM');
      } catch {
        // already gone
      }
    }
    try {
      process.kill(jvm.pid, 'SIGKILL');
    } catch {
      // already gone
    }
  }
  killGroup(pid);
  await waitForHealth(port, { down: true, timeoutMs: 60_000 });
}

export function killGroup(pid) {
  try {
    process.kill(-pid, 'SIGTERM');
  } catch {
    // already gone
  }
}

// Spawns the backend: a fresh STT_DATA_DIR (mkdtemp), the built frontend, and the tiny
// whisper model — the suite transcribes with it (decided 2026-10-09), overriding the
// production STT_WHISPER_MODEL with the devenv's TEST_WHISPER_TINY_EN_MODEL. `dataDir`
// is accepted so the restart test can bring the same data back up on the same port.
//
// By default the backend is `gradle --no-daemon run` in backend/. Item 16's acceptance
// boots the packaged app instead: set E2E_BACKEND_CMD to the wrapper path (e.g.
// ./result/bin/stt-app, resolved against the repo root) and the suite drives that
// binary. Its wrapper serves the bundled frontend and defaults the models, but a
// user-set STT_WHISPER_MODEL (the tiny model) and STT_DATA_DIR still win, so the
// journey is identical.
//
// `env` overrides the child's environment before the fixed data dir, port and models — item 60's
// probe-failure spec strips whisper-cli from PATH for its own backend (a second one on a free port;
// the shared backend is untouched).
/** The Gradle to run the backend with: PATH's gradle when present, else the repository wrapper. */
function backendGradle() {
  const hasGradle = (process.env.PATH ?? '')
    .split(delimiter)
    .some((dir) => existsSync(join(dir, 'gradle')));
  return hasGradle
    ? { command: 'gradle', args: ['--no-daemon', 'run', '--console=plain'], cwd: backendDir }
    : {
        command: join(repoRoot, 'gradlew'),
        args: ['-p', backendDir, '--no-daemon', 'run', '--console=plain'],
        cwd: repoRoot,
      };
}

/**
 * Mirrors the backend's stdout/stderr to the console and appends them to [BACKEND_LOG_FILE], so a
 * CI step can assert which whisper backend a task used (roadmap item 48).
 */
function captureBackendOutput(child) {
  const log = createWriteStream(BACKEND_LOG_FILE, { flags: 'a' });
  child.stdout?.on('data', (chunk) => {
    process.stdout.write(chunk);
    log.write(chunk);
  });
  child.stderr?.on('data', (chunk) => {
    process.stderr.write(chunk);
    log.write(chunk);
  });
  child.once('exit', () => log.end());
}

export async function startBackend({ port, dataDir, env = {} }) {
  checkPreconditions();
  const backendCmd = process.env.E2E_BACKEND_CMD;
  if (backendCmd) {
    // A command line is allowed (e.g. './gradlew run'); a bare path (item 16's
    // ./result/bin/stt-app) still works — the split is a no-op without spaces.
    const [cmd, ...args] = backendCmd.split(/\s+/).filter(Boolean);
    const child = spawn(cmd, args, {
      cwd: repoRoot,
      detached: true, // own session + process group: a group kill reaches the wrapper and the app JVM
      env: {
        ...process.env,
        ...env,
        STT_DATA_DIR: dataDir,
        STT_WHISPER_MODEL: process.env.TEST_WHISPER_TINY_EN_MODEL,
        STT_PORT: String(port),
        // The command may be the packaged wrapper (which sets its own static dir) or a bare
        // backend (`java -cp …`, `./gradlew run`), which serves 404 at `/` without this.
        STT_STATIC_DIR: process.env.STT_STATIC_DIR ?? frontendDist,
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    captureBackendOutput(child);
    try {
      await waitForHealth(port);
    } catch (error) {
      killGroup(child.pid);
      throw error;
    }
    return { pid: child.pid };
  }
  const gradle = backendGradle();
  const child = spawn(gradle.command, gradle.args, {
    cwd: gradle.cwd,
    detached: true, // own session + process group: a group kill reaches gradle and the app JVM
    env: {
      ...process.env,
      ...env,
      STT_DATA_DIR: dataDir,
      STT_STATIC_DIR: frontendDist,
      STT_WHISPER_MODEL: process.env.TEST_WHISPER_TINY_EN_MODEL,
      STT_PORT: String(port),
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  captureBackendOutput(child);
  try {
    await waitForHealth(port);
  } catch (error) {
    killGroup(child.pid);
    throw error;
  }
  return { pid: child.pid };
}
