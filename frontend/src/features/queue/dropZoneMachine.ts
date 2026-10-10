import { assign, setup } from 'xstate';

// Whether files are being dragged over the drop zone (item 25, ADR 0038). Drag events
// bubble from the zone's children (the mascot in the empty state), so entering a child
// counts as one more enter; the zone is left once every enter has been matched by a leave,
// or on a drop.
export const dropZoneMachine = setup({
  types: {
    context: {} as { depth: number },
    events: {} as { type: 'ENTER' } | { type: 'LEAVE' } | { type: 'DROP' },
  },
  guards: {
    lastLeave: ({ context }) => context.depth <= 1,
  },
}).createMachine({
  id: 'dropZone',
  context: { depth: 0 },
  initial: 'idle',
  states: {
    idle: {
      on: { ENTER: { target: 'over', actions: assign({ depth: 1 }) } },
    },
    over: {
      on: {
        ENTER: { actions: assign({ depth: ({ context }) => context.depth + 1 }) },
        LEAVE: [
          { guard: 'lastLeave', target: 'idle', actions: assign({ depth: 0 }) },
          { actions: assign({ depth: ({ context }) => context.depth - 1 }) },
        ],
        DROP: { target: 'idle', actions: assign({ depth: 0 }) },
      },
    },
  },
});
