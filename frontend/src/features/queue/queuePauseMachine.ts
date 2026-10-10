// The queue pause toggle (item 14, ADR 0038): a submit flow — POST /api/queue/pause or
// /resume. The returned queue sits in context; the view applies it to the live state
// (the server's own queue-updated carries the same queue, so applying it twice is
// harmless). The switch shows the connection's `paused` flag and is disabled while
// sending; a failure leaves the flag as it was.
import { assign, fromPromise, setup } from 'xstate';
import { setQueuePaused } from '../../shared/api/client';
import type { Queue } from '../../shared/api/types';

export const queuePauseMachine = setup({
  types: {
    context: {} as { queue: Queue | null; error: unknown },
    events: {} as { type: 'TOGGLE'; paused: boolean },
  },
  actors: {
    set: fromPromise<Queue, boolean>(({ input }) => setQueuePaused(input)),
  },
}).createMachine({
  id: 'queuePause',
  context: { queue: null, error: null },
  initial: 'idle',
  states: {
    idle: {
      on: {
        TOGGLE: { target: 'sending', actions: assign({ error: null }) },
      },
    },
    sending: {
      invoke: {
        src: 'set',
        input: ({ event }) => (event.type === 'TOGGLE' ? event.paused : false),
        onDone: {
          target: 'idle',
          actions: assign({ queue: ({ event }) => event.output }),
        },
        onError: {
          target: 'idle',
          actions: assign({ error: ({ event }) => event.error }),
        },
      },
    },
  },
});
