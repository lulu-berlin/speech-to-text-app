// Backend lifecycle for the e2e suite (roadmap item 21). The whole suite runs against
// one real backend on a fresh temporary STT_DATA_DIR; the shared state (port, data dir,
// backend pid) lives in .e2e-state.json so global setup, the tests and the teardown all
// see the same process.
//
// The backend runs as `gradle --no-daemon run`. Even so, gradle forks a single-use daemon
// JVM that actually launches the app's JVM, and that daemon is outside the group of the
// gradle launcher pid the harness holds — so stops find and kill the app JVM by its
// arguments (lib/processes.ts), not by the group alone.
import { execFileSync, spawn } from 'node:child_process';
import type { ChildProcess } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  createWriteStream,
  existsSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { get } from 'node:http';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { delimiter, dirname, join, posix, win32 } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ancestors, findProcess, processes } from './processes.ts';
import type { ProcessRow } from './processes.ts';
import { commandDirectory } from './executable.ts';
import { waitUntil } from './wait.ts';

export const e2eRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
export const repoRoot = join(e2eRoot, '..');
export const backendDir = join(repoRoot, 'backend');
export const frontendDist = join(repoRoot, 'frontend', 'dist');
export const BACKEND_LOG_FILE = join(e2eRoot, '.e2e-backend.log');

const STATE_FILE = join(e2eRoot, '.e2e-state.json');
const healthUrl = (port: number): string => `http://127.0.0.1:${port}/api/health`;

/** What `.e2e-state.json` holds for one run: global setup writes it, the tests and teardown read it. */
export interface E2eState {
  port: number;
  dataDir: string;
  fixturesDir: string;
  pid: number | undefined;
  /** The service's bootstrap directories (roadmap item 66): `stt-app` needs them to launch a frontend. */
  configDir?: string;
  runtimeDir?: string;
}

// The journey's fixture clips, created by global-setup.ts: from TEST_JFK_WAV the full
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
  'jfk-aa.wav', // the sample looped 7x, ~77s, item 59's running-row clip
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
  'jfk-aj.wav', // the sample looped 15x, ~165s, item 81's held-pause running clip
  'jfk-ak.wav', // 1-5s, item 81's held-pause queued clip
  'librispeech-1089-1089-1188.wav', // item 73's reassign journey: reader 1089 twice, then 1188
  'jfk-upload-wait.wav', // 4-9s, item 87's mid-upload outage clip (unique bytes)
] as const;
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
// Item 87's mid-upload outage clip: unique bytes, so the upload still reaches the service when
// the journey uploads it after the earlier specs.
export const UPLOAD_WAIT_CLIP = FIXTURE_CLIPS[49];
// The history and restart tests' own trims (unique bytes, so they still upload when the
// shared run's clips are finished tasks) — four trims partition the sample, two per
// test, so 12's uploads cannot be 13's already-finished tasks.
export const HISTORY_CLIPS: [string, string] = [FIXTURE_CLIPS[11], FIXTURE_CLIPS[12]]; // 0-3s, 7-11s
export const RESTART_CLIPS: [string, string] = [FIXTURE_CLIPS[13], FIXTURE_CLIPS[14]]; // 0-7s, 4-11s
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

// Item 81's held-pause journey: a long clip paused while its Pause response is held in the
// browser, and a short one queued behind it that must start once the first resumes and
// completes. Unique bytes: no other test loops the sample 15x, and 1-5s is no existing trim.
export const HELD_PAUSE_CLIP = FIXTURE_CLIPS[46];
export const HELD_PAUSE_NEXT_CLIP = FIXTURE_CLIPS[47];
// Item 73's amendment: reader 1089 speaks twice, so its two segments form one turn of two, then
// reader 1188 answers — a two-speaker recording with a turn a single segment can be moved out of.
export const REASSIGN_CLIP = 'librispeech-1089-1089-1188.wav';
export const REASSIGN_SOURCE_IDS = ['1089-134686-0002', '1089-134691-0023', '1188-133604-0025'];

// Item 09's two-speaker fixture (LibriSpeechDiarizationSpec): speakers 1089, 1188, 1089.
export const MERGE_SOURCE_IDS = ['1089-134686-0002', '1188-133604-0025', '1089-134691-0023'];
export const librispeechDir = join(backendDir, 'src', 'test', 'resources', 'librispeech');

