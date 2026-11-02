// The queue reorder (item 14, ADR 0038): the displayed order of the pending tasks lives
// here. A drag or a menu move applies the new order and PUTs it; a rejected reorder
// (409) restores the previous order and carries the error for display (item 14). The
// live queue reconciles into the order while idle, so new uploads and removals still
// land.
import { assign, fromPromise, setup } from 'xstate';
import { reorderQueue } from '../../shared/api/client';
import { errorMessage } from '../../shared/api/types';

export interface ReorderInput {
  order: number[];
}

/**
 * The pending ids in the order the queue shows them: the queue's own order first, then the
 * ids only the current display knows, then anything left over by id. The displayed order
 * is the pending set, so a snapshot that omits a task cannot hide its row (roadmap item
 * 84).
 */
function rankedOrder(queueOrder: number[], localOrder: number[], pending: number[]): number[] {
  const rank = new Map<number, number>();
  queueOrder.forEach((id, index) => rank.set(id, index));
  const base = queueOrder.length;
  localOrder.forEach((id, index) => {
    if (!rank.has(id)) rank.set(id, base + index);
  });
  const place = (id: number): number => rank.get(id) ?? Number.MAX_SAFE_INTEGER;
  return [...pending].sort((a, b) => place(a) - place(b) || a - b);
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
    events: {} as
      | { type: 'SYNC'; order: number[]; pending: number[] }
      | { type: 'MOVE'; id: number; to: number },
  },
  actors: {
    putOrder: fromPromise<number[], number[]>(({ input }) =>
      reorderQueue(input).then((queue) => queue.taskIds),
    ),
  },
  actions: {
    // Follow the server's queue while idle: the displayed order is exactly the pending
    // tasks — the server's own set (`TaskRepository.pendingOrderedIds`) — in the queue's
    // order, and a fresh queue state clears a reorder error (item 14). The server builds
    // a queue snapshot before it emits it, so two uploads' snapshots can cross and an
    // older one arrives last (roadmap item 84): a task this snapshot omits but the live
    // state still holds as pending keeps its row, so a reorder never sends the server a
    // set it would refuse. A task that finished leaves at once, whatever the snapshot
    // says: the server announces the task before the queue.
    reconcile: assign({
      order: ({ context, event }) => {
        if (event.type !== 'SYNC') {
          return context.order;
        }
        return rankedOrder(event.order, context.order, event.pending);
      },
      error: null,
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
            error: ({ event }) => errorMessage(event.error),
          }),
        },
      },
    },
  },
});
