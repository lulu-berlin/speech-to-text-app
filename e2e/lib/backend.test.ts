// The backend stop on Windows (roadmap item 79, test-list item 2): Windows has neither a POSIX
// process group nor a catchable SIGTERM, so the stop finds the app JVM in the Windows process
// listing and ends its whole tree with `taskkill /T /F` — whisper-cli and ffmpeg are its
// descendants — instead of signalling a group. The run's own list is the seam: `gradle run` forks a
// single-use daemon whose app JVM sits outside the launcher's process group on POSIX, so the same
// listing finds the app JVM by its arguments there.
import assert from 'node:assert/strict';
import { ChildProcess } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { subscribe, unsubscribe } from 'node:diagnostics_channel';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { Socket } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { setImmediate } from 'node:timers/promises';
import * as backend from './backend.ts';
import { processes } from './processes.ts';
import { waitUntil } from './wait.ts';

const DATA_DIR = 'C:\\Users\\runner\\Temp\\stt-e2e-1234';

// `Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,CommandLine |
// ConvertTo-Json -Compress`: one object per process. The launcher (735) spawns the app JVM (4812),
// which spawns whisper-cli (4900); `/T` on the JVM's pid must reach the tool.
const CIM = JSON.stringify([
  { ProcessId: 735, ParentProcessId: 1, CommandLine: 'cmd.exe /c gradlew.bat e2e' },
  {
    ProcessId: 4812,
    ParentProcessId: 735,
    CommandLine: `C:\\jdk\\bin\\java.exe -cp lib/* stt.MainKt ${DATA_DIR}`,
  },
  {
    ProcessId: 4900,
    ParentProcessId: 4812,
    CommandLine: 'C:\\tools\\whisper-cli.exe -m model -f clip.wav',
  },
]);

test('on Windows the backend stop ends the app JVM tree with taskkill, not a POSIX group kill', async (t) => {
  // A POSIX signal — or a negative-pid group kill — would be the bug this test rules out.
  const signal = t.mock.method(process, 'kill', () => true);
  const ended: number[] = [];
  const port = await backend.choosePort();
  await backend.stopBackend(735, port, DATA_DIR, {
    platform: 'win32',
    list: () => processes({ platform: 'win32', cimOutput: () => CIM }),
    forceKill: async (pid) => {
      ended.push(pid);
      return true;
    },
  });

  assert.deepEqual(
    ended,
    [4812],
    'taskkill runs on the app JVM; /T takes whisper-cli and ffmpeg with it',
  );
  assert.deepEqual(backend.taskkillArgs(4812), ['/pid', '4812', '/T', '/F']);
  assert.equal(signal.mock.callCount(), 0, 'no signal and no negative-pid group kill on Windows');
});

// Roadmap item 79 (discovered): on Windows the Gradle-built backend is `stt-backend.bat`, and Node
// refuses to start a .bat or .cmd without a shell since its 2024 security fix (EINVAL) — the journey
// would fail in global setup before the first spec. Everything else still starts without one.
test('on Windows a .bat or .cmd backend starts through a shell, anything else directly', () => {
  const cases: [string, NodeJS.Platform, boolean][] = [
    ['backend\\build\\install\\stt-backend\\bin\\stt-backend.bat', 'win32', true],
    ['C:\\tools\\STT-APP.CMD', 'win32', true],
    ['C:\\jdk\\bin\\java.exe', 'win32', false],
    ['backend/build/install/stt-backend/bin/stt-backend', 'linux', false],
    ['./result/bin/stt-app.cmd', 'darwin', false],
  ];
  for (const [command, platform, shell] of cases) {
    assert.equal(backend.needsShell(command, platform), shell, `${command} on ${platform}`);
  }
});

