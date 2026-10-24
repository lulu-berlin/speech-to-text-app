// The task commands of a queue row (item 14, ADR 0038): idle → confirming (only when the
// action needs it, decided by the row) → running → done | failed. The row's buttons
// ACTIVATE the machine; the shared I/O (runTaskCommand) lives here. Delete stays in the
// row's action vocabulary but its dialog/delete lifecycle is delegated to
// confirmActionMachine (item 28). TaskRow reads done (forwarding the returned task to the
// live state) and context.error (the inline error of item 17). ACTIVATE is handled on the
// `ready` parent of idle, failed and done, so a failed action can start the next one,
// clearing the error, and a row takes a second command after a successful one (item 40).
// An ACTIVATE that arrives while a command runs is kept and replayed once that command
// settles (item 81); a replay after a failure keeps that error until its own result
// clears or replaces it. A kept command the transition table no longer allows for the
// task's live status (the row's STATUS reports, the command's response) is dropped.
import { assign, enqueueActions, fromPromise, setup } from 'xstate';
import { runTaskCommand } from '../../shared/api/client';
import type { Task } from '../../shared/api/types';
import { isAllowedCommand } from '../../shared/state/transitions';

export type RowAction = 'pause' | 'resume' | 'cancel' | 'delete';

// status is the task's status when the row mounts, the first live status (item 81).
export interface RowActionInput {
  taskId: number;
  status: Task['status'];
}

interface RunInput {
  taskId: number;
  action: RowAction;
  status: Task['status'];
}

// replay marks an ACTIVATE the machine re-sends itself from `kept`; the row never sets it.
interface ActivateEvent {
  type: 'ACTIVATE';
  action: RowAction;
  status: Task['status'];
  confirm: boolean;
  replay?: true;
}

// The action the row asked for, or null while idle; status is the task's status at
// activation time; task is the command's response; error is the failure of item 17;
// kept is an ACTIVATE that arrived while a command ran, liveStatus the newest task status
// the row reported or a command answered with (item 81).
interface RowActionContext {
  taskId: number;
  action: RowAction | null;
  status: Task['status'] | null;
  task: Task | null;
  error: unknown;
  kept: ActivateEvent | null;
  liveStatus: Task['status'];
}

export const rowActionMachine = setup({
  types: {
    input: {} as RowActionInput,
    context: {} as RowActionContext,
    events: {} as
      | ActivateEvent
      | { type: 'CONFIRM' }
      | { type: 'DISMISS' }
      | { type: 'STATUS'; status: Task['status'] },
  },
  actors: {
    // Commands only: delete never reaches the run actor, TaskRow routes it to
    // confirmActionMachine (item 28).
    run: fromPromise<Task, RunInput>(({ input }) => runTaskCommand(input.taskId, input.action)),
  },
  actions: {
    // The row asked for an action: remember it and its task status, clear any previous
    // failure; a replay keeps it (item 81).
    setAction: assign({
      action: ({ event }) => (event.type === 'ACTIVATE' ? event.action : null),
      status: ({ event }) => (event.type === 'ACTIVATE' ? event.status : null),
      error: ({ context, event }) =>
        event.type === 'ACTIVATE' && event.replay === true ? context.error : null,
    }),
    // A command settled: run the ACTIVATE kept while it was in flight, if the transition
    // table still allows it for the live status; otherwise drop it silently.
    replayKept: enqueueActions(({ context, enqueue }) => {
      const kept = context.kept;
      if (kept === null) {
        return;
      }
      if (kept.action !== 'delete' && isAllowedCommand(kept.action, context.liveStatus)) {
        enqueue.raise({ ...kept, replay: true });
      }
      enqueue.assign({ kept: null });
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
    kept: null,
    liveStatus: input.status,
  }),
  // The row reports its task's status whenever it changes.
  on: {
    STATUS: {
      actions: assign({ liveStatus: ({ event }) => event.status }),
    },
  },
  initial: 'ready',
  states: {
    // Idle, failed or done: the same ACTIVATE applies, asking only when the row says so.
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
        // The returned task is forwarded by TaskRow.
        done: {},
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
      on: {
        ACTIVATE: {
          actions: assign({ kept: ({ event }) => event }),
        },
      },
      invoke: {
        src: 'run',
        input: ({ context }) => ({
          taskId: context.taskId,
          action: context.action as RowAction,
          status: context.status as Task['status'],
        }),
        onDone: {
          target: 'ready.done',
          actions: [
            assign({
              task: ({ event }) => event.output,
              liveStatus: ({ event }) => event.output.status,
              error: null,
            }),
            'replayKept',
          ],
        },
        onError: {
          target: 'ready.failed',
          actions: [assign({ error: ({ event }) => event.error }), 'replayKept'],
        },
      },
    },
  },
});
