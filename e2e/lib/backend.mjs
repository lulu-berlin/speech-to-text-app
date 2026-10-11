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
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const e2eRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
export const repoRoot = join(e2eRoot, '..');
export const backendDir = join(repoRoot, 'backend');
export const frontendDist = join(repoRoot, 'frontend', 'dist');

const STATE_FILE = join(e2eRoot, '.e2e-state.json');
const healthUrl = (port) => `http://127.0.0.1:${port}/api/health`;

// The journey's fixture clips, created by global-setup.mjs: from TEST_JFK_WAV the full
// sample, six distinct trims and the sample looped into two long clips; from item 08's
// committed LibriSpeech clips a two-speaker recording; and a .wav that cannot be decoded.
// Uploaded tasks are deduplicated by content sha256, and every test shares one backend per
// run — so each test that uploads needs its own bytes, or the second upload of the same
// content answers 409 duplicate-audio (the delete test re-uploads by design). QUEUE_CLIPS
// feeds the queue journey, the reorder test uses REORDER_CLIPS, the playback test
// PLAYBACK_CLIP (long enough that its transcript scrolls in a short viewport), the merge
// test MERGE_CLIP, the cancel/retry test CANCEL_CLIP (long enough to cancel while it
// transcribes) and BROKEN_CLIP (fails in chunking), and the delete test DELETE_CLIP,
// which it uploads twice on purpose.
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
];
export const QUEUE_CLIPS = FIXTURE_CLIPS.slice(0, 3);
export const REORDER_CLIPS = FIXTURE_CLIPS.slice(3, 6);
export const PLAYBACK_CLIP = FIXTURE_CLIPS[6];
export const MERGE_CLIP = FIXTURE_CLIPS[7];
export const CANCEL_CLIP = FIXTURE_CLIPS[8];
export const BROKEN_CLIP = FIXTURE_CLIPS[9];
export const DELETE_CLIP = FIXTURE_CLIPS[10];
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

export function checkPreconditions() {
  if (!process.env.TEST_WHISPER_TINY_EN_MODEL) {
    throw new Error(
      'TEST_WHISPER_TINY_EN_MODEL is unset — run inside the devenv shell (devenv.nix sets it)',
    );
  }
  if (!process.env.STT_SPEAKER_MODEL) {
    throw new Error(
      'STT_SPEAKER_MODEL is unset — run inside the devenv shell (devenv.nix sets it)',
    );
  }
  if (!process.env.TEST_JFK_WAV) {
    throw new Error('TEST_JFK_WAV is unset — run inside the devenv shell (devenv.nix sets it)');
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

// The files the backend keeps for a task outside the database: its cached joined FLAC
// ($STT_DATA_DIR/tmp/<id>.flac, built on the first /audio request) and its work
// directory. The restart test checks the same paths.
export function cachedAudioPath(dataDir, taskId) {
  return join(dataDir, 'tmp', `${taskId}.flac`);
}
export function workDirPath(dataDir, taskId) {
  return join(dataDir, 'work', taskId);
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
    const child = spawn(backendCmd, [], {
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
