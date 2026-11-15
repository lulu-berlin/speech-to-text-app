import type { IntlShape } from 'react-intl';

// A duration as mm:ss under an hour, h:mm:ss from one hour on. The digits follow the format
// locale (Arabic-Indic in ar-EG); the colon separators and the shape stay. Shared by the task
// rows' running elapsed time and the history views' timestamps (item 59 moved it here from the
// history feature so the queue can use it without reaching across features).
export function formatDuration(durationMs: number, intl: IntlShape): string {
  const totalSeconds = Math.floor(durationMs / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const padded = (value: number) => intl.formatNumber(value, { minimumIntegerDigits: 2 });
  return hours > 0
    ? `${intl.formatNumber(hours)}:${padded(minutes)}:${padded(seconds)}`
    : `${padded(minutes)}:${padded(seconds)}`;
}