export function loadState(): E2eState {
  if (!existsSync(STATE_FILE)) {
    throw new Error(
      `no e2e state at ${STATE_FILE} — the suite must run through globalSetup (pnpm --dir e2e test)`,
    );
  }
  return JSON.parse(readFileSync(STATE_FILE, 'utf8')) as E2eState;
}

export function saveState(patch: Partial<E2eState>): void {
  const state = existsSync(STATE_FILE) ? JSON.parse(readFileSync(STATE_FILE, 'utf8')) : {};
  writeFileSync(STATE_FILE, JSON.stringify({ ...state, ...patch }));
}

/**
 * Refuses a journey that would share this checkout with a run already in progress (roadmap item 84):
 * one `.e2e-state.json` per checkout means a second run reads the first's port, starts a backend
 * over it and kills the first's, and both then fail as a cascade with `ECONNREFUSED`. The state
 * names the backend's pid — a pid that still exists is a live run, one that is gone is a leftover
 * the run may overwrite.
 */
export function refuseLiveRun(
  statePath: string = STATE_FILE,
  alive: (pid: number) => boolean = processAlive,
): void {
  if (!existsSync(statePath)) return;
  let state: unknown;
  try {
    state = JSON.parse(readFileSync(statePath, 'utf8'));
  } catch {
    return; // a half-written or hand-edited file: this run overwrites it
  }
  if (typeof state !== 'object' || state === null || !('pid' in state)) return;
  const pid = state.pid;
  if (typeof pid !== 'number' || !alive(pid)) return;
  throw new Error(
    `another journey is running from this checkout (${statePath} names pid ${pid}); stop it ` +
      'and, if the process is a leftover, delete that file before starting another run',
  );
}

/** Whether [pid] is a process this user can see: a signal 0 probe answers without sending one. */
function processAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    // EPERM means the process exists and belongs to someone else, which a shared checkout's run is not.
    return (error as NodeJS.ErrnoException).code === 'EPERM';
  }
}

// The app's origin for this run; the tests reach the backend through it (the app itself
// talks to same-origin /api, so no port ever appears in frontend code).
export function appUrl(path = '/'): string {
  return `http://127.0.0.1:${loadState().port}${path}`;
}

// Without Nix, `./gradlew e2e` sets all of these (roadmap item 33). The built UI is not checked
// here: global-setup.ts builds it when its inputs changed since the last build (roadmap item 84).
export function checkPreconditions(): void {
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
}

export async function choosePort(): Promise<number> {
  const { promise, resolve, reject } = Promise.withResolvers<number>();
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
  return await promise;
}

// The scratch the backend keeps for a task outside the database: one directory per data directory under
// the OS temp dir (`java.io.tmpdir/stt-<fingerprint of STT_DATA_DIR>`), one subdirectory per task. It holds
// the chunker's scratch, whisper's inputs and the clip decoded for diarization, and it is removed when the
// task ends — every task's audio lives in the database (item 44).
export function scratchDirPath(dataDir: string, taskId: number | string): string {
  const fingerprint = createHash('sha256').update(dataDir).digest('hex').slice(0, 16);
  return join(tmpdir(), `stt-${fingerprint}`, String(taskId));
}

// Waits until /api/health answers (startup) or, with `down: true`, until it stops
// answering (shutdown, and the restart test's kill).
export async function waitForHealth(
  port: number,
  {
    down = false,
    timeoutMs = 600_000,
    signal,
    authorization,
  }: {
    down?: boolean;
    timeoutMs?: number;
    signal?: AbortSignal;
    // App mode (roadmap item 66) answers only a client that sends the per-install secret.
    authorization?: () => string | undefined;
  } = {},
): Promise<void> {
  // node:http, not fetch: Node 24's bundled undici calls `socket.setTypeOfService()` on every
  // request, and macOS can refuse it with an EINVAL thrown outside the fetch promise — a poll during
  // a backend restart then killed the Playwright worker (roadmap item 84, nodejs/undici#5544). One
  // connection per poll (`agent: false`), so no pooled socket outlives the backend it reached.
  const answering = (): Promise<boolean> => {
    const { promise, resolve } = Promise.withResolvers<boolean>();
    const header = authorization?.();
    const request = get(
      healthUrl(port),
      {
        agent: false,
        signal: AbortSignal.timeout(2_000),
        // App mode answers health only to the per-install credential; without it every poll gets 401.
        headers: header === undefined ? {} : { Authorization: header },
      },
      (response) => {
        const status = response.statusCode ?? 0;
        response.once('end', () => resolve(status >= 200 && status < 300));
        // A body cut off by a dying backend: `close` without `end`, or an `error`.
        response.once('error', () => resolve(false));
        response.once('close', () => resolve(false));
        response.resume();
      },
    );
    request.once('error', () => resolve(false));
    return promise;
  };
  const reached = await waitUntil(async () => (down ? !(await answering()) : await answering()), {
    timeoutMs,
    intervalMs: 300,
    signal,
  });
  // Aborted by the caller: there is no answer to wait for, and a timeout error would be a lie.
  if (!reached && signal?.aborted !== true) {
    throw new Error(
      `backend on :${port} ${down ? 'still answering after' : 'did not answer within'} ${timeoutMs} ms`,
    );
  }
}

