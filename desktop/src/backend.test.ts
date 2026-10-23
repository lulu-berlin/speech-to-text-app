import assert from 'node:assert/strict';
import { mock, test } from 'node:test';
import { backendStdio, backendStopEnv, stopBackend, type StoppableChild } from './backend.ts';

/** A backend process double: records what a stop does to it, and [exit] ends it as a real exit would. */
class FakeBackend implements StoppableChild {
  exitCode: number | null = null;
  readonly pid = 4242;
  readonly signals: Array<NodeJS.Signals | 'default'> = [];
  readonly stdin = {
    ended: false,
    end(): void {
      this.ended = true;
    },
  };
  readonly #exitListeners: Array<() => void> = [];

  kill(signal?: NodeJS.Signals): boolean {
    this.signals.push(signal ?? 'default');
    return true;
  }

  once(_event: 'exit', listener: () => void): this {
    this.#exitListeners.push(listener);
    return this;
  }

  /** Ends the process the way a real exit does, resolving a stop that waits for it. */
  exit(): void {
    this.exitCode = 0;
    for (const listener of this.#exitListeners) listener();
  }
}

/** A Windows force-kill that records the pids it was asked for and reports that it ended them. */
function forceKills(forced: number[]): (pid: number) => Promise<boolean> {
  return async (pid) => {
    forced.push(pid);
    return true;
  };
}

test('on Windows the quit closes the backend stdin and force-kills only after the wait', async () => {
  mock.timers.enable({ apis: ['setTimeout'] });
  try {
    const backend = new FakeBackend();
    const forced: number[] = [];

    const stopping = stopBackend(backend, {
      platform: 'win32',
      timeoutMs: 10_000,
      forceKill: forceKills(forced),
    });

    assert.equal(backend.stdin.ended, true, 'the graceful ask is the closed stdin');
    assert.deepEqual(forced, [], 'not forced before the wait');
    mock.timers.tick(9_999);
    assert.deepEqual(forced, [], 'not forced a millisecond early');
    mock.timers.tick(1);
    assert.deepEqual(forced, [backend.pid], 'forced once the backend outlived the wait');
    assert.deepEqual(backend.signals, [], 'Windows gets no signal');
    backend.exit();
    await stopping;
  } finally {
    mock.timers.reset();
  }
});

test('on Windows the quit never force-kills a backend that is already gone', async () => {
  mock.timers.enable({ apis: ['setTimeout'] });
  try {
    // It ends within the wait: the deferred force-kill must find nothing to do.
    const exiting = new FakeBackend();
    const forcedAfterExit: number[] = [];
    const stopping = stopBackend(exiting, {
      platform: 'win32',
      timeoutMs: 10_000,
      forceKill: forceKills(forcedAfterExit),
    });
    exiting.exit();
    await stopping;
    mock.timers.tick(10_000);
    assert.deepEqual(forcedAfterExit, [], 'an exited backend is not force-killed');

    // It has already exited before the quit reaches it: the stop resolves at once.
    const exited = new FakeBackend();
    exited.exit();
    const forcedBefore: number[] = [];
    await stopBackend(exited, {
      platform: 'win32',
      timeoutMs: 10_000,
      forceKill: forceKills(forcedBefore),
    });
    assert.equal(exited.stdin.ended, false, 'nothing is asked of a gone backend');
    mock.timers.tick(10_000);
    assert.deepEqual(forcedBefore, [], 'nothing is forced either');
  } finally {
    mock.timers.reset();
  }
});

test('on Windows a quit whose taskkill fails logs it and still ends the backend', async () => {
  mock.timers.enable({ apis: ['setTimeout'] });
  try {
    const backend = new FakeBackend();
    const logged: string[] = [];

    const stopping = stopBackend(backend, {
      platform: 'win32',
      timeoutMs: 10_000,
      forceKill: async () => false,
      log: (message) => logged.push(message),
    });

    mock.timers.tick(10_000);
    await Promise.resolve();
    await Promise.resolve();

    assert.equal(logged.length, 1, `one line names the failure, got ${JSON.stringify(logged)}`);
    assert.match(logged[0] ?? '', /taskkill/);
    assert.deepEqual(backend.signals, ['default'], 'the JVM is then ended directly');
    backend.exit();
    await stopping;
  } finally {
    mock.timers.reset();
  }
});

test('on Windows the backend is started with the stdin-close switch, elsewhere without it', () => {
  assert.deepEqual(backendStopEnv('win32'), { STT_STOP_ON_STDIN_CLOSE: '1' });
  assert.deepEqual(backendStopEnv('linux'), {});
  assert.deepEqual(backendStopEnv('darwin'), {});
});

test('on Windows the backend stdin is the quit pipe, elsewhere it is ignored', () => {
  assert.equal(backendStdio('win32')[0], 'pipe');
  assert.equal(backendStdio('linux')[0], 'ignore');
  assert.equal(backendStdio('darwin')[0], 'ignore');
});
