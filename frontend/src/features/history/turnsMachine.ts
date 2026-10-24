import { assign, fromPromise, setup } from 'xstate';
import { getTurns } from '../../shared/api/client';
import type { Segment, TurnSpeaker, Turns } from '../../shared/api/types';

// Loads a completed task's turns (ADR 0038: the loading/loaded/failed flow and the retry
// are machine states, not React state). LOAD starts a fetch for a task (again when the
// view moves to another task); a failure keeps its error for the error callout, and RETRY
// fetches the same task again. Item 22's speakers live here too: SPEAKERS replaces the
// names in place (a rename), and RELOAD re-reads /turns (a merge or a reset joined or
// split turns). Item 52: SEGMENTS replaces edited segments in place, matched by id.
export const turnsMachine = setup({
  types: {
    input: {} as { taskId: number },
    context: {} as { taskId: number; turns: Turns | null; error: unknown },
    events: {} as
      | { type: 'LOAD'; taskId: number }
      | { type: 'RETRY' }
      | { type: 'SPEAKERS'; speakers: TurnSpeaker[] }
      | { type: 'SEGMENTS'; segments: Segment[] }
      | { type: 'RELOAD' },
  },
  actors: {
    fetchTurns: fromPromise<Turns, { taskId: number }>(({ input }) => getTurns(input.taskId)),
  },
}).createMachine({
  id: 'turns',
  context: ({ input }) => ({ taskId: input.taskId, turns: null, error: null }),
  initial: 'idle',
  on: {
    LOAD: {
      target: '.loading',
      reenter: true,
      actions: assign({ taskId: ({ event }) => event.taskId, turns: null, error: null }),
    },
  },
  states: {
    idle: {},
    loading: {
      invoke: {
        src: 'fetchTurns',
        input: ({ context }) => ({ taskId: context.taskId }),
        onDone: { target: 'loaded', actions: assign({ turns: ({ event }) => event.output }) },
        onError: { target: 'failed', actions: assign({ error: ({ event }) => event.error }) },
      },
    },
    loaded: {
      on: {
        SPEAKERS: {
          actions: assign({
            turns: ({ context, event }) =>
              context.turns === null
                ? context.turns
                : { ...context.turns, speakers: event.speakers },
          }),
        },
        SEGMENTS: {
          actions: assign({
            turns: ({ context, event }) => {
              if (context.turns === null) {
                return context.turns;
              }
              const edited = new Map(event.segments.map((segment) => [segment.id, segment]));
              return {
                ...context.turns,
                turns: context.turns.turns.map((turn) => ({
                  ...turn,
                  segments: turn.segments.map((segment) => edited.get(segment.id) ?? segment),
                })),
              };
            },
          }),
        },
        RELOAD: 'loading',
      },
    },
    failed: {
      on: { RETRY: { target: 'loading', actions: assign({ error: null }) } },
    },
  },
});
