import { createIntl } from 'react-intl';
import { describe, expect, it } from 'vitest';
import { formatTaskMeta } from './formatTask';

// Cycle 18: the finish date follows the locale's date pattern. The region matters: en-US writes
// the month before the day, en-GB the day before the month, and German uses its own month
// abbreviation. The date is still the UTC date (the value the API stores).
describe('formatTaskMeta', () => {
  it('formats the finish date in the locale’s order and month names', () => {
    const task = { finishedAt: Date.UTC(2026, 9, 9, 19, 45), durationMs: null };

    expect(formatTaskMeta(task, createIntl({ locale: 'en-US', messages: {} }))).toBe('Oct 9, 2026');
    expect(formatTaskMeta(task, createIntl({ locale: 'en-GB', messages: {} }))).toBe('9 Oct 2026');
    expect(formatTaskMeta(task, createIntl({ locale: 'de-DE', messages: {} }))).toBe(
      '9. Okt. 2026',
    );
  });
});
