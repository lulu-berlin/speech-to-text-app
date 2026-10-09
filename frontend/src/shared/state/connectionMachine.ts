// The live connection to GET /api/events (ADR 0007, ADR 0031): connecting until the first
// snapshot, then live, where the server's events update the state. Server state is applied
// as received.
import { assign, fromCallback, setup } from 'xstate';
import { subscribeToEvents } from '../api/client';
import type { Queue, ServerEvent, Task } from '../api/types';

export interface ConnectionContext {
  tasks: Task[];
  queue: Queue | null;
}

export const connectionMachine = setup({
  types: {
    context: {} as ConnectionContext,
    events: {} as ServerEvent,
  },
  actors: {
    eventStream: fromCallback<ServerEvent>(({ sendBack }) => subscribeToEvents(sendBack)),
  },
}).createMachine({
  id: 'connection',
  context: { tasks: [], queue: null },
  invoke: { src: 'eventStream' },
  initial: 'connecting',
  states: {
    connecting: {
      on: {
        snapshot: {
          target: 'live',
          actions: assign(({ event }) => ({
            tasks: event.snapshot.tasks,
            queue: event.snapshot.queue,
          })),
        },
      },
    },
    live: {
      on: {
        'task-updated': {
          actions: assign({
            tasks: ({ context, event }) =>
              context.tasks.map((task) => (task.id === event.task.id ? event.task : task)),
          }),
        },
        'task-progress': {
          actions: assign({
            tasks: ({ context, event }) =>
              context.tasks.map((task) =>
                task.id === event.progress.id
                  ? {
                      ...task,
                      transcriptionProgress: event.progress.transcriptionProgress,
                      diarizationProgress: event.progress.diarizationProgress,
                    }
                  : task,
              ),
          }),
        },
        'queue-updated': {
          actions: assign({ queue: ({ event }) => event.queue }),
        },
        'task-deleted': {
          actions: assign({
            tasks: ({ context, event }) => context.tasks.filter((task) => task.id !== event.id),
          }),
        },
      },
    },
  },
});