// Stops a backend running on [port]. `gradle run` launches through two JVMs — a
// launcher and a single-use daemon — that are outside this harness's process group by
// now; find the app JVM by its args and SIGKILL the whole chain, so nothing survives to
// hold the port or linger. Windows has neither a POSIX group nor a catchable SIGTERM
// (roadmap item 79): there the same listing finds the app JVM and `taskkill /T /F` ends
// its tree, whisper-cli and ffmpeg included.
export async function stopBackend(
  pid: number | undefined,
  port: number,
  dataDir: string,
  {
    platform = process.platform,
    list = () => processes({ platform }),
    forceKill = taskkill,
    spawnTree = false,
  }: {
    platform?: NodeJS.Platform;
    list?: () => ProcessRow[];
    forceKill?: (pid: number) => Promise<boolean>;
    /** End the spawn's own tree before the app JVM's (a start failure still owns its spawn). */
    spawnTree?: boolean;
  } = {},
): Promise<void> {
  const rows = list();
  // App mode (roadmap item 66) passes its data directory in the environment, not argv, so the
  // argv match below cannot find its JVM; the service advertises that JVM's own pid in runtime.json.
  const advertised = advertisedAppPid(dataDir);
  const jvm = advertised === undefined ? findProcess(rows, ['stt.MainKt', dataDir]) : null;
  if (platform === 'win32') {
    // The app JVM's own tree is the narrower kill; the spawn's pid is the fallback when the listing
    // cannot see the JVM, and the target when the failure is still in the caller's hands.
    const target = spawnTree ? (pid ?? advertised) : (advertised ?? jvm?.pid ?? pid);
    if (target !== undefined) await forceKill(target);
  } else {
    if (advertised !== undefined) {
      try {
        process.kill(advertised, 'SIGKILL');
      } catch {
        // already gone
      }
    }
    if (jvm !== null) {
      for (const chainPid of ancestors(rows, jvm.pid)) {
        // The caller may have spawned this backend itself (a start failure, specs 13/17/60): the
        // chain then runs through this very process, which must survive its own stop.
        if (chainPid === process.pid) break;
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
  }
  await waitForHealth(port, { down: true, timeoutMs: 60_000 });
}

/** The app JVM pid an app-mode service advertises in its runtime directory, if it left one. */
function advertisedAppPid(dataDir: string): number | undefined {
  const file = join(appModeDirs(dataDir).runtimeDir, 'runtime.json');
  if (!existsSync(file)) return undefined;
  try {
    const { pid } = JSON.parse(readFileSync(file, 'utf8')) as { pid?: unknown };
    return typeof pid === 'number' ? pid : undefined;
  } catch {
    // a torn file is not worth failing the stop over
    return undefined;
  }
}

/** `taskkill`'s arguments: the pid and its whole tree, forcefully (Windows has no SIGTERM). */
export function taskkillArgs(pid: number): string[] {
  return ['/pid', String(pid), '/T', '/F'];
}

/**
 * Whether [command] starts only through a shell: Windows refuses to spawn a .bat or .cmd without one
 * since Node's 2024 security fix (EINVAL), and the Gradle-built backend there is `stt-backend.bat`.
 * The command runs unquoted under the shell, which is safe for the relative, space-free paths the
 * harness passes.
 */
export function needsShell(command: string, platform: NodeJS.Platform = process.platform): boolean {
  return platform === 'win32' && /\.(bat|cmd)$/i.test(command);
}

/** Ends [pid]'s tree with `taskkill`; false when the process was already gone. */
async function taskkill(pid: number): Promise<boolean> {
  try {
    execFileSync('taskkill', taskkillArgs(pid), { stdio: 'ignore', windowsHide: true });
    return true;
  } catch {
    // already gone, or not ours to kill
    return false;
  }
}

export function killGroup(pid: number | undefined): void {
  if (pid === undefined) return; // nothing was spawned
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
/**
 * How to start the backend with Gradle: `gradle` from the PATH when there is one, else the checkout's
 * wrapper. Windows cannot spawn a POSIX script, so there it is `gradle.bat` from the PATH - or
 * `gradlew.bat`, run through cmd.exe, which is how Node must start a `.bat` at all.
 */
export function backendGradle({
  platform = process.platform,
  pathValue = process.env.PATH ?? '',
  exists = existsSync,
  task = 'run',
}: {
  platform?: NodeJS.Platform;
  pathValue?: string;
  exists?: (file: string) => boolean;
  task?: string;
} = {}): { command: string; args: string[]; cwd: string } {
  const windows = platform === 'win32';
  const path = windows ? win32 : posix;
  const args = ['-p', backendDir, '--no-daemon', task, '--console=plain'];
  // The file the PATH actually holds: Windows' shim need not be named `gradle.bat`, and a hard-coded
  // name the shell cannot find is exactly how run #482 failed.
  const directory = commandDirectory('gradle', { platform, pathValue, exists });
  if (directory !== null) {
    const names = windows ? ['gradle.bat', 'gradle.cmd', 'gradle.exe'] : ['gradle'];
    const found = names.map((name) => path.join(directory, name)).find((file) => exists(file));
    if (found !== undefined) return { command: found, args: args.slice(2), cwd: backendDir };
  }
  // `needsShell` runs a `.bat`/`.cmd` through cmd.exe, which Windows requires.
  return { command: path.join(repoRoot, windows ? 'gradlew.bat' : 'gradlew'), args, cwd: repoRoot };
}

/**
 * Mirrors the backend's stdout/stderr to the console and appends them to [BACKEND_LOG_FILE], so a
 * CI step can assert which whisper backend a task used (roadmap item 48).
 */
function captureBackendOutput(child: ChildProcess): void {
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

/**
 * The port `runtime.json`'s endpoint names, or [fallback] when it names none. A service whose durable port was taken
 * serves on a free one and advertises that in `runtime.json` (roadmap item 66) - the app and the tray read it, and a
 * start that polled the port it asked for would sit out its whole health timeout against another program's server.
 */
export function runtimePort(runtime: unknown, fallback: number): number {
  const endpoint = (runtime as { endpoint?: unknown } | null | undefined)?.endpoint;
  if (typeof endpoint !== 'string') return fallback;
  try {
    const port = new URL(endpoint).port;
    return port === '' ? fallback : Number(port);
  } catch {
    return fallback;
  }
}

/** `runtime.json`'s advertisement, or null while the service has written none. */
function readRuntimeOrNull(runtimeDir: string): unknown {
  try {
    return JSON.parse(readFileSync(join(runtimeDir, 'runtime.json'), 'utf8')) as unknown;
  } catch {
    return null;
  }
}

/** The environment every backend gets after [env]: a fresh data dir, the tiny model and the port. */
function backendEnv(
  dataDir: string,
  port: number,
  env: NodeJS.ProcessEnv,
  staticDir: string,
): NodeJS.ProcessEnv {
  return {
    ...process.env,
    ...env,
    STT_DATA_DIR: dataDir,
    STT_WHISPER_MODEL: process.env.TEST_WHISPER_TINY_EN_MODEL,
    STT_STATIC_DIR: staticDir,
    STT_PORT: String(port),
  };
}

/**
 * Waits for the backend to answer health, or fails as soon as its process exits or cannot start: a
 * dead process can never answer, so the failure names its exit at once instead of after the whole
 * health timeout.
 */
async function waitForHealthOrExit(
  child: ChildProcess,
  port: number,
  timeoutMs: number,
  authorization?: () => string | undefined,
  advertisedPort?: () => number,
): Promise<void> {
  const ended = Promise.withResolvers<never>();
  // Ends the health wait with the start: a dead process can never answer, and polling it again would only
  // keep the harness busy for the rest of the timeout.
  const aborted = new AbortController();
  // The service advertises the port it actually serves on in `runtime.json` (the durable port can be taken);
  // polls follow it, as the app and the tray do.
  const servingPort = (): number => advertisedPort?.() ?? port;
  const failed = (reason: string): Error =>
    new Error(
      `the backend ${reason} before answering health on :${port}; its output is in ${BACKEND_LOG_FILE}`,
    );
  const onExit = (code: number | null, signal: NodeJS.Signals | null): void => {
    aborted.abort();
    ended.reject(
      failed(`exited with code ${code ?? 'none'}${signal === null ? '' : ` (${signal})`}`),
    );
  };
  const onError = (error: Error): void => {
    aborted.abort();
    ended.reject(failed(`could not start: ${error.message}`));
  };
  child.once('exit', onExit);
  child.once('error', onError);
  try {
    await Promise.race([
      waitForHealth(servingPort(), { timeoutMs, signal: aborted.signal, authorization }),
      ended.promise,
    ]);
  } finally {
    child.off('exit', onExit);
    child.off('error', onError);
  }
}

/**
 * Spawns one backend process in its own group, mirrors its output into the log, and waits for it to
 * answer health. A backend that never comes up — or that exits first — is stopped again before the
 * error surfaces.
 */
async function launchBackend({
  command,
  args,
  cwd,
  port,
  dataDir,
  env = {},
  staticDir,
  healthTimeoutMs = 600_000,
  authorization,
  advertisedPort,
}: {
  command: string;
  args: string[];
  cwd: string;
  port: number;
  dataDir: string;
  env?: NodeJS.ProcessEnv;
  staticDir: string;
  healthTimeoutMs?: number;
  authorization?: () => string | undefined;
  /** The port the service advertised when it is up, else the asked one. */
  advertisedPort?: () => number;
}): Promise<{ pid: number | undefined }> {
  const child = spawn(command, args, {
    cwd,
    detached: true, // own session + process group: a group kill reaches the backend and the app JVM
    env: backendEnv(dataDir, port, env, staticDir),
    stdio: ['ignore', 'pipe', 'pipe'],
    shell: needsShell(command),
  });
  captureBackendOutput(child);
  try {
    await waitForHealthOrExit(child, port, healthTimeoutMs, authorization, advertisedPort);
  } catch (error) {
    // A start that failed still owns its spawn: the stop ends the whole tree (POSIX finds and
    // SIGKILLs the app JVM even when it ignores SIGTERM; Windows kills the spawn's tree, which
    // reaches the app JVM and the tools its own JVM kill could leave behind).
    await stopBackend(child.pid, port, dataDir, { spawnTree: true });
    throw error;
  }
  return { pid: child.pid };
}

export async function startBackend({
  port,
  dataDir,
  env = {},
  healthTimeoutMs = 600_000,
}: {
  port: number;
  dataDir: string;
  env?: NodeJS.ProcessEnv;
  healthTimeoutMs?: number;
}): Promise<{ pid: number | undefined }> {
  checkPreconditions();
  const backendCmd = process.env.E2E_BACKEND_CMD;
  if (backendCmd) {
    // A command line is allowed (e.g. './gradlew run'); a bare path (item 16's
    // ./result/bin/stt-app) still works — the split is a no-op without spaces.
    const [command = '', ...args] = backendCmd.split(/\s+/).filter(Boolean);
    return await launchBackend({
      command,
      args,
      cwd: repoRoot,
      port,
      dataDir,
      env,
      // The command may be the packaged wrapper (which sets its own static dir) or a bare
      // backend (`java -cp …`, `./gradlew run`), which serves 404 at `/` without this.
      staticDir: process.env.STT_STATIC_DIR ?? frontendDist,
      healthTimeoutMs,
    });
  }
  // The journey runs against the per-user service (roadmap item 66, e2e line E2): app mode, which
  // requires the credential, on the same data directory so a restart keeps its database.
  const dirs = appModeDirs(dataDir);
  return await startService({ port, dataDir, ...dirs, env, healthTimeoutMs });
}

/** The service's bootstrap directories for one run's data directory: `config/` and `runtime/` inside it. */
export function appModeDirs(dataDir: string): { configDir: string; runtimeDir: string } {
  return { configDir: join(dataDir, 'config'), runtimeDir: join(dataDir, 'runtime') };
}

/**
 * `stt-app` built from this checkout with the pinned toolchain — the client the journey's browser
 * enters through (roadmap item 66). Built once per run.
 */
export function sttAppPath(): string {
  const exe = process.platform === 'win32' ? 'stt-app.exe' : 'stt-app';
  const path = join(repoRoot, 'client', 'target', 'debug', exe);
  execFileSync(
    'cargo',
    [
      'build',
      '--locked',
      '--manifest-path',
      join(repoRoot, 'client', 'Cargo.toml'),
      '--bin',
      'stt-app',
    ],
    { cwd: join(repoRoot, 'client'), stdio: 'inherit' },
  );
  return path;
}

/** The one-time launch URL `stt-app open` prints for the browser frontend of one service. */
export function appModeLaunchUrl(dirs: { configDir: string; runtimeDir: string }): string {
  return execFileSync(sttAppPath(), ['open', 'browser', '--print-url'], {
    env: { ...process.env, STT_CONFIG_DIR: dirs.configDir, STT_RUNTIME_DIR: dirs.runtimeDir },
    encoding: 'utf8',
  }).trim();
}

/** Registers the browser frontend in a service's registry, as its installer would. */
export function registerBrowserFrontend(configDir: string): void {
  const descriptor = join(configDir, 'browser.json');
  writeFileSync(
    descriptor,
    JSON.stringify({ version: 1, id: 'browser', kind: 'browser', name: 'Browser', url: '/' }),
  );
  execFileSync(sttAppPath(), ['register', descriptor], {
    env: { ...process.env, STT_CONFIG_DIR: configDir },
  });
}

/** Where global setup leaves the session cookie every browser context starts with. */
export const STORAGE_STATE_FILE = join(e2eRoot, '.e2e-storage.json');

/** Writes Playwright's storage state holding [cookie] as the service's session. */
export function writeStoredSession(cookie: { name: string; value: string }): void {
  writeFileSync(
    STORAGE_STATE_FILE,
    JSON.stringify({
      cookies: [
        {
          name: cookie.name,
          value: cookie.value,
          domain: '127.0.0.1',
          path: '/',
          expires: -1,
          httpOnly: true,
          secure: false,
          sameSite: 'Strict',
        },
      ],
      origins: [],
    }),
  );
}

/**
 * Replaces the stored session after the service restarted (roadmap item 66): sessions live in the
 * service process, so the contexts created by later tests must carry the new one.
 */
export async function refreshStoredSession(dirs: {
  configDir: string;
  runtimeDir: string;
}): Promise<void> {
  // A dev-mode server (`E2E_BACKEND_CMD`) has no credential and no launch handshake, so there is
  // nothing to carry across its restart - and a stored cookie from an earlier app-mode run would be
  // wrong for it.
  if (process.env.E2E_BACKEND_CMD !== undefined) {
    rmSync(STORAGE_STATE_FILE, { force: true });
    return;
  }
  writeStoredSession(await captureSessionCookie(dirs));
}

/**
 * Freezes the backend's process group (SIGSTOP), the app JVM included: its requests hang until the
 * group is killed, which lets a spec cut an upload off mid-request without racing a response.
 * POSIX only (Windows has no SIGSTOP). The group is the one `startBackend` spawned detached.
 */
export function freezeBackend(pid: number): void {
  if (process.platform === 'win32') {
    throw new Error('freezeBackend needs POSIX signals');
  }
  try {
    process.kill(-pid, 'SIGSTOP');
  } catch {
    throw new Error(`freezeBackend: no process group ${pid} to freeze`);
  }
}

/** Ends a frozen backend's group (SIGKILL; a stopped group ignores SIGTERM) and waits for it. */
export async function killFrozenBackend(pid: number, port: number): Promise<void> {
  try {
    process.kill(-pid, 'SIGKILL');
  } catch {
    // already gone
  }
  await waitForHealth(port, { down: true, timeoutMs: 60_000 });
}

/**
 * Brings the shared backend back after a spec restarted it: stop the process the spec started,
 * start a fresh one on the same socket and data directory, and save its pid. The global teardown
 * only knows the run's original pid, so the handover must be explicit.
 */
export async function handOverSharedBackend(
  pid: number | undefined,
  port: number,
  dataDir: string,
): Promise<void> {
  await stopBackend(pid, port, dataDir);
  const fresh = await startBackend({ port, dataDir });
  saveState({ pid: fresh.pid });
}

/**
 * Redeems one launch URL for its session cookie, without a browser: the cookie the journey's browser
 * contexts start with (Playwright's storage state). The token is spent by this call.
 */
export async function captureSessionCookie(dirs: {
  configDir: string;
  runtimeDir: string;
}): Promise<{ name: string; value: string }> {
  const response = await fetch(appModeLaunchUrl(dirs), { redirect: 'manual' });
  const setCookie = response.headers.getSetCookie()[0];
  const pair = setCookie?.split(';')[0] ?? '';
  const [name, value] = pair.split('=');
  if (name === undefined || value === undefined) {
    throw new Error(`the launch URL answered ${response.status} without a session cookie`);
  }
  return { name, value };
}

/**
 * Starts the backend in app mode — the per-user service of roadmap item 66 — on its own data, config
 * and runtime directories, and waits until it answers health to the per-install secret it writes to
 * `config.json`. Always through Gradle: `E2E_BACKEND_CMD` names a dev-mode server.
 */
/** Where the backend's installed distribution keeps its launcher (Gradle's `installDist` output). */
const BACKEND_INSTALL = join(backendDir, 'build', 'install', 'stt-backend');

/**
 * The backend's installed launcher, building the distribution once per run when it is missing. The
 * launcher runs the app's JVM directly — no Gradle daemon or wrapper at test time — and Windows gets
 * Gradle's own `.bat`, which `needsShell` runs through cmd.exe.
 */
async function backendLauncher(): Promise<string> {
  const windows = process.platform === 'win32';
  const launcher = join(BACKEND_INSTALL, 'bin', `stt-backend${windows ? '.bat' : ''}`);
  if (!existsSync(launcher)) {
    console.log(`e2e: no backend launcher at ${launcher}; building the distribution`);
    const gradle = backendGradle({ task: 'installDist' });
    execFileSync(gradle.command, gradle.args, { cwd: gradle.cwd, stdio: 'inherit' });
  }
  if (!existsSync(launcher)) {
    throw new Error(`no backend launcher at ${launcher} after installDist`);
  }
  return launcher;
}

export async function startService({
  port,
  dataDir,
  configDir,
  runtimeDir,
  env = {},
  // Below Playwright's 600 s test timeout, so a service that never answers fails with its own output
  // and the launcher line instead of the test timing out with nothing to go on.
  healthTimeoutMs = 120_000,
}: {
  port: number;
  dataDir: string;
  configDir: string;
  runtimeDir: string;
  env?: NodeJS.ProcessEnv;
  healthTimeoutMs?: number;
}): Promise<{ pid: number | undefined }> {
  checkPreconditions();
  for (const dir of [configDir, runtimeDir]) mkdirSync(dir, { recursive: true });
  // The distribution's launcher, not `gradle run`: Gradle at test time is minutes of daemon and
  // wrapper work under a loaded runner (CI run #500: the macOS leg sat the whole 600 s health
  // timeout), while the launcher starts the same JVM directly. `installDist` runs once per run.
  const launcher = await backendLauncher();
  console.log(`e2e: starting the service with ${launcher} on :${port}`);
  // A service whose durable port was taken serves on a free one and advertises that in `runtime.json`;
  // the wait follows the advertised endpoint, as every client does (CI run #500 polled the asked port
  // for the whole health timeout while the service answered on another).
  return await launchBackend({
    command: launcher,
    args: ['service'],
    cwd: backendDir,
    port,
    dataDir,
    env: { ...env, STT_CONFIG_DIR: configDir, STT_RUNTIME_DIR: runtimeDir },
    staticDir: frontendDist,
    healthTimeoutMs,
    authorization: () => serviceAuthorization(configDir),
    advertisedPort: () => runtimePort(readRuntimeOrNull(runtimeDir), port),
  });
}

/** `Bearer <secret>` from the service's `config.json`, or nothing until the service has written it. */
function serviceAuthorization(configDir: string): string | undefined {
  try {
    const { secret } = JSON.parse(readFileSync(join(configDir, 'config.json'), 'utf8')) as {
      secret?: unknown;
    };
    return typeof secret === 'string' ? `Bearer ${secret}` : undefined;
  } catch {
    return undefined;
  }
}
