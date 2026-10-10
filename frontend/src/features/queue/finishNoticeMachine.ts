// The short notices when a task leaves the queue finished (item 14, ADR 0038): its row
// drops out through the pending filter, and this machine keeps the most recent notices
// visible with a link to the transcript (completed) or to History. The view decides
// which tasks finished (a diff of the live statuses) and sends NOTICE.
import { assign, setup } from 'xstate';

export interface FinishNotice {
  id: number;
  filename: string;
  status: 'COMPLETED' | 'FAILED' | 'CANCELED';
}

export const finishNoticeMachine = setup({
  types: {
    context: {} as { notices: FinishNotice[] },
    events: {} as { type: 'NOTICE'; notice: FinishNotice },
  },
}).createMachine({
  id: 'finishNotice',
  context: { notices: [] },
  on: {
    NOTICE: {
      actions: assign({
        notices: ({ context, event }) => [event.notice, ...context.notices].slice(0, 5),
      }),
    },
  },
});
