// Typed API client (ADR 0029 contract-first, frontend conventions: components never fetch
// directly; all access goes through this module).

export const taskStatuses = [
  'QUEUED',
  'RUNNING',
  'PAUSED',
  'COMPLETED',
  'FAILED',
  'CANCELED',
] as const;

export type TaskStatus = (typeof taskStatuses)[number];

export type TaskPhase = 'CHUNKING' | 'TRANSCRIBING' | 'DIARIZING' | null;

export type SpeakerMode = 'auto' | 'off' | 'fixed';

// Item 12 contract: Task (docs/api/task.*.json).
export interface Task {
  id: number;
  filename: string;
  status: TaskStatus;
  phase: TaskPhase;
  language: string;
  speakersMode: SpeakerMode;
  speakersCount: number | null;
  maxSpeakers: number;
  durationMs: number | null;
  sizeBytes: number;
  transcriptionProgress: number;
  diarizationProgress: number | null;
  error: string | null;
  transcribedUntilMs: number | null;
  createdAt: number | null;
  startedAt: number | null;
  finishedAt: number | null;
}

export interface TaskList {
  tasks: Task[];
}

// Item 12 contract: Queue (docs/api/queue.json), pending task ids in queue order.
export interface Queue {
  paused: boolean;
  taskIds: number[];
}

// Item 12 contract: the first event of every /api/events connection (docs/api/snapshot.json).
export interface Snapshot {
  tasks: Task[];
  queue: Queue;
}

// Item 12 contract: payload of the task-progress event (docs/api/task-progress*.json).
export interface TaskProgress {
  id: number;
  transcriptionProgress: number;
  diarizationProgress: number | null;
}

// Events of GET /api/events, by their SSE event name.
export type ServerEvent =
  | { type: 'snapshot'; snapshot: Snapshot }
  | { type: 'task-updated'; task: Task }
  | { type: 'task-progress'; progress: TaskProgress }
  | { type: 'queue-updated'; queue: Queue }
  | { type: 'task-deleted'; id: number };

// Error body: { error, message } plus taskId for duplicate-audio only.
export interface ApiErrorBody {
  error: string;
  message: string;
  taskId?: number;
}

// A typed error carrying the contract's error code; the UI never sees a bare exception.
// For duplicate-audio responses, taskId names the existing task.
export class ApiError extends Error {
  readonly code: string;
  readonly taskId?: number;
  constructor(code: string, message: string, taskId?: number) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.taskId = taskId;
  }
}
