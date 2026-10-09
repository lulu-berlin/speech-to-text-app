import { createActor } from 'xstate';
import { describe, expect, it, vi } from 'vitest';
import { commandMachine } from './commandMachine';

describe('the command machine', () => {
  it('cannot start a command the task status does not allow, and sends no request', () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch');

    // task-transitions.json: pause is allowed from QUEUED and RUNNING only.
    const disallowed = createActor(commandMachine, {
      input: { command: 'pause', taskId: 3, status: 'COMPLETED' },
    }).start();
    disallowed.send({ type: 'START' });
    expect(disallowed.getSnapshot().value).toBe('idle');
    expect(fetchSpy).not.toHaveBeenCalled();
    disallowed.stop();

    // The guard is what stops it: the same command from an allowed status starts.
    const allowed = createActor(commandMachine, {
      input: { command: 'pause', taskId: 5, status: 'RUNNING' },
    }).start();
    allowed.send({ type: 'START' });
    expect(allowed.getSnapshot().value).toBe('sending');
    allowed.stop();

    fetchSpy.mockRestore();
  });
});
