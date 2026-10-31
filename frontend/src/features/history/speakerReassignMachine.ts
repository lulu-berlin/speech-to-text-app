import { assign, fromPromise, setup } from 'xstate';
import { reassignSegments } from '../../shared/api/client';
import type { ApiError, Speakers } from '../../shared/api/types';

interface ReassignInput {
  taskId: number;
  segmentIds: number[];
  speakerId: number | null;
}

// The transcript view's reassignment flow (item 73's amendment, ADR 0038: a UI flow with steps is a
// machine): SEND holds the selection and its target (null for a new speaker) and, when the task has
// merges — which the move makes permanent — waits for CONFIRM first; CANCEL sends nothing. A refusal
// holds its typed error for the bar; success runs the view's `reassigned` action, which reads /turns
// again and leaves the mode.
export const speakerReassignMachine = setup({
  types: {
    input: {} as { taskId: number },
    context: {} as {
      taskId: number;
      segmentIds: number[];
      speakerId: number | null;
      error: ApiError | null;
    },
    events: {} as
      | { type: 'SEND'; segmentIds: number[]; speakerId: number | null; confirm: boolean }
      | { type: 'CONFIRM' }
      | { type: 'CANCEL' },
  },
  actions: {
    reassigned: (_, _speakers: Speakers) => {},
    // Holds the request until it is sent (now, or after CONFIRM).
    request: assign(({ event }) =>
      event.type === 'SEND'
        ? { segmentIds: event.segmentIds, speakerId: event.speakerId, error: null }
        : {},
    ),
  },
  actors: {
    send: fromPromise<Speakers, ReassignInput>(({ input }) =>
      reassignSegments(input.taskId, input.segmentIds, input.speakerId),
    ),
  },
}).createMachine({
  id: 'speakerReassign',
  context: ({ input }) => ({ taskId: input.taskId, segmentIds: [], speakerId: null, error: null }),
  initial: 'idle',
  states: {
    idle: {
      on: {
        SEND: [
          { guard: ({ event }) => event.confirm, target: 'confirming', actions: 'request' },
          { target: 'sending', actions: 'request' },
        ],
      },
    },
    confirming: {
      on: { CONFIRM: 'sending', CANCEL: 'idle' },
    },
    sending: {
      invoke: {
        src: 'send',
        input: ({ context }) => ({
          taskId: context.taskId,
          segmentIds: context.segmentIds,
          speakerId: context.speakerId,
        }),
        onDone: {
          target: 'idle',
          actions: [
            assign({ error: null }),
            { type: 'reassigned', params: ({ event }) => event.output },
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
