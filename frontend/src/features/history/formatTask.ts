import { formatDuration } from '../../shared/formatDuration';
import type { Task } from '../../shared/api/types';

// The finish date and duration of a task, as the History cards and the transcript's header
// block show them.

// The fixed month names and UTC rendering of the finish date, so the text is the same for
// every user (a toLocaleDateString would follow the reader's locale).
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
export function formatFinishDate(finishedAtMs: number): string {
  const date = new Date(finishedAtMs);
  return `${MONTHS[date.getUTCMonth()]} ${date.getUTCDate()}, ${date.getUTCFullYear()}`;
}

// The meta line: the finish date and the duration joined by " · ", each only when known.
export function formatTaskMeta(task: Pick<Task, 'finishedAt' | 'durationMs'>): string {
  return [
    task.finishedAt !== null ? formatFinishDate(task.finishedAt) : null,
    task.durationMs !== null ? formatDuration(task.durationMs) : null,
  ]
    .filter((part) => part !== null)
    .join(' · ');
}
