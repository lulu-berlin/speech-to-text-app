import { ApiError } from './types';
import type {
  ApiErrorBody,
  Queue,
  ServerEvent,
  StreamEvent,
  Segment,
  Settings,
  SettingsNotice,
  SettingsUpdate,
  Snapshot,
  Speakers,
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

// Item 32 contract: GET /api/settings (docs/api/settings.json): the stored settings and the
// devices to choose from.
export async function getSettings(): Promise<Settings> {
  const response = await fetch('/api/settings');
  return json<Settings>(response);
}

// Item 32 contract: DELETE /api/settings/device-fallback -> 200 Settings, the CPU-fallback
// notice cleared.
export async function dismissDeviceFallback(): Promise<Settings> {
  const response = await fetch('/api/settings/device-fallback', { method: 'DELETE' });
  return json<Settings>(response);
}

// Item 72 contract (ADR 0062): DELETE /api/settings -> 200 Settings, the defaults restored in
// one write that also clears the CPU-fallback notice.
export async function resetSettings(): Promise<Settings> {
  const response = await fetch('/api/settings', { method: 'DELETE' });
  return json<Settings>(response);
}

// Item 32 contract: PUT /api/settings with the settings as a whole -> 200 Settings,
// or 400 with the matching error code for the first invalid field.
export async function saveSettings(settings: SettingsUpdate): Promise<Settings> {
  const response = await fetch('/api/settings', {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(settings),
  });
  return json<Settings>(response);
}

// Item 22 contract: PUT /api/tasks/{id}/speakers/{speakerId} with { speaker } -> 200 Speakers;
// a null name resets it to "Speaker N". The server stores the name as sent, so callers trim.
export async function renameSpeaker(
  id: number,
  speakerId: number,
  speaker: string | null,
): Promise<Speakers> {
  const response = await fetch(`/api/tasks/${id}/speakers/${speakerId}`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ speaker }),
  });
  return json<Speakers>(response);
}

// Item 52 contract: PUT /api/tasks/{id}/segments/{segmentId} with { text } -> 200 Turns (the
// same body /turns returns, the edited segment already updated); 409 not-completed and 404
// not-found are the refusals.
export async function editSegment(id: number, segmentId: number, text: string): Promise<Turns> {
  const response = await fetch(`/api/tasks/${id}/segments/${segmentId}`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ text }),
  });
  return json<Turns>(response);
}

// Item 52 / ADR 0061: POST /api/tasks/{id}/segments/{segmentId}/revert puts whisper's original
// text back and answers 200 Turns, like an edit; 409 not-completed and 404 not-found are the
// refusals.
export async function revertSegment(id: number, segmentId: number): Promise<Turns> {
  const response = await fetch(`/api/tasks/${id}/segments/${segmentId}/revert`, { method: 'POST' });
  return json<Turns>(response);
}

// Item 22 contract: POST /api/tasks/{id}/speakers/merge with { group, into } -> 200 Speakers
// (the effective speakers); 400 invalid-merge for a group the server refuses.
export async function mergeSpeakers(id: number, group: number[], into: number): Promise<Speakers> {
  const response = await fetch(`/api/tasks/${id}/speakers/merge`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ group, into }),
  });
  return json<Speakers>(response);
}

// Item 22 contract: DELETE /api/tasks/{id}/speakers/merges ("Reset all merges") -> 200 Speakers.
export async function resetMerges(id: number): Promise<Speakers> {
  const response = await fetch(`/api/tasks/${id}/speakers/merges`, { method: 'DELETE' });
  return json<Speakers>(response);
}

// Item 73's amendment: POST /api/tasks/{id}/speakers/reassign with { segmentIds, speakerId }
// (null = a new speaker) -> 200 Speakers, the new speaker included when one was made.
export async function reassignSegments(
  id: number,
  segmentIds: number[],
  speakerId: number | null,
): Promise<Speakers> {
  const response = await fetch(`/api/tasks/${id}/speakers/reassign`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ segmentIds, speakerId }),
  });
  return json<Speakers>(response);
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