// Roadmap item 79 (discovered): a backend that exits during startup can never answer health, so the
// start fails at once with the exit it reported — not after the full health timeout (run #388 sat
// idle 10 minutes on a dead backend). Roadmap item 84: "at once" is proven by order, not by a clock —
// a 5 s bound failed on a loaded Windows runner (5136 ms, with the right exit). The health timeout
// runs on mocked time that stands still until the backend's exit has been heard, so only the exit
// can end the start; the tick afterwards turns a start that missed the exit into its timeout
// failure instead of a hang.
test('a backend that exits before answering health fails the start naming the exit, not the health timeout', async (t) => {
  const dataDir = mkdtempSync(join(tmpdir(), 'stt-e2e-exited-'));
  const script = writeFakeBackend(
    dataDir,
    'exits',
    `console.log('fake backend: the data directory is unusable');
process.exit(3);
`,
  );
  const port = await backend.choosePort();
  const healthTimeoutMs = 60_000;
  // Node's built-in channel hands over the spawned backend before the start subscribes to it, so the
  // test hears the exit first; the start's own listener runs in the same emit.
  const exited = Promise.withResolvers<void>();
  const onSpawn = (message: unknown): void => {
    if (typeof message !== 'object' || message === null || !('process' in message)) return;
    if (message.process instanceof ChildProcess) {
      message.process.once('exit', () => exited.resolve());
    }
  };
  subscribe('child_process', onSpawn);
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'] });
  try {
    await withEnv(fakeBackendEnv(script), async () => {
      const outcome = outcomeOf(backend.startBackend({ port, dataDir, healthTimeoutMs }));
      await exited.promise;
      // Let the start's rejection run (setImmediate is not mocked), then let the timeout fire.
      await setImmediate();
      t.mock.timers.tick(healthTimeoutMs);

      const error = await outcome;
      assert.ok(error instanceof Error, 'the start fails');
      assert.match(error.message, /exited with code 3/);
      assert.doesNotMatch(error.message, /did not answer within/);
    });
  } finally {
    unsubscribe('child_process', onSpawn);
    rmSync(dataDir, { recursive: true, force: true });
  }
});

// Roadmap item 79, review finding 6: once the start has failed there is nothing left to wait for, so the
// health wait must stop instead of polling a dead backend for the rest of its 600 s timeout. Roadmap
// item 84: proven by order, not by sleeps — a poll counts when the harness starts the request (Node's
// `http.client.request.start` channel), not when it lands, the backend exits only once the test has
// seen it poll, and the health wait's interval runs on mocked time, so after the failure the test
// steps past the whole timeout at once.
test('a start that fails stops polling the backend port', async (t) => {
  const dataDir = mkdtempSync(join(tmpdir(), 'stt-e2e-poll-'));
  const exitNow = join(dataDir, 'exit-now');
  const script = writeFakeBackend(
    dataDir,
    'exits-on-cue',
    `import { existsSync } from 'node:fs';
// A separate process the test's mocked clock cannot reach: it watches for the test's cue file.
setInterval(() => {
  if (existsSync(${JSON.stringify(exitNow)})) process.exit(3);
}, 10);
`,
  );
  const port = await backend.choosePort();
  const server = createServer((_request, response) => {
    response.writeHead(503);
    response.end();
  });
  const listening = Promise.withResolvers<void>();
  server.listen(port, '127.0.0.1', listening.resolve);
  await listening.promise;
  let polls = 0;
  const polled = Promise.withResolvers<void>();
  const onRequest = (): void => {
    polls += 1;
    polled.resolve();
  };
  subscribe('http.client.request.start', onRequest);
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'] });
  try {
    await withEnv(fakeBackendEnv(script), async () => {
      const outcome = outcomeOf(backend.startBackend({ port, dataDir, healthTimeoutMs: 60_000 }));
      await polled.promise;
      writeFileSync(exitNow, '');
      const error = await outcome;
      assert.ok(error instanceof Error, 'the start fails');
      assert.match(error.message, /exited with code 3/);
    });
    const settled = polls;
    t.mock.timers.tick(60_000);
    // Let a woken health wait run its next attempt (setImmediate is not mocked).
    await setImmediate();
    assert.equal(polls, settled, 'no health poll after the start failed');
  } finally {
    unsubscribe('http.client.request.start', onRequest);
    const closed = Promise.withResolvers<void>();
    server.close(() => closed.resolve());
    await closed.promise;
    rmSync(dataDir, { recursive: true, force: true });
  }
});

// Roadmap item 84 (discovered): Node 24's bundled undici calls `socket.setTypeOfService()` on every
// HTTP/1.1 request, and on macOS that can throw EINVAL on a socket in teardown — outside the `fetch`
// promise, so it killed the Playwright worker while `17-settings` restarted the backend (macOS e2e,
// CI run 41165893251; nodejs/undici#5544, unfixed on Node 24). Here every socket refuses the call, so
// the health wait answers only if it does not depend on it.
test('the health wait answers even when every socket refuses setTypeOfService', async (t) => {
  t.mock.method(Socket.prototype, 'setTypeOfService', () => {
    throw Object.assign(new Error('setTypeOfService EINVAL'), {
      code: 'EINVAL',
      syscall: 'setTypeOfService',
    });
  });
  const server = createServer((_request, response) => {
    response.writeHead(200);
    response.end();
  });
  const port = await backend.choosePort();
  const listening = Promise.withResolvers<void>();
  server.listen(port, '127.0.0.1', listening.resolve);
  await listening.promise;
  try {
    await backend.waitForHealth(port, { timeoutMs: 60_000 });
  } finally {
    server.closeAllConnections();
    const closed = Promise.withResolvers<void>();
    server.close(() => closed.resolve());
    await closed.promise;
  }
});

