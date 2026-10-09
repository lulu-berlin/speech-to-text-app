// The live connection to GET /api/events (ADR 0007, ADR 0031): connecting until the first
// snapshot, then live, where the server's events update the state; reconnecting after the
// stream drops, until the next snapshot. Server state is applied as received.
import { assign, fromCallback, setup } from 'xstate';
import { subscribeToEvents } from '../api/client';
import type { Queue, StreamEvent, Task } from '../api/types';
import { isAllowedTransition } from './transitions';

export interface ConnectionContext {
  tasks: Task[];
  queue: Queue | null;
}

export const connectionMachine = setup({
  types: {
    context: {} as ConnectionContext,
    events: {} as StreamEvent,
  },
  actors: {
    // The stream actor reconnects on its own when EventSource gives up (client.ts).
    eventStream: fromCallback<StreamEvent>(({ sendBack }) => subscribeToEvents(sendBack)),
  },
}).createMachine({
  id: 'connection',
  context: { tasks: [], queue: null },
  invoke: { src: 'eventStream' },
  initial: 'connecting',
  // Every snapshot (first connection or reconnect) replaces the whole state.
  on: {
    snapshot: {
      target: '.live',
      actions: assign(({ event }) => ({
        tasks: event.snapshot.tasks,
        queue: event.snapshot.queue,
      })),
    },
  },
  states: {
    connecting: {
      on: { disconnected: 'reconnecting' },
    },
    reconnecting: {
      // A snapshot from the actor's own reconnect lands through the root transition.
      on: { disconnected: 'reconnecting' },
    },
    live: {
      on: {
        disconnected: 'reconnecting',
        'task-updated': {
          actions: assign(({ context, event }) => {
            const previous = context.tasks.find((task) => task.id === event.task.id);
            if (previous === undefined) {
              // A task the state does not hold yet (e.g. a new upload) is added.
              return { tasks: [...context.tasks, event.task] };
            }
            // Server state is applied as received: an update outside the transition table is
            // logged, not rejected, and the next snapshot repairs the gap (ADR 0031).
            if (!isAllowedTransition(previous.status, event.task.status)) {
              console.warn(
                `task-updated for task ${event.task.id} leaves the transition table: ` +
                  `${previous.status} -> ${event.task.status}`,
              );
            }
            return {
              tasks: context.tasks.map((task) => (task.id === event.task.id ? event.task : task)),
            };
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
