import { createIntl } from 'react-intl';
import { describe, expect, it } from 'vitest';
import { formatDuration } from './formatDuration';

// Cycle 21: the duration's digits follow the format locale. Arabic writes Arabic-Indic digits;
// the colon separators and the h:mm:ss shape stay.
describe('formatDuration', () => {
  it('formats the duration digits in the format locale', () => {
    const arabic = createIntl({ locale: 'ar-EG', messages: {} });
    const english = createIntl({ locale: 'en-US', messages: {} });

    expect(formatDuration(125_000, arabic)).toBe('٠٢:٠٥');
    expect(formatDuration(3_725_000, arabic)).toBe('١:٠٢:٠٥');
    expect(formatDuration(125_000, english)).toBe('02:05');
    expect(formatDuration(3_725_000, english)).toBe('1:02:05');
  });
});
