import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { ConnectionContext } from '../../app/ConnectionContext';
import { controllableEventStream } from '../../test/eventStream';
import { ThemedProviders } from '../../test/render';
import { server } from '../../test/server';
import settingsFallback from '../../../../docs/api/settings.fallback.json';
import settingsFixed from '../../../../docs/api/settings.fixed.json';
import settingsProbeFailed from '../../../../docs/api/settings.probe-failed.json';
import settingsUpdatedDefaults from '../../../../docs/api/settings-updated.defaults.json';
import { SettingsView } from './SettingsView';

function renderSettings() {
  return render(
    <ThemedProviders>
      <ConnectionContext.Provider>
        <MemoryRouter>
          <SettingsView />
        </MemoryRouter>
      </ConnectionContext.Provider>
    </ThemedProviders>,
  );
}

async function settingsLoaded() {
  await waitFor(() =>
    expect(screen.getByRole('combobox', { name: 'Language' })).toHaveValue('german'),
  );
}

describe('the settings page', () => {
  it('shows the stored settings and the available devices, a stored device not listed included', async () => {
    server.use(http.get('/api/settings', () => HttpResponse.json(settingsFixed)));
    renderSettings();
    await settingsLoaded();

    // settings.fixed.json: GPU 1, 6 threads, German, 3 fixed speakers, 12 max speakers.
    expect(screen.getByLabelText('Device')).toHaveValue('gpu:1');
    expect(
      Array.from(screen.getByLabelText('Device').querySelectorAll('option')).map(
        (option) => option.textContent,
      ),
    ).toEqual(['CPU', 'Auto', 'GPU 0 — NVIDIA GeForce RTX 3060', 'GPU 1 — AMD Radeon RX 6600']);
    expect(screen.getByLabelText('Threads')).toHaveValue(6);
    expect(screen.getByLabelText('Speakers')).toHaveValue('fixed');
    expect(screen.getByLabelText('Speaker count')).toHaveValue(3);
    expect(screen.getByLabelText('Max speakers')).toHaveValue(12);
  });

  it('saves a change with PUT /api/settings and shows the stored answer', async () => {
    const puts: Array<object> = [];
    server.use(
      http.get('/api/settings', () => HttpResponse.json(settingsFixed)),
      http.put('/api/settings', async ({ request }) => {
        puts.push((await request.json()) as object);
        return HttpResponse.json({ ...settingsFixed, device: 'gpu:0' });
      }),
    );
    renderSettings();
    await settingsLoaded();

    fireEvent.change(screen.getByLabelText('Device'), { target: { value: 'gpu:0' } });
    fireEvent.change(screen.getByLabelText('Threads'), { target: { value: '4' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).toEqual({
      device: 'gpu:0',
      threads: 4,
      defaultLanguage: 'de',
      speakersMode: 'fixed',
      speakersCount: 3,
      maxSpeakers: 12,
    });
    await waitFor(() => expect(screen.getByLabelText('Device')).toHaveValue('gpu:0'));
    expect(screen.getByLabelText('Threads')).toHaveValue(6);
  });

  it('shows a rejection inline and changes nothing', async () => {
    server.use(
      http.get('/api/settings', () => HttpResponse.json(settingsFixed)),
      http.put('/api/settings', () =>
        HttpResponse.json(
          {
            error: 'invalid-device',
            message: 'device must be auto, cpu, or gpu:<index> with an index >= 0, got "tpu"',
          },
          { status: 400 },
        ),
      ),
    );
    renderSettings();
    await settingsLoaded();

    fireEvent.change(screen.getByLabelText('Device'), { target: { value: 'tpu' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText(/device must be auto, cpu/)).toBeInTheDocument();
  });

  it('shows a callout naming a failed device probe', async () => {
    server.use(http.get('/api/settings', () => HttpResponse.json(settingsProbeFailed)));
    renderSettings();
    await waitFor(() => expect(screen.getByLabelText('Device')).toHaveValue('auto'));

    const notice = await screen.findByRole('alert');
    expect(notice).toHaveTextContent('The device probe failed');
    expect(notice).toHaveTextContent('Executable not found: whisper-cli');
  });

  it('shows no probe notice for a successful probe', async () => {
    server.use(http.get('/api/settings', () => HttpResponse.json(settingsFixed)));
    renderSettings();
    await settingsLoaded();

    expect(screen.queryByText('The device probe failed')).not.toBeInTheDocument();
  });

  it('follows a settings-updated from another tab', async () => {
    const events = controllableEventStream();
    server.use(http.get('/api/settings', () => HttpResponse.json(settingsFixed)));
    renderSettings();
    await settingsLoaded();

    // settings-updated.defaults.json: another tab saved the defaults.
    events.send('settings-updated', settingsUpdatedDefaults);
    await waitFor(() =>
      expect(screen.getByRole('combobox', { name: 'Language' })).toHaveValue('auto-detect'),
    );
    expect(screen.getByLabelText('Device')).toHaveValue('auto');
  });
});
