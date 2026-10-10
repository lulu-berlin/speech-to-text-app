import { assign, fromPromise, setup } from 'xstate';
import { mergeSpeakers, resetMerges } from '../../shared/api/client';
import type { ApiError, Speakers } from '../../shared/api/types';

interface MergeInput {
  taskId: number;
  group: number[];
  into: number;
}

// The speakers panel's merge flow (item 22, ADR 0038): the form is closed until OPEN; in
// it the user checks the group and picks the survivor among the checked speakers; REQUEST
// opens the confirmation (only for a group of two or more with a survivor in it); only
// CONFIRM sends. A refusal returns to the form with the typed error; success closes it and
// runs the view's `merged` action (which refetches /turns, since turns join).
export const speakerMergeMachine = setup({
  types: {
    input: {} as { taskId: number },
    context: {} as {
      taskId: number;
      group: number[];
      into: number | null;
      error: ApiError | null;
    },
    events: {} as
      | { type: 'OPEN' }
      | { type: 'CLOSE' }
      | { type: 'TOGGLE'; speakerId: number }
      | { type: 'KEEP'; speakerId: number }
      | { type: 'REQUEST' }
      | { type: 'CONFIRM' }
      | { type: 'CANCEL' },
  },
  actions: {
    merged: (_, _speakers: Speakers) => {},
  },
  guards: {
    complete: ({ context }) =>
      context.group.length >= 2 && context.into !== null && context.group.includes(context.into),
  },
  actors: {
    merge: fromPromise<Speakers, MergeInput>(({ input }) =>
      mergeSpeakers(input.taskId, input.group, input.into),
    ),
  },
}).createMachine({
  id: 'speakerMerge',
  context: ({ input }) => ({ taskId: input.taskId, group: [], into: null, error: null }),
  initial: 'closed',
  states: {
    closed: {
      on: {
        OPEN: {
          target: 'editing',
          actions: assign({ group: [], into: null, error: null }),
        },
      },
    },
    editing: {
      on: {
        CLOSE: 'closed',
        TOGGLE: {
          actions: assign(({ context, event }) => {
            const group = context.group.includes(event.speakerId)
              ? context.group.filter((id) => id !== event.speakerId)
              : [...context.group, event.speakerId];
            // The survivor is one of the group: the first checked speaker until one is picked.
            const into =
              context.into !== null && group.includes(context.into)
                ? context.into
                : (group[0] ?? null);
            return { group, into };
          }),
        },
        KEEP: { actions: assign({ into: ({ event }) => event.speakerId }) },
        REQUEST: { guard: 'complete', target: 'confirming' },
      },
    },
    confirming: {
      on: {
        CONFIRM: 'sending',
        CANCEL: 'editing',
      },
    },
    sending: {
      invoke: {
        src: 'merge',
        input: ({ context }) => ({
          taskId: context.taskId,
          group: [...context.group].sort((a, b) => a - b),
          into: context.into!,
        }),
        onDone: {
          target: 'closed',
          actions: [{ type: 'merged', params: ({ event }) => event.output }],
        },
        onError: {
          target: 'editing',
          actions: assign({ error: ({ event }) => event.error as ApiError }),
        },
      },
    },
  },
});

// "Reset all merges" (item 22): one request; success runs the view's `reset` action (which
// refetches /turns), a refusal holds the typed error for an inline message.
export const resetMergesMachine = setup({
  types: {
    input: {} as { taskId: number },
    context: {} as { taskId: number; error: ApiError | null },
    events: {} as { type: 'RESET' },
  },
  actions: {
    reset: (_, _speakers: Speakers) => {},
  },
  actors: {
    reset: fromPromise<Speakers, { taskId: number }>(({ input }) => resetMerges(input.taskId)),
  },
}).createMachine({
  id: 'resetMerges',
  context: ({ input }) => ({ taskId: input.taskId, error: null }),
  initial: 'idle',
  states: {
    idle: { on: { RESET: 'sending' } },
    sending: {
      invoke: {
        src: 'reset',
        input: ({ context }) => ({ taskId: context.taskId }),
        onDone: {
          target: 'idle',
          actions: [
            assign({ error: null }),
            { type: 'reset', params: ({ event }) => event.output },
          ],
        },
        onError: {
          target: 'idle',
          actions: assign({ error: ({ event }) => event.error as ApiError }),
        },
      },
    },
  },
});
