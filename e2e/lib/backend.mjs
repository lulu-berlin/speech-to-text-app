// Backend lifecycle for the e2e suite (roadmap item 21). The whole suite runs against
// one real backend on a fresh temporary STT_DATA_DIR; the shared state (port, data dir,
// backend pid) lives in .e2e-state.json so global setup, the tests and the teardown all
// see the same process.
//
// The backend runs as `gradle --no-daemon run`: with the daemon, the app's JVM would be
// a child of the daemon (outside this process group), and killing this harness would
// orphan the server. --no-daemon keeps gradle and the app JVM under one session, so a
// group kill reaches both.
import { spawn } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const e2eRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
export const repoRoot = join(e2eRoot, '..');
export const backendDir = join(repoRoot, 'backend');
export const frontendDist = join(repoRoot, 'frontend', 'dist');

const STATE_FILE = join(e2eRoot, '.e2e-state.json');
const healthUrl = (port) => `http://127.0.0.1:${port}/api/health`;

// The journey's fixture clips (global-setup.mjs creates them from TEST_JFK_WAV: the
// full sample, five distinct trims, and the sample looped into a long clip). Uploaded
// tasks are deduplicated by content sha256, and every test shares one backend per run —
// so each test that uploads needs its own bytes, or the second upload of the same content
// answers 409 duplicate-audio (the delete test re-uploads by design). QUEUE_CLIPS feeds
// the queue journey, the reorder test uses REORDER_CLIPS, and the playback test uses
// PLAYBACK_CLIP — long enough that its transcript scrolls in a short viewport.
export const FIXTURE_CLIPS = [
  'jfk-a.wav',
  'jfk-b.wav',
  'jfk-c.wav',
  'jfk-d.wav',
  'jfk-e.wav',
  'jfk-f.wav',
  'jfk-long.wav',
];
export const QUEUE_CLIPS = FIXTURE_CLIPS.slice(0, 3);
export const REORDER_CLIPS = FIXTURE_CLIPS.slice(3, 6);
export const PLAYBACK_CLIP = FIXTURE_CLIPS[6];

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
  if (!existsSync(join(frontendDist, 'index.html'))) {
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
export async function startBackend({ port, dataDir }) {
  checkPreconditions();
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

export async function stopBackend(pid, port) {
  killGroup(pid);
  await waitForHealth(port, { down: true, timeoutMs: 60_000 });
}
