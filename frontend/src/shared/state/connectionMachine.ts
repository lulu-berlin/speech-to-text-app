// The live connection to GET /api/events (ADR 0007, ADR 0031): connecting until the first
// snapshot, then live. Server state is applied as received.
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
  actions: {
    applySnapshot: assign(({ event }) => ({
      tasks: event.snapshot.tasks,
      queue: event.snapshot.queue,
    })),
  },
}).createMachine({
  id: 'connection',
  context: { tasks: [], queue: null },
  invoke: { src: 'eventStream' },
  initial: 'connecting',
  states: {
    connecting: {
      on: { snapshot: { target: 'live', actions: 'applySnapshot' } },
    },
    live: {},
  },
});
