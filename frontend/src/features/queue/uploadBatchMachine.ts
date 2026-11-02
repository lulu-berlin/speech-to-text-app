// The batch uploader (item 14, ADR 0038): the dropped files are rows in context; while
// one uploads, the rest wait; each upload reports its progress through PROGRESS events
// and finishes as done (with the created task) or with an inline error (a duplicate
// names the existing task). The unsupported-format check happens here, at row creation.
import { assign, fromCallback, setup } from 'xstate';
import { uploadTask } from '../../shared/api/client';
import type { UploadOptions } from '../../shared/api/client';
import { ApiError, errorMessage, isTransportError } from '../../shared/api/types';
import type { Task } from '../../shared/api/types';
import { isSupportedFormat } from './uploadValidation';

// The grace before a request that failed at the transport while the stream stayed live becomes
// the row's error (product behavior, 2–3 s; not a request timeout) — item 87's second review.
const STALL_GRACE_MS = 2500;

// One file of the batch, with the options it was dropped under (ADR 0038: the rows are
// machine context, not component state).
export interface UploadRow {
  id: number;
  file: File;
  options: UploadOptions;
  status: 'queued' | 'uploading' | 'done' | 'error';
  // Upload progress 0..1 while uploading (bytes sent).
  progress: number;
  error: string | null;
  // The task a successful upload created; the row leaves once it is in the live state.
  taskId?: number;
  // The existing task a duplicate upload names; the row links to it.
  duplicateTaskId?: number;
}

// A drop: each file with the options it was dropped under.
interface DroppedFile {
  file: File;
  options: UploadOptions;
}

interface UploadBatchContext {
  rows: UploadRow[];
  nextId: number;
  // The live stream's state (item 87): no upload starts or continues while it is down.
  live: boolean;
  // The app's message of a request that failed at the transport while the stream stayed live
  // (item 87's second review): published as the row's error after the grace.
  stalledError: string | null;
}

type UploadEvent =
  | { type: 'DROP'; files: DroppedFile[] }
  | { type: 'CONNECTION'; live: boolean }
  | { type: 'PROGRESS'; id: number; fraction: number }
  | { type: 'UPLOAD_DONE'; id: number; task: Task }
  | { type: 'UPLOAD_DUP'; id: number; duplicateTaskId: number }
  // `transport` marks a request that never reached the service (item 87): the row waits and
  // is sent again when the live stream returns, instead of becoming an error row.
  | { type: 'UPLOAD_ERROR'; id: number; message: string; transport: boolean };

function inFlightRow(rows: UploadRow[]): UploadRow {
  const row = rows.find((candidate) => candidate.status === 'uploading');
  if (row === undefined) {
    // The uploading state marks exactly the first queued row before its invoker starts.
    throw new Error('upload batch has no uploading row');
  }
  return row;
}