// A start that never answers health is a run that failed on the way (a bad model path, a spec that
// killed the backend): the harness must not leave the launcher or the app JVM behind, and the output
// the backend managed to write has to stay readable. POSIX signalled only the group, which an app
// JVM that ignores SIGTERM survives; a failure path that ends the tree leaves neither behind on any
// platform (SIGKILL by classification on POSIX, `taskkill /T /F` on Windows).
test('a backend that never answers health is stopped with its tree, and its output stays in the log', async () => {
  const dataDir = mkdtempSync(join(tmpdir(), 'stt-e2e-failed-'));
  const marker = `stt-e2e-failed-${randomUUID()}`;
  const script = writeFakeBackend(
    dataDir,
    'never-answers',
    `
import { spawn } from 'node:child_process';
// The 'app JVM': named as the harness finds it, and ignoring SIGTERM the way a JVM with a shutdown
// hook can — only a tree kill (SIGKILL / \`taskkill /T /F\`) takes it down.
spawn(process.execPath, [
  '-e',
  "process.on('SIGTERM', () => {}); console.log(process.argv[1]); setInterval(() => {}, 1e6);",
  ${JSON.stringify(marker)},
  'stt.MainKt',
  ${JSON.stringify(dataDir)},
], { stdio: 'inherit' });
setInterval(() => {}, 1e6);
`,
  );
  const port = await backend.choosePort();
  await withEnv(fakeBackendEnv(script), async () => {
    await assert.rejects(
      backend.startBackend({ port, dataDir, healthTimeoutMs: 1_500 }),
      /did not answer within/,
      'a backend that never answers health fails its start',
    );
  });

  // Real child processes: these waits poll the platform clock on purpose — fake timers cannot see a
  // process die, and the assertion is what a guessed sleep would hide.
  const survivors = (): string[] =>
    processes()
      .filter((row) => row.command.includes(marker) || row.command.includes(script))
      .map((row) => row.command);
  assert.ok(
    await waitUntil(() => survivors().length === 0, { timeoutMs: 5_000 }),
    `the launcher and the app JVM are both gone: ${JSON.stringify(survivors())}`,
  );

  const logText = (): string =>
    existsSync(backend.BACKEND_LOG_FILE) ? readFileSync(backend.BACKEND_LOG_FILE, 'utf8') : '';
  assert.ok(
    await waitUntil(() => logText().includes(marker), { timeoutMs: 5_000 }),
    "the failed run's output is still readable in the log",
  );
  rmSync(dataDir, { recursive: true, force: true });
});

/**
 * Writes a fake backend script named [name] into [dataDir] and returns its path: `E2E_BACKEND_CMD`
 * splits on whitespace, so a fake backend has to be a file.
 */
function writeFakeBackend(dataDir: string, name: string, source: string): string {
  const script = join(dataDir, `${name}.ts`);
  writeFileSync(script, source);
  return script;
}

/**
 * The environment that starts [script] as the backend. Presence of the TEST_* variables is all
 * checkPreconditions asks for, and these tests never run a transcription.
 */
function fakeBackendEnv(script: string): NodeJS.ProcessEnv {
  const env: NodeJS.ProcessEnv = { E2E_BACKEND_CMD: `${process.execPath} ${script}` };
  for (const key of ['TEST_WHISPER_TINY_EN_MODEL', 'STT_SPEAKER_MODEL', 'TEST_JFK_WAV']) {
    if (process.env[key] === undefined) env[key] = 'not-needed-by-this-test';
  }
  return env;
}

/** Runs [body] with [vars] set in the environment, restoring every key afterwards. */
async function withEnv(vars: NodeJS.ProcessEnv, body: () => Promise<void>): Promise<void> {
  const saved = new Map<string, string | undefined>();
  for (const [key, value] of Object.entries(vars)) {
    saved.set(key, process.env[key]);
    if (value !== undefined) process.env[key] = value;
  }
  try {
    await body();
  } finally {
    for (const [key, value] of saved) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  }
}

/**
 * What [start] settles with — null when it succeeds, the error when it fails — without awaiting it, so
 * a test can cue the backend's exit or move the clock before it looks.
 */
const outcomeOf = (start: Promise<unknown>): Promise<unknown> =>
  start.then(
    () => null,
    (error: unknown) => error,
  );
