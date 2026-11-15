import type { IntlShape } from 'react-intl';
import { formatDuration } from '../../shared/formatDuration';
import type { Task } from '../../shared/api/types';

// The finish date and duration of a task, as the History cards and the transcript's header
// block show them. The date follows the locale's pattern (the month order and names are the
// locale's) and stays on the UTC date the API stores; `intl` is the app's formatter, whose
// locale is the format locale.
export function formatTaskMeta(
  task: Pick<Task, 'finishedAt' | 'durationMs'>,
  intl: IntlShape,
): string {
  return [
    task.finishedAt !== null
      ? intl.formatDate(new Date(task.finishedAt), {
          month: 'short',
          day: 'numeric',
          year: 'numeric',
          timeZone: 'UTC',
        })
      : null,
    task.durationMs !== null ? formatDuration(task.durationMs, intl) : null,
  ]
    .filter((part) => part !== null)
    .join(' · ');
}
