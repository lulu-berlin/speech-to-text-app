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
} from './types';

const json = async <T>(response: Response): Promise<T> => {
  if (!response.ok) {
    // A body that is not the contract's error shape (e.g. a proxy's 502 page) falls back to
    // the HTTP status and status text.
    const body = (await response.json().catch(() => null)) as ApiErrorBody | null;
    const code = body?.error ?? String(response.status);
    const message = body?.message ?? response.statusText;
    throw new ApiError(code, message, body?.taskId);
  }
  return (await response.json()) as T;
};

export async function listTasks(): Promise<TaskList> {
  const response = await fetch('/api/tasks');
  return json<TaskList>(response);
}

export async function getTask(id: number): Promise<Task> {
  const response = await fetch(`/api/tasks/${id}`);
  return json<Task>(response);
}

// Item 12 contract: POST /api/tasks, multipart with a `file` field.
export async function uploadTask(file: File): Promise<Task> {
  const form = new FormData();
  form.append('file', file);
  const response = await fetch('/api/tasks', { method: 'POST', body: form });
  return json<Task>(response);
}

// Item 12 contract: POST /api/tasks/{id}/<command> -> 200 Task, or an error (409 for an
// illegal transition).
export async function runTaskCommand(id: number, command: string): Promise<Task> {
  const response = await fetch(`/api/tasks/${id}/${command}`, { method: 'POST' });
  return json<Task>(response);
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
