// The one confirmed destructive task action (ADR 0038): idle until REQUEST, then
// confirming → running → done | failed, with the failure in context.error. Delete is not
// a status transition, so it is not one of commandMachine's commands; every delete flow
// (history list, history detail, queue rows) runs through this one machine.
import { assign, fromPromise, setup } from 'xstate';
import { deleteTask } from '../api/client';
import type { ApiError } from '../api/types';

export const confirmActionMachine = setup({
  types: {
    input: {} as { taskId: number },
    context: {} as { taskId: number; error: ApiError | null },
    events: {} as { type: 'REQUEST' } | { type: 'CONFIRM' } | { type: 'CANCEL' },
  },
  actors: {
    run: fromPromise<void, { taskId: number }>(({ input }) => deleteTask(input.taskId)),
  },
}).createMachine({
  id: 'confirmAction',
  context: ({ input }) => ({ taskId: input.taskId, error: null }),
  initial: 'idle',
  states: {
    // The dialog is closed until REQUEST opens it.
    idle: { on: { REQUEST: 'confirming' } },
    confirming: {
      on: {
        CONFIRM: 'running',
        CANCEL: 'idle',
      },
    },
    running: {
      invoke: {
        src: 'run',
        input: ({ context }) => ({ taskId: context.taskId }),
        onDone: 'done',
        onError: {
          target: 'failed',
          actions: assign({ error: ({ event }) => event.error as ApiError }),
        },
      },
    },
    done: {},
    failed: {},
  },
});
