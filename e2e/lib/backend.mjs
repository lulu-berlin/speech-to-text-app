// Backend lifecycle for the e2e suite (roadmap item 21). The whole suite runs against
// one real backend on a fresh temporary STT_DATA_DIR; the shared state (port, data dir,
// backend pid) lives in .e2e-state.json so global setup, the tests and the teardown all
// see the same process.
//
// The backend runs as `gradle --no-daemon run`. Even so, gradle 8.14 forks a single-use
// daemon JVM that actually launches the app's JVM, and that daemon is outside the group
// of the gradle launcher pid the harness holds — so stops find and kill the app JVM by
// its arguments (appJvmPid), not by the group alone.
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const e2eRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
export const repoRoot = join(e2eRoot, '..');
export const backendDir = join(repoRoot, 'backend');
export const frontendDist = join(repoRoot, 'frontend', 'dist');

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
  'jfk-k.wav', // the sample looped 7x, ~77s
  'jfk-l.wav', // 0-4s
  'jfk-m.wav', // 0-5s
  'jfk-n.wav', // the sample looped 8x, ~88s
  'jfk-o.wav', // 5-7s, the contract check (item 16)
  'jfk-p.wav', // 1-8s, item 32's settings e2e
  'jfk-follow.wav', // the sample looped 14x, ~154s (item 36)
  'jfk-q.wav', // 3-5s, item 37's completed player clip
  'jfk-r.wav', // the sample looped 5x, ~55s, item 37's background transcription
  'jfk-s.wav', // the sample looped 9x, ~110s, item 40's atomic-transitions race clip
  'jfk-u.wav', // 6-9s, item 40's queue-is-live follow-up
  'jfk-v.wav', // the sample looped 11x, ~121s, item 40's pause-queued running clip
  'jfk-w.wav', // 2-6s, item 40's pause-queued clip
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
export const TRANSCRIBING_BACKGROUND_CLIP = FIXTURE_CLIPS[24]; // the sample looped 5x, ~55s

// Item 40's atomic-transition journey (ADR 0046): a long clip for the rapid
// pause/resume/cancel race, and a short trim whose completion proves the queue kept
// working. Unique bytes: jfk-r (5x), jfk-k (7x), jfk-n (8x), jfk-long (12x) and
// jfk-follow (14x) are other tests' loops, and 6-9s is no existing trim.
export const ATOMIC_RACE_CLIP = FIXTURE_CLIPS[25]; // the sample looped 9x, ~110s
export const ATOMIC_NEXT_CLIP = FIXTURE_CLIPS[26]; // 6-9s
// Item 40's pause-a-queued-upload journey (the review): a long clip that keeps the engine
// transcribing, and a short trim queued behind it that the test pauses. Unique bytes: no
// other test loops the sample 11x, and 2-6s is no existing trim.
export const PAUSE_QUEUED_RUNNING_CLIP = FIXTURE_CLIPS[27]; // the sample looped 11x, ~121s
export const PAUSE_QUEUED_CLIP = FIXTURE_CLIPS[28]; // 2-6s

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

// The app JVM `gradle run` actually runs lives under the single-use daemon gradle forks
// (its launcher process is already gone by the time a stop happens), so a group kill of
// the launcher pid can miss it. The JVM is identifiable by its own args instead: every
// run passes the same class name and the data directory.
function appJvmPid(dataDir) {
  // No `ps` binary in this environment (it is a bash builtin), so read /proc directly.
  const needles = ['stt.MainKt', dataDir];
  for (const entry of readdirSync('/proc')) {
    if (!/^\d+$/.test(entry)) continue;
    try {
      const cmdline = readFileSync(`/proc/${entry}/cmdline`, 'utf8').replaceAll('\0', ' ');
      if (needles.every((n) => cmdline.includes(n))) return Number(entry);
    } catch {
      // the process exited while we listed it
    }
  }
  return null;
}

// The ancestors of the app JVM — its gradle daemon and the launcher JVM — started out
// in this harness's process group but are long reparented by the time a stop happens,
// so a group kill misses them. Walk the live ppid chain from the app JVM up to init.
function jvmAncestors(appPid) {
  const chain = [];
  let pid = appPid;
  for (let guard = 0; pid > 1 && guard < 16; guard++) {
    chain.push(pid);
    try {
      const stat = readFileSync(`/proc/${pid}/stat`, 'utf8');
      const rightmostParen = stat.lastIndexOf(')');
      const fields = stat.slice(rightmostParen + 2).split(/\s+/);
      pid = Number(fields[1]);
    } catch {
      break; // exited while walking
    }
  }
  return chain;
}

// Stops a backend running on [port]. `gradle run` launches through two JVMs — a
// launcher and a single-use daemon — that are outside this harness's process group by
// now; find the app JVM by its args and SIGKILL the whole chain, so nothing survives to
// hold the port or linger.
export async function stopBackend(pid, port, dataDir) {
  const jvm = appJvmPid(dataDir);
  if (jvm !== null) {
    for (const chainPid of jvmAncestors(jvm)) {
      try {
        process.kill(chainPid, 'SIGTERM');
      } catch {
        // already gone
      }
    }
    try {
      process.kill(jvm, 'SIGKILL');
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
export async function startBackend({ port, dataDir }) {
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
        STT_DATA_DIR: dataDir,
        STT_WHISPER_MODEL: process.env.TEST_WHISPER_TINY_EN_MODEL,
        STT_PORT: String(port),
      },
      stdio: ['ignore', 'inherit', 'inherit'],
    });
    try {
      await waitForHealth(port);
    } catch (error) {
      killGroup(child.pid);
      throw error;
    }
    return { pid: child.pid };
  }
  const child = spawn('gradle', ['--no-daemon', 'run', '--console=plain'], {
    cwd: backendDir,
    detached: true, // own session + process group: a group kill reaches gradle and the app JVM
    env: {
      ...process.env,
      STT_DATA_DIR: dataDir,
      STT_STATIC_DIR: frontendDist,
      STT_WHISPER_MODEL: process.env.TEST_WHISPER_TINY_EN_MODEL,
      STT_PORT: String(port),
    },
    stdio: ['ignore', 'inherit', 'inherit'],
  });
  try {
    await waitForHealth(port);
  } catch (error) {
    killGroup(child.pid);
    throw error;
  }
  return { pid: child.pid };
}
