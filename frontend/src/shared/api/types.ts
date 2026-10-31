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

// The running diarization stage; null outside the DIARIZING phase (item 12 contract).
export type DiarizationStage = 'EMBEDDING' | 'CLUSTERING' | null;

export type SpeakerMode = 'auto' | 'off' | 'fixed';

// Item 12 contract: one speaker of GET /api/tasks/{id}/turns (docs/api/turns.json).
export interface TurnSpeaker {
  speakerId: number;
  speaker: string;
}

// Item 12 contract: one spoken segment of a turn (docs/api/turns.json). `id` is the
// stored segment row (item 52), which the edit endpoint addresses.
export interface Segment {
  id: number;
  startMs: number;
  endMs: number;
  text: string;
  // ADR 0061: the stored text differs from whisper's original, so the editor offers Revert.
  edited: boolean;
}

// Item 12 contract: one speaker turn (docs/api/turns.json); times are on the /audio
// timeline. `speakerId` is null when speakers are off (one turn per segment).
export interface Turn {
  speakerId: number | null;
  startMs: number;
  endMs: number;
  text: string;
  segments: Segment[];
}

// Item 12 contract: GET /api/tasks/{id}/turns (docs/api/turns.json).
export interface Turns {
  speakers: TurnSpeaker[];
  // Item 22: whether the task has a merge to undo ("Reset all merges"); false with speakers off.
  hasMerges: boolean;
  turns: Turn[];
}

// Item 22 contract: the 200 of the speaker endpoints (docs/api/speakers.json): the task's
// effective speakers, those not merged into another, by speakerId.
export interface Speakers {
  speakers: TurnSpeaker[];
}

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
  hasAudio: boolean;
  transcriptionProgress: number;
  diarizationStage: DiarizationStage;
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
  diarizationStage: DiarizationStage;
  diarizationProgress: number | null;
}

// Events of GET /api/events, by their SSE event name.
export type ServerEvent =
  | { type: 'snapshot'; snapshot: Snapshot }
  | { type: 'task-updated'; task: Task }
  | { type: 'task-progress'; progress: TaskProgress }
  | { type: 'queue-updated'; queue: Queue }
  | { type: 'task-deleted'; id: number }
  // Item 22 (docs/api/speakers-renamed.json, speakers-merged.json): the task's effective
  // speakers after a stored rename, or after a merge or reset of all merges.
  | { type: 'speakers-renamed'; id: number; speakers: TurnSpeaker[] }
  | { type: 'speakers-merged'; id: number; speakers: TurnSpeaker[] }
  // Item 73 (docs/api/speakers-reassigned.json): the task's effective speakers after a stored move.
  | { type: 'speakers-reassigned'; id: number; speakers: TurnSpeaker[] }
  // Item 52 (docs/api/segment-edited.json): the one segment a stored edit changed.
  | { type: 'segment-edited'; id: number; segment: Segment }
  // Item 32 (docs/api/settings-updated*.json): the stored settings after a write, a dismissed
  // CPU-fallback notice, or the engine's switch of an unusable GPU to the CPU.
  | { type: 'settings-updated'; settings: SettingsNotice };

// What the event stream reports: a server event, or `disconnected` when the connection
// dropped. `closed` is true when EventSource gave up (an HTTP error) and will not retry,
// so the machine has to reconnect itself.
export type StreamEvent = ServerEvent | { type: 'disconnected'; closed: boolean };

// Item 32 contract: the stored settings (docs/api/settings*.json). `device` and the entries of
// `availableDevices` share one value space: `auto`, `cpu` or `gpu:<index>`. `deviceFallback`
// names the GPU the engine switched to the CPU (the Queue's banner), null without a notice.
export interface Settings {
  device: string;
  threads: number | null;
  defaultLanguage: string;
  speakersMode: SpeakerMode;
  speakersCount: number | null;
  maxSpeakers: number;
  deviceFallback: string | null;
  availableDevices: string[];
  // The probe's name for each offered GPU, keyed by `gpu:<index>` (`{}` without a GPU); the
  // Settings page labels each GPU with it. Fixed by the startup probe, so it is not in
  // settings-updated.
  gpuNames: Record<string, string>;
  // The startup probe's launch failure message when it could not run (item 60), null when it
  // succeeded. Fixed at startup, like gpuNames, so it is not part of a settings write or event.
  deviceProbeError: string | null;
}

// What a settings write (PUT /api/settings) sends: the settings without the device
// choices and without the fallback notice, which the write clears (item 32's contract).
export type SettingsUpdate = Omit<
  Settings,
  'availableDevices' | 'deviceFallback' | 'gpuNames' | 'deviceProbeError'
>;

// What the settings-updated event carries: every field of the settings except the device
// choices — the fallback notice can change live, and belongs here.
export type SettingsNotice = Omit<Settings, 'availableDevices' | 'gpuNames' | 'deviceProbeError'>;

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

// The visible text of a failure: an Error's message (the typed ApiError carries the
// server's), anything else as a string.
export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
