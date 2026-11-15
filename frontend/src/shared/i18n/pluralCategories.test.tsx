import { render, screen } from '@testing-library/react';
import { useIntl } from 'react-intl';
import { describe, expect, it } from 'vitest';
import { LocaleProvider } from '../../app/LocaleProvider';

// Cycle 20: the catalog's ICU plural messages are selected by the format locale's plural rules,
// not by English's. Arabic has six categories, including the dual (`two`) for count 2.
function PluralProbe() {
  const intl = useIntl();
  return (
    <span>
      {[0, 1, 2, 3, 11, 100]
        .map((count) => intl.formatMessage({ id: 'test.plural' }, { count }))
        .join(' ')}
    </span>
  );
}

describe('plural categories', () => {
  it('selects Arabic’s dual and the other categories from the format locale', () => {
    render(
      <LocaleProvider
        locale="ar-EG"
        messages={{
          'test.plural':
            '{count, plural, zero {zero} one {one} two {two} few {few} many {many} other {other}}',
        }}
      >
        <PluralProbe />
      </LocaleProvider>,
    );

    expect(screen.getByText('zero one two few many other')).toBeInTheDocument();
  });
});
