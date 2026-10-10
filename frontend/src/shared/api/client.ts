import { ApiError } from './types';
import type {
  ApiErrorBody,
  Queue,
  ServerEvent,
  StreamEvent,
  Snapshot,
  Task,
  TaskList,
  TaskProgress,
  Turns,
} from './types';

const json = async <T>(response: Response): Promise<T> => {
  if (!response.ok) {
    // A body that is not the contract's error shape (e.g. a proxy's 502 page) falls back to
    // the HTTP status and status text.
    const body = (await response.json().catch(() => null)) as ApiErrorBody | null;
    throw apiError(body, response.status, response.statusText);
  }
  return (await response.json()) as T;
};

// The typed error for a non-2xx response: the body's error code and message, falling
// back to the HTTP status and status text when the body is not the contract's error
// shape (e.g. a proxy's 502 page). Shared by the fetch and XHR paths.
function apiError(body: ApiErrorBody | null, status: number, statusText: string): ApiError {
  const code = body?.error ?? String(status);
  const message = body?.message ?? statusText;
  return new ApiError(code, message, body?.taskId);
}

export async function listTasks(): Promise<TaskList> {
  const response = await fetch('/api/tasks');
  return json<TaskList>(response);
}

export async function getTask(id: number): Promise<Task> {
  const response = await fetch(`/api/tasks/${id}`);
  return json<Task>(response);
}

// Item 12 contract: GET /api/tasks/{id}/turns (docs/api/turns.json).
export async function getTurns(id: number): Promise<Turns> {
  const response = await fetch(`/api/tasks/${id}/turns`);
  return json<Turns>(response);
}

// The upload options of one file (item 12 contract): language from docs/api/languages.json,
// speakers auto|off|<n> (1–20), maxSpeakers 1–20.
export interface UploadOptions {
  language: string;
  speakers: string;
  maxSpeakers: number;
}

// Item 12 contract: POST /api/tasks, multipart with a `file` field and the batch options.
// XMLHttpRequest, because fetch has no upload progress (item 14); this stays the only
// place that talks to POST /api/tasks. `onProgress` reports uploaded bytes while sending.
export function uploadTask(
  file: File,
  options: UploadOptions,
  onProgress?: (sentBytes: number, totalBytes: number) => void,
): Promise<Task> {
  const { promise, resolve, reject } = Promise.withResolvers<Task>();
  const form = new FormData();
  form.append('file', file);
  form.append('language', options.language);
  form.append('speakers', options.speakers);
  form.append('maxSpeakers', String(options.maxSpeakers));
  const xhr = new XMLHttpRequest();
  xhr.open('POST', '/api/tasks');
  xhr.responseType = 'json';
  xhr.upload.onprogress = (event) => {
    if (event.lengthComputable && onProgress !== undefined) {
      onProgress(event.loaded, event.total);
    }
  };
  xhr.onload = () => {
    if (xhr.status >= 200 && xhr.status < 300) {
      resolve(xhr.response as Task);
    } else {
      const body = xhr.response as ApiErrorBody | null;
      reject(apiError(body, xhr.status, xhr.statusText));
    }
  };
  xhr.onerror = () => reject(new ApiError(String(xhr.status || 0), 'Network error'));
  xhr.send(form);
  return promise;
}

// Item 12 contract: POST /api/tasks/{id}/<command> -> 200 Task, or an error (409 for an
// illegal transition).
export async function runTaskCommand(id: number, command: string): Promise<Task> {
  const response = await fetch(`/api/tasks/${id}/${command}`, { method: 'POST' });
  return json<Task>(response);
}

// Item 12 contract: DELETE /api/tasks/{id} -> 204 without a body. Delete is not a status
// transition, so it works on any task; an error body still becomes a typed ApiError.
export async function deleteTask(id: number): Promise<void> {
  const response = await fetch(`/api/tasks/${id}`, { method: 'DELETE' });
  if (!response.ok) {
    await json<never>(response); // throws the typed error
  }
}

// Opens GET /api/events and passes each named event to onEvent, plus `disconnected` when
// the connection drops. EventSource retries a dropped connection by itself, but gives up
// for good on an HTTP error, so this opens a new stream in that case. Returns a function
// that closes the stream and cancels any pending reconnect.
export function subscribeToEvents(
  onEvent: (event: StreamEvent) => void,
  reconnectMs = 1000,
): () => void {
  let source: EventSource | undefined;
  let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
  let stopped = false;

  const open = () => {
    const next = new EventSource('/api/events');
    source = next;
    const listen = <T>(name: ServerEvent['type'], toEvent: (data: T) => ServerEvent) => {
      next.addEventListener(name, (message) => onEvent(toEvent(JSON.parse(message.data) as T)));
    };
    listen<Snapshot>('snapshot', (snapshot) => ({ type: 'snapshot', snapshot }));
    listen<Task>('task-updated', (task) => ({ type: 'task-updated', task }));
    listen<TaskProgress>('task-progress', (progress) => ({ type: 'task-progress', progress }));
    listen<Queue>('queue-updated', (queue) => ({ type: 'queue-updated', queue }));
    listen<{ id: number }>('task-deleted', ({ id }) => ({ type: 'task-deleted', id }));
    next.addEventListener('error', () => {
      const closed = next.readyState === EventSource.CLOSED;
      onEvent({ type: 'disconnected', closed });
      if (closed && !stopped) {
        next.close();
        reconnectTimer = setTimeout(open, reconnectMs);
      }
    });
  };

  open();
  return () => {
    stopped = true;
    clearTimeout(reconnectTimer);
    source?.close();
  };
}
