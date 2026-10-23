import assert from 'node:assert/strict';
import { mock, test } from 'node:test';
import { stopBackend, type StoppableChild } from './backend.ts';

/** A backend process double: records what a stop does to it, and [exit] ends it as a real exit would. */
class FakeBackend implements StoppableChild {
  exitCode: number | null = null;
  readonly pid = 4242;
  readonly signals: NodeJS.Signals[] = [];
  readonly stdin = {
    ended: false,
    end(): void {
      this.ended = true;
    },
  };
  readonly #exitListeners: Array<() => void> = [];

  kill(signal: NodeJS.Signals): boolean {
    this.signals.push(signal);
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

test('on Windows the quit closes the backend stdin and force-kills only after the wait', async () => {
  mock.timers.enable({ apis: ['setTimeout'] });
  try {
    const backend = new FakeBackend();
    const forced: number[] = [];

    const stopping = stopBackend(backend, {
      platform: 'win32',
      timeoutMs: 10_000,
      forceKill: (pid) => forced.push(pid),
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
