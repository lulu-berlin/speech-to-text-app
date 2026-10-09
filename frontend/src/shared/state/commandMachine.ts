// One command invocation (ADR 0031): idle until START, then sending, then done or failed.
// It only starts when docs/api/task-transitions.json allows the command for the task's
// current status, so no request is sent otherwise.
import { assign, fromPromise, setup } from 'xstate';
import { runTaskCommand } from '../api/client';
import type { Task, TaskStatus } from '../api/types';
import { isAllowedCommand } from './transitions';
import type { TaskCommand } from './transitions';

export interface CommandInput {
  command: TaskCommand;
  taskId: number;
  status: TaskStatus;
}

export interface CommandContext extends CommandInput {
  task: Task | null;
  error: unknown;
}

export const commandMachine = setup({
  types: {
    input: {} as CommandInput,
    context: {} as CommandContext,
    events: {} as { type: 'START' },
  },
  guards: {
    allowed: ({ context }) => isAllowedCommand(context.command, context.status),
  },
  actors: {
    run: fromPromise<Task, { taskId: number; command: TaskCommand }>(({ input }) =>
      runTaskCommand(input.taskId, input.command),
    ),
  },
}).createMachine({
  id: 'command',
  context: ({ input }) => ({ ...input, task: null, error: null }),
  initial: 'idle',
  states: {
    idle: {
      on: { START: { guard: 'allowed', target: 'sending' } },
    },
    sending: {
      invoke: {
        src: 'run',
        input: ({ context }) => ({ taskId: context.taskId, command: context.command }),
        onDone: { target: 'done', actions: assign({ task: ({ event }) => event.output }) },
        onError: { target: 'failed', actions: assign({ error: ({ event }) => event.error }) },
      },
    },
    done: {},
    failed: {},
  },
});
