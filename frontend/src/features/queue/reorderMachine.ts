// The queue reorder (item 14, ADR 0038): the displayed order of the pending tasks lives
// here. A drag or a menu move applies the new order and PUTs it; a rejected reorder
// (409) restores the previous order and carries the error for display (item 14). The
// live queue reconciles into the order while idle, so new uploads and removals still
// land.
import { assign, fromPromise, setup } from 'xstate';
import { reorderQueue } from '../../shared/api/client';

export interface ReorderInput {
  order: number[];
}

interface ReorderContext {
  order: number[];
  previousOrder: number[];
  error: string | null;
}

export const reorderMachine = setup({
  types: {
    input: {} as ReorderInput,
    context: {} as ReorderContext,
    events: {} as { type: 'SYNC'; order: number[] } | { type: 'MOVE'; id: number; to: number },
  },
  actors: {
    putOrder: fromPromise<number[], number[]>(({ input }) =>
      reorderQueue(input).then((queue) => queue.taskIds),
    ),
  },
  actions: {
    // Follow the server's queue while idle: keep the known ids in their current order,
    // append new ones, drop removed ones.
    reconcile: assign({
      order: ({ context, event }) => {
        if (event.type !== 'SYNC') {
          return context.order;
        }
        const known = context.order.filter((id) => event.order.includes(id));
        const added = event.order.filter((id) => !context.order.includes(id));
        return [...known, ...added];
      },
    }),
    applyMove: assign({
      order: ({ context, event }) => {
        if (event.type !== 'MOVE') {
          return context.order;
        }
        const without = context.order.filter((id) => id !== event.id);
        return [...without.slice(0, event.to), event.id, ...without.slice(event.to)];
      },
      previousOrder: ({ context }) => context.order,
      error: null,
    }),
  },
}).createMachine({
  id: 'reorder',
  context: ({ input }) => ({ order: input.order, previousOrder: input.order, error: null }),
  initial: 'idle',
  states: {
    idle: {
      on: {
        SYNC: { actions: 'reconcile' },
        MOVE: { actions: 'applyMove', target: 'sending' },
      },
    },
    sending: {
      invoke: {
        src: 'putOrder',
        input: ({ context }) => context.order,
        onDone: {
          target: 'idle',
          actions: assign({ order: ({ event }) => event.output, error: null }),
        },
        onError: {
          target: 'idle',
          actions: assign({
            // The server kept its order: restore the one the reorder started from.
            order: ({ context }) => context.previousOrder,
            error: ({ event }) =>
              event.error instanceof Error ? event.error.message : String(event.error),
          }),
        },
      },
    },
  },
});