// Item 12 contract: DELETE /api/tasks/{id} -> 204 without a body; the task is gone.
export async function deleteTask(id: number): Promise<void> {
  const response = await fetch(`/api/tasks/${id}`, { method: 'DELETE' });
  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as ApiErrorBody | null;
    throw apiError(body, response.status, response.statusText);
  }
}

// Item 12 contract: PUT /api/queue/order with the full ordered list of pending task
// ids; 200 Queue, or 409 reorder-mismatch for a different set.
export async function reorderQueue(taskIds: number[]): Promise<Queue> {
  const response = await fetch('/api/queue/order', {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ taskIds }),
  });
  return json<Queue>(response);
}

// Item 12 contract: POST /api/queue/pause|resume -> 200 Queue.
export async function setQueuePaused(paused: boolean): Promise<Queue> {
  const response = await fetch(`/api/queue/${paused ? 'pause' : 'resume'}`, { method: 'POST' });
  return json<Queue>(response);
}

// Opens GET /api/events and passes each named event to onEvent, plus `disconnected` when
// the connection drops. EventSource retries a dropped connection by itself, but gives up
// for good on an HTTP error, so this opens a new stream in that case. The server sends a
// `heartbeat` after 5 s of silence (ADR 0044), so idleMs without any event means the
// connection is lost although EventSource has not noticed (a silent network partition):
// the stream is closed, reported as `disconnected`, and opened anew. Returns a function
// that closes the stream and cancels any pending reconnect.
export function subscribeToEvents(
  onEvent: (event: StreamEvent) => void,
  reconnectMs = 1000,
  idleMs = 15_000,
): () => void {
  let source: EventSource | undefined;
  let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
  let idleTimer: ReturnType<typeof setTimeout> | undefined;
  let stopped = false;

  const open = () => {
    const next = new EventSource('/api/events');
    source = next;
    // Every event restarts the watchdog, so it only fires on a stream that stayed silent.
    const watch = () => {
      clearTimeout(idleTimer);
      idleTimer = setTimeout(() => {
        next.close();
        onEvent({ type: 'disconnected', closed: true });
        reconnectTimer = setTimeout(open, reconnectMs);
      }, idleMs);
    };
    const listen = <T>(name: ServerEvent['type'], toEvent: (data: T) => ServerEvent) => {
      next.addEventListener(name, (message) => {
        watch();
        onEvent(toEvent(JSON.parse(message.data) as T));
      });
    };
    listen<Snapshot>('snapshot', (snapshot) => ({ type: 'snapshot', snapshot }));
    listen<Task>('task-updated', (task) => ({ type: 'task-updated', task }));
    listen<TaskProgress>('task-progress', (progress) => ({ type: 'task-progress', progress }));
    listen<Queue>('queue-updated', (queue) => ({ type: 'queue-updated', queue }));
    listen<{ id: number }>('task-deleted', ({ id }) => ({ type: 'task-deleted', id }));
    listen<Speakers & { id: number }>('speakers-renamed', ({ id, speakers }) => ({
      type: 'speakers-renamed',
      id,
      speakers,
    }));
    listen<Speakers & { id: number }>('speakers-merged', ({ id, speakers }) => ({
      type: 'speakers-merged',
      id,
      speakers,
    }));
    listen<Speakers & { id: number }>('speakers-reassigned', ({ id, speakers }) => ({
      type: 'speakers-reassigned',
      id,
      speakers,
    }));
    listen<{ id: number; segment: Segment }>('segment-edited', ({ id, segment }) => ({
      type: 'segment-edited',
      id,
      segment,
    }));
    listen<SettingsNotice>('settings-updated', (settings) => ({
      type: 'settings-updated',
      settings,
    }));
    next.addEventListener('heartbeat', watch);
    next.addEventListener('error', () => {
      const closed = next.readyState === EventSource.CLOSED;
      onEvent({ type: 'disconnected', closed });
      if (closed && !stopped) {
        clearTimeout(idleTimer);
        next.close();
        reconnectTimer = setTimeout(open, reconnectMs);
      }
    });
    watch();
  };

  open();
  return () => {
    stopped = true;
    clearTimeout(reconnectTimer);
    clearTimeout(idleTimer);
    source?.close();
  };
}
