import { assign, fromPromise, setup } from 'xstate';
import { renameSpeaker } from '../../shared/api/client';
import type { ApiError, Speakers } from '../../shared/api/types';

interface RenameInput {
  taskId: number;
  speakerId: number;
  name: string;
}

// One speaker's name field in the speakers panel (item 22, ADR 0038): the draft the user
// types, the stored name, and the save flow. A save sends the trimmed draft (the API stores
// names as sent, user decision 2026-10-10); an unchanged name sends nothing. A rejected
// save keeps the stored name and holds the typed error for an inline message. The view
// provides `renamed`, which receives the task's speakers from the 200.
export const speakerRenameMachine = setup({
  types: {
    input: {} as { taskId: number; speakerId: number; stored: string },
    context: {} as {
      taskId: number;
      speakerId: number;
      stored: string;
      draft: string;
      error: ApiError | null;
    },
    events: {} as
      { type: 'EDIT'; value: string } | { type: 'SAVE' } | { type: 'STORED'; name: string },
  },
  actions: {
    renamed: (_, _speakers: Speakers) => {},
  },
  guards: {
    changed: ({ context }) => context.draft.trim() !== context.stored,
  },
  actors: {
    rename: fromPromise<Speakers, RenameInput>(({ input }) =>
      renameSpeaker(input.taskId, input.speakerId, input.name),
    ),
  },
}).createMachine({
  id: 'speakerRename',
  context: ({ input }) => ({ ...input, draft: input.stored, error: null }),
  initial: 'idle',
  on: {
    // The stored name changed elsewhere (a reset, an event): the field follows it.
    STORED: { actions: assign(({ event }) => ({ stored: event.name, draft: event.name })) },
  },
  states: {
    idle: {
      on: {
        EDIT: { actions: assign({ draft: ({ event }) => event.value }) },
        SAVE: [
          { guard: 'changed', target: 'sending' },
          { actions: assign({ draft: ({ context }) => context.stored }) },
        ],
      },
    },
    sending: {
      invoke: {
        src: 'rename',
        input: ({ context }) => ({
          taskId: context.taskId,
          speakerId: context.speakerId,
          name: context.draft.trim(),
        }),
        onDone: {
          target: 'idle',
          actions: [
            assign(({ context }) => ({
              stored: context.draft.trim(),
              draft: context.draft.trim(),
              error: null,
            })),
            { type: 'renamed', params: ({ event }) => event.output },
          ],
        },
        onError: {
          target: 'idle',
          actions: assign(({ context, event }) => ({
            draft: context.stored,
            error: event.error as ApiError,
          })),
        },
      },
    },
  },
});
