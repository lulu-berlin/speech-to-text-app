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

// Error body: { error, message } plus taskId for duplicate-audio only.
export interface ApiErrorBody {
  error: string;
  message: string;
  taskId?: number;
}

export interface GetTasksResult {
  tasks: Task[];
}
