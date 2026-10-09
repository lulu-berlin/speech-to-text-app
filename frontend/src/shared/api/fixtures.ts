// The single source of mock data is docs/api/ (ADR 0029). These files sit outside the
// frontend package, so this module re-exports them for both the MSW handlers (tests and
// dev:mock) and the typed client types.

export { default as snapshot } from '../../../../docs/api/snapshot.json';
export { default as taskList } from '../../../../docs/api/task-list.json';
export { default as taskQueued } from '../../../../docs/api/task.queued.json';
export { default as taskRunning } from '../../../../docs/api/task.running.json';
export { default as taskCompleted } from '../../../../docs/api/task.completed.json';
export { default as taskFailed } from '../../../../docs/api/task.failed.json';
export { default as taskPaused } from '../../../../docs/api/task.paused.json';
export { default as taskCanceled } from '../../../../docs/api/task.canceled.json';
export { default as queue } from '../../../../docs/api/queue.json';
export { default as taskProgress } from '../../../../docs/api/task-progress.json';
export { default as taskDeleted } from '../../../../docs/api/task-deleted.json';
export { default as turns } from '../../../../docs/api/turns.json';
export { default as turnsSpeakersOff } from '../../../../docs/api/turns.speakers-off.json';
export { default as error } from '../../../../docs/api/error.json';
export { default as errorDuplicateAudio } from '../../../../docs/api/error.duplicate-audio.json';
export { default as taskTransitions } from '../../../../docs/api/task-transitions.json';

import type { Task } from './types';
import taskQueuedExample from '../../../../docs/api/task.queued.json';
import taskRunningExample from '../../../../docs/api/task.running.json';
import taskPausedExample from '../../../../docs/api/task.paused.json';
import taskCompletedExample from '../../../../docs/api/task.completed.json';
import taskFailedExample from '../../../../docs/api/task.failed.json';
import taskCanceledExample from '../../../../docs/api/task.canceled.json';

// Every Task example, one per status. JSON imports type `status` as string, so they are
// narrowed to Task once here.
export const taskExamples = [
  taskQueuedExample,
  taskRunningExample,
  taskPausedExample,
  taskCompletedExample,
  taskFailedExample,
  taskCanceledExample,
] as Task[];
