import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { ConnectionContext } from '../../app/ConnectionContext';
import { ThemedProviders } from '../../test/render';
import languages from '../../../../docs/api/languages.json';
import { QueueView } from './QueueView';

function renderQueue() {
  return render(
    <ThemedProviders>
      <ConnectionContext.Provider>
        <MemoryRouter>
          <QueueView />
        </MemoryRouter>
      </ConnectionContext.Provider>
    </ThemedProviders>,
  );
}

describe('the queue view upload options', () => {
  it('offers the languages from docs/api/languages.json with auto as the default, and bounds speakers and max speakers to 1–20', () => {
    renderQueue();

    // The language select defaults to auto (languages.json's default).
    const language = screen.getByRole('combobox', { name: 'Language' });
    expect(language).toHaveValue('auto-detect');

    // It offers every code from docs/api/languages.json.
    fireEvent.click(language);
    const listbox = screen.getByRole('listbox', { name: 'Language' });
    const options = within(listbox).getAllByRole('option');
    expect(options).toHaveLength(languages.languages.length);
    expect(within(listbox).getByRole('option', { name: 'german (de)' })).toBeInTheDocument();
    expect(within(listbox).getByRole('option', { name: 'auto-detect (auto)' })).toBeInTheDocument();

    // Selecting a language shows its name (the option selects on mousedown, so the
    // input's blur cannot close the listbox first).
    fireEvent.mouseDown(within(listbox).getByRole('option', { name: 'german (de)' }));
    expect(screen.getByRole('combobox', { name: 'Language' })).toHaveValue('german');

    // Speakers: auto/off/fixed; a fixed count is bounded to 1–20.
    fireEvent.change(screen.getByLabelText('Speakers'), { target: { value: 'fixed' } });
    const count = screen.getByLabelText('Speaker count');
    fireEvent.change(count, { target: { value: '0' } });
    expect(count).toHaveValue(1);
    fireEvent.change(count, { target: { value: '25' } });
    expect(count).toHaveValue(20);
    fireEvent.change(count, { target: { value: '7' } });
    expect(count).toHaveValue(7);

    // Max speakers (behind the advanced toggle) defaults to 8 and is bounded to 1–20.
    fireEvent.click(screen.getByRole('button', { name: /advanced/i }));
    const max = screen.getByLabelText('Max speakers');
    expect(max).toHaveValue(8);
    fireEvent.change(max, { target: { value: '0' } });
    expect(max).toHaveValue(1);
    fireEvent.change(max, { target: { value: '25' } });
    expect(max).toHaveValue(20);
  });
});
