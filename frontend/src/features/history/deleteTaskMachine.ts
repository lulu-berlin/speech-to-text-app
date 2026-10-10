import { assign, fromPromise, setup } from 'xstate';
import { deleteTask } from '../../shared/api/client';
import type { ApiError, ApiErrorBody } from '../../shared/api/types';

// Deletes a task (ADR 0031-style, but delete is not a status transition so it is not one
// of commandMachine's commands). The dialog's open/closed and the sending/done/failed flow
// live in the machine, so no useState drives the state transitions; on failure the typed
// error lands in `context.error` for an inline message.
export const deleteTaskMachine = setup({
  types: {
    input: {} as { taskId: number },
    context: {} as { taskId: number; error: ApiError | null },
    events: {} as { type: 'REQUEST' } | { type: 'CONFIRM' } | { type: 'CANCEL' },
  },
  actors: {
    run: fromPromise<void, { taskId: number }>(({ input }) => deleteTask(input.taskId)),
  },
}).createMachine({
  id: 'deleteTask',
  context: ({ input }) => ({ taskId: input.taskId, error: null }),
  initial: 'idle',
  states: {
    // The dialog is closed until REQUEST opens it.
    idle: { on: { REQUEST: 'confirming' } },
    confirming: {
      on: {
        CONFIRM: 'sending',
        CANCEL: 'idle',
      },
    },
    sending: {
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
