// One command or destructive action of a task row (item 14, ADR 0038): idle → confirming
// (only when the action needs it, decided by the row) → running → done | failed. The
// row's buttons ACTIVATE the machine; the shared I/O (runTaskCommand / deleteTask) lives
// here. TaskRow reads done (forwarding the task or the deletion to the live state) and
// failed (context.error is the inline error of item 17). ACTIVATE is handled on the
// `ready` parent, so a failed action can start the next one, clearing the error.
import { assign, fromPromise, setup } from 'xstate';
import { deleteTask, runTaskCommand } from '../../shared/api/client';
import type { Task } from '../../shared/api/types';

export type RowAction = 'pause' | 'resume' | 'cancel' | 'delete';

export interface RowActionInput {
  taskId: number;
}

interface RunInput {
  taskId: number;
  action: RowAction;
  status: Task['status'];
}

// The action the row asked for, or null while idle; status is the task's status at
// activation time; task is the command's response; error is the failure of item 17.
interface RowActionContext {
  taskId: number;
  action: RowAction | null;
  status: Task['status'] | null;
  task: Task | null;
  error: unknown;
}

export const rowActionMachine = setup({
  types: {
    input: {} as RowActionInput,
    context: {} as RowActionContext,
    events: {} as
      | { type: 'ACTIVATE'; action: RowAction; status: Task['status']; confirm: boolean }
      | { type: 'CONFIRM' }
      | { type: 'DISMISS' },
  },
  actors: {
    run: fromPromise<Task | null, RunInput>(({ input }) =>
      input.action === 'delete'
        ? deleteTask(input.taskId).then(() => null)
        : runTaskCommand(input.taskId, input.action),
    ),
  },
  actions: {
    // The row asked for an action: remember it and its task status, clear any previous
    // failure.
    setAction: assign({
      action: ({ event }) => (event.type === 'ACTIVATE' ? event.action : null),
      status: ({ event }) => (event.type === 'ACTIVATE' ? event.status : null),
      error: null,
    }),
  },
}).createMachine({
  id: 'rowAction',
  context: ({ input }) => ({
    taskId: input.taskId,
    action: null,
    status: null,
    task: null,
    error: null,
  }),
  initial: 'ready',
  states: {
    // Idle or failed: the same ACTIVATE applies, asking only when the row says so.
    ready: {
      initial: 'idle',
      on: {
        ACTIVATE: [
          {
            guard: ({ event }) => event.confirm,
            target: 'confirming',
            actions: 'setAction',
          },
          {
            target: 'running',
            actions: 'setAction',
          },
        ],
      },
      states: {
        idle: {},
        failed: {},
      },
    },
    confirming: {
      on: {
        CONFIRM: 'running',
        DISMISS: {
          target: 'ready',
          actions: assign({ action: null }),
        },
      },
    },
    running: {
      invoke: {
        src: 'run',
        input: ({ context }) => ({
          taskId: context.taskId,
          action: context.action as RowAction,
          status: context.status as Task['status'],
        }),
        onDone: {
          target: 'done',
          actions: assign({ task: ({ event }) => event.output }),
        },
        onError: {
          target: 'ready.failed',
          actions: assign({ error: ({ event }) => event.error }),
        },
      },
    },
    done: {},
  },
});