export const uploadBatchMachine = setup({
  types: {
    context: {} as UploadBatchContext,
    events: {} as UploadEvent,
    input: {} as { live: boolean },
  },
  actors: {
    // The in-flight upload: reports progress and its result back to the machine
    // (callback actors can sendBack mid-flight; promise actors cannot).
    uploadNext: fromCallback<UploadEvent, { row: UploadRow }>(({ sendBack, input }) => {
      let stopped = false;
      // The machine's cleanup runs on a transition out of `uploading` (the connection
      // dropped): the request is aborted, so the row stops waiting on one that never answers.
      const controller = new AbortController();
      void uploadTask(
        input.row.file,
        input.row.options,
        (sent, total) => {
          if (!stopped) {
            sendBack({
              type: 'PROGRESS',
              id: input.row.id,
              fraction: total > 0 ? sent / total : 0,
            });
          }
        },
        controller.signal,
      ).then(
        (task) => {
          if (!stopped) {
            sendBack({ type: 'UPLOAD_DONE', id: input.row.id, task });
          }
        },
        (error) => {
          if (stopped) {
            return;
          }
          if (
            error instanceof ApiError &&
            error.code === 'duplicate-audio' &&
            error.taskId !== undefined
          ) {
            sendBack({ type: 'UPLOAD_DUP', id: input.row.id, duplicateTaskId: error.taskId });
            return;
          }
          sendBack({
            type: 'UPLOAD_ERROR',
            id: input.row.id,
            message: errorMessage(error),
            transport: isTransportError(error),
          });
        },
      );
      return () => {
        stopped = true;
        controller.abort();
      };
    }),
  },
  actions: {
    // Turns the dropped files into rows: an unsupported format (item 12 allow-list) is
    // rejected client-side without an upload.
    enqueueRows: assign(({ context, event }) => {
      if (event.type !== 'DROP') {
        return {};
      }
      const created = event.files.map(({ file, options }, index) => {
        const supported = isSupportedFormat(file.name);
        return {
          id: context.nextId + index,
          file,
          options,
          status: supported ? ('queued' as const) : ('error' as const),
          progress: 0,
          error: supported ? null : 'Unsupported file format',
        };
      });
      return { rows: [...context.rows, ...created], nextId: context.nextId + event.files.length };
    }),
    setProgress: assign({
      rows: ({ context, event }) =>
        event.type === 'PROGRESS'
          ? context.rows.map((row) =>
              row.id === event.id ? { ...row, progress: event.fraction } : row,
            )
          : context.rows,
    }),
    markDone: assign({
      rows: ({ context, event }) =>
        event.type === 'UPLOAD_DONE'
          ? context.rows.map((row) =>
              row.id === event.id
                ? { ...row, status: 'done' as const, taskId: event.task.id }
                : row,
            )
          : context.rows,
    }),
    markDuplicate: assign({
      rows: ({ context, event }) =>
        event.type === 'UPLOAD_DUP'
          ? context.rows.map((row) =>
              row.id === event.id
                ? {
                    ...row,
                    status: 'error' as const,
                    error: null,
                    duplicateTaskId: event.duplicateTaskId,
                  }
                : row,
            )
          : context.rows,
    }),
    markError: assign({
      rows: ({ context, event }) =>
        event.type === 'UPLOAD_ERROR'
          ? context.rows.map((row) =>
              row.id === event.id
                ? { ...row, status: 'error' as const, error: event.message }
                : row,
            )
          : context.rows,
    }),
    setLive: assign({
      live: ({ context, event }) => (event.type === 'CONNECTION' ? event.live : context.live),
    }),
    rememberError: assign({
      stalledError: ({ event }) => (event.type === 'UPLOAD_ERROR' ? event.message : null),
    }),
    // The stream stayed live through the grace: the cut-off row is an error with the app's
    // message, and the batch behind it goes on.
    markStalled: assign({
      rows: ({ context }) => {
        const stuck = context.rows.find((row) => row.status === 'queued');
        if (stuck === undefined) {
          return context.rows;
        }
        return context.rows.map((row) =>
          row.id === stuck.id
            ? { ...row, status: 'error' as const, error: context.stalledError }
            : row,
        );
      },
      stalledError: null,
    }),
    // The row whose request the drop cut off goes back to the queue with no progress, so its
    // resend starts the file over.
    requeueInFlight: assign({
      rows: ({ context }) =>
        context.rows.map((row) =>
          row.status === 'uploading' ? { ...row, status: 'queued' as const, progress: 0 } : row,
        ),
    }),
  },
}).createMachine({
  id: 'uploadBatch',
  context: ({ input }) => ({ rows: [], nextId: 0, live: input.live, stalledError: null }),
  initial: 'idle',
  states: {
    idle: {
      on: {
        DROP: { actions: 'enqueueRows', target: 'next' },
        CONNECTION: { actions: 'setLive' },
      },
    },
    // The live stream is down: rows not yet sent (or cut off) wait here; no upload starts
    // until the connection is back.
    waiting: {
      // A real drop keeps the batch here until the stream returns. A request that failed at the
      // transport while the stream stayed live would otherwise wait forever: after a short grace
      // (product behavior, not a request timeout) its row becomes an error and the batch goes on.
      after: {
        [STALL_GRACE_MS]: {
          guard: ({ context }) => context.live,
          target: 'next',
          actions: 'markStalled',
        },
      },
      on: {
        DROP: { actions: 'enqueueRows' },
        CONNECTION: [
          { guard: ({ event }) => event.live, target: 'next', actions: 'setLive' },
          { actions: 'setLive' },
        ],
      },
    },
    // While a row uploads, new drops are appended and progress lands on the row.
    uploading: {
      entry: assign({
        rows: ({ context }) => {
          const firstQueued = context.rows.find((row) => row.status === 'queued');
          if (firstQueued === undefined) {
            return context.rows;
          }
          return context.rows.map((row) =>
            row.id === firstQueued.id ? { ...row, status: 'uploading' as const } : row,
          );
        },
      }),
      invoke: {
        src: 'uploadNext',
        input: ({ context }) => ({ row: inFlightRow(context.rows) }),
      },
      on: {
        DROP: { actions: 'enqueueRows' },
        CONNECTION: [
          {
            guard: ({ event }) => !event.live,
            target: 'waiting',
            actions: ['setLive', 'requeueInFlight'],
          },
          { actions: 'setLive' },
        ],
        PROGRESS: { actions: 'setProgress' },
        UPLOAD_DONE: { target: 'next', actions: 'markDone' },
        UPLOAD_DUP: { target: 'next', actions: 'markDuplicate' },
        UPLOAD_ERROR: [
          {
            guard: ({ event }) => event.transport,
            target: 'waiting',
            actions: ['requeueInFlight', 'rememberError'],
          },
          { target: 'next', actions: 'markError' },
        ],
      },
    },
    // After any change, start the first queued row again, or rest.
    next: {
      always: [
        {
          guard: ({ context }) =>
            context.live && context.rows.some((row) => row.status === 'queued'),
          target: 'uploading',
        },
        {
          guard: ({ context }) =>
            !context.live && context.rows.some((row) => row.status === 'queued'),
          target: 'waiting',
        },
        { target: 'idle' },
      ],
    },
  },
});
