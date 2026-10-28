import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { ConnectionContext } from '../../app/ConnectionContext';
import { SettingsContext, SettingsSync } from '../../app/SettingsContext';
import { controllableEventStream } from '../../test/eventStream';
import { ThemedProviders } from '../../test/render';
import { server } from '../../test/server';
import settingsFixture from '../../../../docs/api/settings.json';
import settingsFixed from '../../../../docs/api/settings.fixed.json';
import settingsProbeFailed from '../../../../docs/api/settings.probe-failed.json';
import settingsUpdatedDefaults from '../../../../docs/api/settings-updated.defaults.json';
import errorFixture from '../../../../docs/api/error.json';
import { SettingsView } from './SettingsView';

function renderSettings() {
  return render(
    <ThemedProviders>
      <ConnectionContext.Provider>
        <SettingsContext.Provider>
          <SettingsSync />
          <MemoryRouter>
            <SettingsView />
          </MemoryRouter>
        </SettingsContext.Provider>
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

  it('writes the whole settings when the device changes, with no Save button', async () => {
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

    // The page commits each change itself: there is no Save to click.
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Device'), { target: { value: 'gpu:0' } });

    // One write, the settings as a whole: settings.fixed.json's other fields travel unchanged.
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).toEqual({
      device: 'gpu:0',
      threads: 6,
      defaultLanguage: 'de',
      speakersMode: 'fixed',
      speakersCount: 3,
      maxSpeakers: 12,
    });

    // The stored answer is what the page shows.
    await waitFor(() => expect(screen.getByLabelText('Device')).toHaveValue('gpu:0'));
    expect(screen.getByLabelText('Threads')).toHaveValue(6);
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

  it('shows a failed load with Retry, and Retry fetches the stored settings into the form', async () => {
    let gets = 0;
    server.use(
      http.get('/api/settings', () => {
        gets += 1;
        // The first load fails (500 internal has no docs/api example, so error.json stands
        // in); the retry is served.
        return gets === 1
          ? HttpResponse.json(errorFixture, { status: 500 })
          : HttpResponse.json(settingsFixed);
      }),
    );
    renderSettings();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Task 42 does not exist');

    fireEvent.click(within(alert).getByRole('button', { name: 'Retry' }));
    await settingsLoaded();

    // settings.fixed.json: GPU 1, 6 threads, German, 3 fixed speakers, 12 max speakers.
    expect(gets).toBe(2);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Threads')).toHaveValue(6);
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

  it('keeps a focused draft when a settings-updated arrives', async () => {
    const events = controllableEventStream();
    server.use(http.get('/api/settings', () => HttpResponse.json(settingsFixed)));
    renderSettings();
    await settingsLoaded();

    // The user is editing threads (not yet committed); another tab's update must not replace
    // what they are typing, while every other field follows (ADR 0062).
    const threads = screen.getByLabelText('Threads');
    fireEvent.focus(threads);
    fireEvent.change(threads, { target: { value: '4' } });
    events.send('settings-updated', settingsUpdatedDefaults);

    await waitFor(() => expect(screen.getByLabelText('Device')).toHaveValue('auto'));
    expect(threads).toHaveValue(4);
    expect(screen.getByRole('combobox', { name: 'Language' })).toHaveValue('auto-detect');
  });

  it('keeps an invalid draft and its error when a settings-updated arrives', async () => {
    const events = controllableEventStream();
    server.use(http.get('/api/settings', () => HttpResponse.json(settingsFixed)));
    renderSettings();
    await settingsLoaded();

    // An invalid max speakers keeps the typed value and its error, while the other fields
    // follow the event (ADR 0062).
    const max = screen.getByLabelText('Max speakers');
    fireEvent.change(max, { target: { value: '25' } });
    expect(max).toHaveAccessibleDescription(/whole number from 1 to 20/);
    events.send('settings-updated', settingsUpdatedDefaults);

    await waitFor(() => expect(screen.getByLabelText('Device')).toHaveValue('auto'));
    expect(max).toHaveValue(25);
    expect(max).toHaveAccessibleDescription(/whole number from 1 to 20/);
  });
});

describe('the settings threads field', () => {
  it('shows a field error for invalid threads and blocks a junk paste', async () => {
    let puts = 0;
    server.use(
      http.get('/api/settings', () => HttpResponse.json(settingsFixed)),
      http.put('/api/settings', () => {
        puts += 1;
        return HttpResponse.json(settingsFixed);
      }),
    );
    renderSettings();
    await settingsLoaded();

    // settings.fixed.json stores 6 threads; that draft is valid.
    const threads = screen.getByLabelText('Threads');
    expect(threads).toHaveValue(6);
    expect(threads).not.toHaveAccessibleDescription();

    // Zero, a negative count and a fraction are not whole numbers >= 1: each shows a field
    // error, and typing never writes.
    for (const value of ['0', '-3', '1.5']) {
      fireEvent.change(threads, { target: { value } });
      expect(threads).toBeInvalid();
      expect(threads).toHaveAccessibleDescription(/whole number of 1 or more/);
    }

    // A valid draft clears the error.
    fireEvent.change(threads, { target: { value: '6' } });
    expect(threads).toBeValid();
    expect(threads).not.toHaveAccessibleDescription();

    // Pasting junk is blocked: the field keeps what it held and shows the field error.
    fireEvent.paste(threads, { clipboardData: { getData: () => 'abc' } });
    expect(threads).toHaveValue(6);
    expect(threads).toHaveAccessibleDescription(/whole number of 1 or more/);
    fireEvent.change(threads, { target: { value: '7' } });
    expect(threads).not.toHaveAccessibleDescription();

    // Nothing was stored: the server's threads value is unchanged.
    expect(puts).toBe(0);
  });

  it('sends a threads edit when the field loses focus or on Enter, never while typing', async () => {
    const puts: Array<Record<string, unknown>> = [];
    server.use(
      http.get('/api/settings', () => HttpResponse.json(settingsFixed)),
      http.put('/api/settings', async ({ request }) => {
        const update = (await request.json()) as Record<string, unknown>;
        puts.push(update);
        return HttpResponse.json({ ...settingsFixed, ...update });
      }),
    );
    renderSettings();
    await settingsLoaded();

    // Typing a new count writes nothing: an intermediate draft never reaches the server.
    const threads = screen.getByLabelText('Threads');
    fireEvent.change(threads, { target: { value: '4' } });
    expect(puts).toHaveLength(0);

    // Leaving the field commits the settings as a whole, with the new count.
    fireEvent.blur(threads);
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).toEqual({
      device: 'gpu:1',
      threads: 4,
      defaultLanguage: 'de',
      speakersMode: 'fixed',
      speakersCount: 3,
      maxSpeakers: 12,
    });

    // Enter commits too.
    fireEvent.change(threads, { target: { value: '9' } });
    expect(puts).toHaveLength(1);
    fireEvent.keyDown(threads, { key: 'Enter' });
    await waitFor(() => expect(puts).toHaveLength(2));
    expect(puts[1]?.threads).toBe(9);
  });

  it('stores null when the cleared threads field is left', async () => {
    const puts: Array<Record<string, unknown>> = [];
    server.use(
      http.get('/api/settings', () => HttpResponse.json(settingsFixed)),
      http.put('/api/settings', async ({ request }) => {
        const update = (await request.json()) as Record<string, unknown>;
        puts.push(update);
        return HttpResponse.json({ ...settingsFixed, ...update });
      }),
    );
    renderSettings();
    await settingsLoaded();

    const threads = screen.getByLabelText('Threads');
    fireEvent.change(threads, { target: { value: '' } });
    // An empty field is whisper's default, not invalid input — and still writes nothing yet.
    expect(threads).not.toHaveAccessibleDescription();
    expect(puts).toHaveLength(0);

    fireEvent.blur(threads);
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]?.threads).toBeNull();
    // The stored answer (null) keeps the field empty.
    await waitFor(() => expect(threads).toHaveValue(null));
  });
});

describe('the settings typed counts', () => {
  it('shows a field error for invalid counts and sends nothing', async () => {
    const puts: Array<Record<string, unknown>> = [];
    server.use(
      http.get('/api/settings', () => HttpResponse.json(settingsFixed)),
      http.put('/api/settings', async ({ request }) => {
        const update = (await request.json()) as Record<string, unknown>;
        puts.push(update);
        return HttpResponse.json({ ...settingsFixed, ...update });
      }),
    );
    renderSettings();
    await settingsLoaded();

    const threads = screen.getByLabelText('Threads');
    const count = screen.getByLabelText('Speaker count');
    const max = screen.getByLabelText('Max speakers');

    // Threads: an integer >= 1. Zero, a negative count and a fraction each show the field's
    // error and write nothing when the field is left.
    for (const value of ['0', '-3', '1.5']) {
      fireEvent.change(threads, { target: { value } });
      expect(threads).toHaveAccessibleDescription(/whole number of 1 or more/);
      fireEvent.blur(threads);
    }
    expect(puts).toHaveLength(0);
    fireEvent.change(threads, { target: { value: '6' } });
    expect(threads).not.toHaveAccessibleDescription();

    // The speaker count and max speakers are integers from 1 to 20 (settings.fixed.json
    // stores 3 and 12): outside the bound, or a fraction, is an error and writes nothing.
    for (const value of ['0', '21', '1.5']) {
      fireEvent.change(count, { target: { value } });
      expect(count).toHaveAccessibleDescription(/whole number from 1 to 20/);
      fireEvent.blur(count);
    }
    expect(puts).toHaveLength(0);

    fireEvent.change(max, { target: { value: '25' } });
    expect(max).toHaveAccessibleDescription(/whole number from 1 to 20/);
    fireEvent.blur(max);
    expect(puts).toHaveLength(0);

    // A junk paste into the speaker count keeps the count it held, with the error shown.
    fireEvent.change(count, { target: { value: '3' } });
    fireEvent.paste(count, { clipboardData: { getData: () => 'abc' } });
    expect(count).toHaveValue(3);
    expect(count).toHaveAccessibleDescription(/whole number from 1 to 20/);
    expect(puts).toHaveLength(0);

    // A valid correction is written as the whole settings; the stored counts were unchanged
    // until now, so the invalid max speakers travels as the stored 12.
    fireEvent.change(count, { target: { value: '5' } });
    fireEvent.blur(count);
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).toEqual({
      device: 'gpu:1',
      threads: 6,
      defaultLanguage: 'de',
      speakersMode: 'fixed',
      speakersCount: 5,
      maxSpeakers: 12,
    });
  });
});

describe('the settings writes', () => {
  it('collapses a change made during a write into one follow-up with the latest values', async () => {
    const writes: Array<Record<string, unknown>> = [];
    const settle: Array<(response: Response) => void> = [];
    server.use(
      http.get('/api/settings', () => HttpResponse.json(settingsFixed)),
      http.put('/api/settings', async ({ request }) => {
        writes.push((await request.json()) as Record<string, unknown>);
        const { promise, resolve } = Promise.withResolvers<Response>();
        settle.push(resolve);
        return promise;
      }),
    );
    renderSettings();
    await settingsLoaded();

    // The client issues its fetch synchronously from the change, so counting fetches tells a
    // coalesced follow-up from a second write without waiting on the network.
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    const putFetches = () =>
      fetchSpy.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === 'PUT')
        .length;

    const device = screen.getByLabelText('Device');
    fireEvent.change(device, { target: { value: 'gpu:0' } });
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(putFetches()).toBe(1);

    // A second change while the first write is in flight joins one follow-up; it is not
    // written beside the first.
    fireEvent.change(device, { target: { value: 'cpu' } });
    expect(putFetches()).toBe(1);

    // The first answer lands and the follow-up starts with the latest values; the form keeps
    // the user's latest choice throughout (no flash back to the answered value).
    settle[0]?.(HttpResponse.json({ ...settingsFixed, device: 'gpu:0' }));
    await waitFor(() => expect(writes).toHaveLength(2));
    expect(device).toHaveValue('cpu');
    expect(writes[1]).toEqual({
      device: 'cpu',
      threads: 6,
      defaultLanguage: 'de',
      speakersMode: 'fixed',
      speakersCount: 3,
      maxSpeakers: 12,
    });

    settle[1]?.(HttpResponse.json({ ...settingsFixed, device: 'cpu' }));
    await waitFor(() => expect(device).toHaveValue('cpu'));
    // Two changes, two writes: no third.
    expect(writes).toHaveLength(2);
  });
});

describe('a rejected settings write', () => {
  it('shows the message on the field its code names, reverts only that field, and stores the rest next time', async () => {
    const writes: Array<Record<string, unknown>> = [];
    let calls = 0;
    server.use(
      http.get('/api/settings', () => HttpResponse.json(settingsFixed)),
      http.put('/api/settings', async ({ request }) => {
        writes.push((await request.json()) as Record<string, unknown>);
        calls += 1;
        return calls === 1
          ? HttpResponse.json(
              {
                error: 'invalid-threads',
                message: 'threads must be null or a number >= 1, got 4',
              },
              { status: 400 },
            )
          : HttpResponse.json({ ...settingsFixed, device: 'gpu:0', threads: 5 });
      }),
    );
    renderSettings();
    await settingsLoaded();

    const threads = screen.getByLabelText('Threads');
    const device = screen.getByLabelText('Device');

    // The threads draft (not yet committed) rides along with the device change: the write
    // carries both.
    fireEvent.change(threads, { target: { value: '4' } });
    fireEvent.change(device, { target: { value: 'gpu:0' } });
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes[0]).toMatchObject({ device: 'gpu:0', threads: 4 });

    // The rejection names the threads field: the server's message shows on that field, the
    // field goes back to the stored 6, and no page-level callout takes over.
    await waitFor(() =>
      expect(threads).toHaveAccessibleDescription(/threads must be null or a number >= 1/),
    );
    expect(threads).toHaveValue(6);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    // Every other field keeps its draft: the device stays the one that was chosen.
    expect(device).toHaveValue('gpu:0');

    // The rejected write changed nothing on the server; the next change stores the draft the
    // rejection also carried.
    fireEvent.change(threads, { target: { value: '5' } });
    fireEvent.blur(threads);
    await waitFor(() => expect(writes).toHaveLength(2));
    expect(writes[1]).toMatchObject({ device: 'gpu:0', threads: 5 });
    await waitFor(() => expect(threads).toHaveValue(5));
    expect(device).toHaveValue('gpu:0');
  });

  it('sends the change made during the rejected write as one rebuilt follow-up', async () => {
    const writes: Array<Record<string, unknown>> = [];
    const first = Promise.withResolvers<Response>();
    let calls = 0;
    server.use(
      http.get('/api/settings', () => HttpResponse.json(settingsFixed)),
      http.put('/api/settings', async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        writes.push(body);
        calls += 1;
        return calls === 1 ? first.promise : HttpResponse.json({ ...settingsFixed, ...body });
      }),
    );
    renderSettings();
    await settingsLoaded();

    const threads = screen.getByLabelText('Threads');
    const device = screen.getByLabelText('Device');

    // The threads draft rides along with a device change; while that write runs the user
    // chooses another device, which becomes the one queued follow-up.
    fireEvent.change(threads, { target: { value: '4' } });
    fireEvent.change(device, { target: { value: 'gpu:0' } });
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes[0]).toMatchObject({ device: 'gpu:0', threads: 4 });
    fireEvent.change(device, { target: { value: 'cpu' } });

    // The write is rejected: its threads revert to the stored 6, and the queued change is
    // rebuilt from the form as it now stands — not resent with the refused 4.
    first.resolve(
      HttpResponse.json(
        { error: 'invalid-threads', message: 'threads must be null or a number >= 1, got 4' },
        { status: 400 },
      ),
    );
    await waitFor(() => expect(writes).toHaveLength(2));
    expect(writes[1]).toEqual({
      device: 'cpu',
      threads: 6,
      defaultLanguage: 'de',
      speakersMode: 'fixed',
      speakersCount: 3,
      maxSpeakers: 12,
    });
    await waitFor(() => expect(threads).toHaveValue(6));
    await waitFor(() => expect(device).toHaveValue('cpu'));
    // Exactly one follow-up: the rebuild is sent once.
    expect(writes).toHaveLength(2);
  });

  it('shows a code that names no field without a Retry action', async () => {
    server.use(
      http.get('/api/settings', () => HttpResponse.json(settingsFixed)),
      http.put('/api/settings', () => HttpResponse.json(errorFixture, { status: 500 })),
    );
    renderSettings();
    await settingsLoaded();

    fireEvent.change(screen.getByLabelText('Device'), { target: { value: 'gpu:0' } });

    // There is no retry for a write: fixing the field is the retry, so the callout has no
    // dead Retry button (RETRY is handled only by the failed load).
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Task 42 does not exist');
    expect(within(alert).queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });

  it('keeps a shown rejection on its field when a settings-updated arrives', async () => {
    const events = controllableEventStream();
    server.use(
      http.get('/api/settings', () => HttpResponse.json(settingsFixed)),
      http.put('/api/settings', () =>
        HttpResponse.json(
          { error: 'invalid-device', message: 'device is not one of the available devices' },
          { status: 400 },
        ),
      ),
    );
    renderSettings();
    await settingsLoaded();

    const device = screen.getByLabelText('Device');
    fireEvent.change(device, { target: { value: 'gpu:0' } });

    // The rejection names the device field: its message shows there, and the field is back
    // to the stored GPU 1.
    await waitFor(() =>
      expect(device).toHaveAccessibleDescription(/device is not one of the available devices/),
    );
    expect(device).toHaveValue('gpu:1');

    // Another tab's settings arrive; the message the user has not answered stays, while the
    // field follows the newer stored settings (settings-updated.defaults.json: auto).
    events.send('settings-updated', settingsUpdatedDefaults);
    await waitFor(() => expect(device).toHaveValue('auto'));
    expect(device).toHaveAccessibleDescription(/device is not one of the available devices/);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('the saving indicator', () => {
  it('says "Saving…" while a write is in flight and drops it when the settings are stored', async () => {
    const settle: Array<(response: Response) => void> = [];
    server.use(
      http.get('/api/settings', () => HttpResponse.json(settingsFixed)),
      http.put('/api/settings', () => {
        const { promise, resolve } = Promise.withResolvers<Response>();
        settle.push(resolve);
        return promise;
      }),
    );
    renderSettings();
    await settingsLoaded();

    expect(screen.queryByText('Saving…')).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Device'), { target: { value: 'gpu:0' } });
    const saving = await screen.findByRole('status');
    expect(saving).toHaveTextContent('Saving…');

    settle[0]?.(HttpResponse.json({ ...settingsFixed, device: 'gpu:0' }));
    await waitFor(() => expect(screen.queryByText('Saving…')).not.toBeInTheDocument());
  });
});

describe('a settings page whose load failed', () => {
  it('disables every field and sends no write', async () => {
    const puts: Array<Record<string, unknown>> = [];
    server.use(
      http.get('/api/settings', () => HttpResponse.json(errorFixture, { status: 500 })),
      http.put('/api/settings', async ({ request }) => {
        puts.push((await request.json()) as Record<string, unknown>);
        return HttpResponse.json(settingsFixed);
      }),
    );
    renderSettings();

    // The load failed: its callout offers Retry, and the form has nothing to send, so every
    // field is inert.
    await screen.findByRole('alert');
    expect(screen.getByLabelText('Device')).toBeDisabled();
    expect(screen.getByLabelText('Threads')).toBeDisabled();
    expect(screen.getByRole('combobox', { name: 'Language' })).toBeDisabled();
    expect(screen.getByLabelText('Speakers')).toBeDisabled();
    expect(screen.getByLabelText('Max speakers')).toBeDisabled();

    // Nothing can be typed or chosen, so nothing is written.
    fireEvent.change(screen.getByLabelText('Device'), { target: { value: 'gpu:0' } });
    fireEvent.change(screen.getByLabelText('Threads'), { target: { value: '4' } });
    fireEvent.blur(screen.getByLabelText('Threads'));
    expect(puts).toHaveLength(0);
  });
});

describe('restoring the default settings', () => {
  it('asks first, then resets the stored settings and shows every default', async () => {
    let deletes = 0;
    server.use(
      http.get('/api/settings', () => HttpResponse.json(settingsFixed)),
      http.delete('/api/settings', () => {
        deletes += 1;
        return HttpResponse.json(settingsFixture);
      }),
    );
    renderSettings();
    await settingsLoaded();

    fireEvent.click(screen.getByRole('button', { name: 'Restore default settings' }));

    // The dialog asks first: nothing is sent yet.
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Restore default settings?')).toBeInTheDocument();
    expect(deletes).toBe(0);

    fireEvent.click(within(dialog).getByRole('button', { name: 'Restore' }));
    await waitFor(() => expect(deletes).toBe(1));

    // settings.json: device auto, threads null, auto-detect, auto speakers, 8 max speakers.
    await waitFor(() => expect(screen.getByLabelText('Device')).toHaveValue('auto'));
    expect(screen.getByLabelText('Threads')).toHaveValue(null);
    expect(screen.getByRole('combobox', { name: 'Language' })).toHaveValue('auto-detect');
    expect(screen.getByLabelText('Speakers')).toHaveValue('auto');
    expect(screen.getByLabelText('Max speakers')).toHaveValue(8);
    expect(screen.queryByLabelText('Speaker count')).not.toBeInTheDocument();
  });

  it('shows a failed reset in the page callout without a Retry, and keeps every field', async () => {
    let deletes = 0;
    server.use(
      http.get('/api/settings', () => HttpResponse.json(settingsFixed)),
      http.delete('/api/settings', () => {
        deletes += 1;
        return HttpResponse.json(errorFixture, { status: 500 });
      }),
    );
    renderSettings();
    await settingsLoaded();

    fireEvent.click(screen.getByRole('button', { name: 'Restore default settings' }));
    fireEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Restore' }),
    );

    // The reset is a write: its rejection names no field, so it uses the page callout, and
    // fixing it is the action, so there is no dead Retry (RETRY belongs to the failed load).
    const alert = await screen.findByRole('alert');
    expect(deletes).toBe(1);
    expect(alert).toHaveTextContent('Task 42 does not exist');
    expect(within(alert).queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();

    // Nothing was reset: settings.fixed.json is still what the form shows, and the action can
    // be used again.
    expect(screen.getByLabelText('Device')).toHaveValue('gpu:1');
    expect(screen.getByLabelText('Threads')).toHaveValue(6);
    expect(screen.getByRole('combobox', { name: 'Language' })).toHaveValue('german');
    expect(screen.getByLabelText('Speakers')).toHaveValue('fixed');
    expect(screen.getByLabelText('Speaker count')).toHaveValue(3);
    expect(screen.getByLabelText('Max speakers')).toHaveValue(12);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Restore default settings' })).toBeEnabled(),
    );
  });
});

describe('cancelling the restore', () => {
  it('sends no request and leaves every field as it was', async () => {
    let deletes = 0;
    server.use(
      http.get('/api/settings', () => HttpResponse.json(settingsFixed)),
      http.delete('/api/settings', () => {
        deletes += 1;
        return HttpResponse.json(settingsFixture);
      }),
    );
    renderSettings();
    await settingsLoaded();

    fireEvent.click(screen.getByRole('button', { name: 'Restore default settings' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(deletes).toBe(0);

    // settings.fixed.json is still what the form shows, in every field.
    expect(screen.getByLabelText('Device')).toHaveValue('gpu:1');
    expect(screen.getByLabelText('Threads')).toHaveValue(6);
    expect(screen.getByRole('combobox', { name: 'Language' })).toHaveValue('german');
    expect(screen.getByLabelText('Speakers')).toHaveValue('fixed');
    expect(screen.getByLabelText('Speaker count')).toHaveValue(3);
    expect(screen.getByLabelText('Max speakers')).toHaveValue(12);
  });
});

describe('switching the speakers mode', () => {
  it('defers a switch to fixed until the count field holds a valid count', async () => {
    const puts: Array<Record<string, unknown>> = [];
    server.use(
      http.get('/api/settings', () => HttpResponse.json(settingsFixture)),
      http.put('/api/settings', async ({ request }) => {
        const update = (await request.json()) as Record<string, unknown>;
        puts.push(update);
        return HttpResponse.json({ ...settingsFixture, ...update });
      }),
    );
    renderSettings();
    await waitFor(() => expect(screen.getByLabelText('Speakers')).toHaveValue('auto'));

    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    const putFetches = () =>
      fetchSpy.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === 'PUT')
        .length;

    fireEvent.change(screen.getByLabelText('Speakers'), { target: { value: 'fixed' } });

    // The count field appears empty and invalid: nothing is written until a count is typed.
    const count = screen.getByLabelText('Speaker count');
    expect(count).toHaveAccessibleDescription(/whole number from 1 to 20/);
    expect(putFetches()).toBe(0);

    // The count's own commit stores the mode with it.
    fireEvent.change(count, { target: { value: '3' } });
    fireEvent.blur(count);
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).toMatchObject({ speakersMode: 'fixed', speakersCount: 3 });
  });

  it('stores another field while fixed has no valid count, sending the stored speakers', async () => {
    const puts: Array<Record<string, unknown>> = [];
    server.use(
      http.get('/api/settings', () => HttpResponse.json(settingsFixture)),
      http.put('/api/settings', async ({ request }) => {
        const update = (await request.json()) as Record<string, unknown>;
        puts.push(update);
        return HttpResponse.json({ ...settingsFixture, ...update });
      }),
    );
    renderSettings();
    await waitFor(() => expect(screen.getByLabelText('Speakers')).toHaveValue('auto'));

    // fixed with an empty count is an invalid body: nothing is sent, the field keeps its
    // error, and the stored speakers (auto, null) travel instead.
    fireEvent.change(screen.getByLabelText('Speakers'), { target: { value: 'fixed' } });
    const count = screen.getByLabelText('Speaker count');
    expect(count).toHaveAccessibleDescription(/whole number from 1 to 20/);

    const max = screen.getByLabelText('Max speakers');
    fireEvent.change(max, { target: { value: '10' } });
    fireEvent.blur(max);
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).toEqual({
      device: 'auto',
      threads: null,
      defaultLanguage: 'auto',
      speakersMode: 'auto',
      speakersCount: null,
      maxSpeakers: 10,
    });

    // The draft mode and count stay, with the count's error; the other field followed the
    // stored answer.
    expect(screen.getByLabelText('Speakers')).toHaveValue('fixed');
    expect(count).toHaveValue(null);
    expect(count).toHaveAccessibleDescription(/whole number from 1 to 20/);
    expect(max).toHaveValue(10);
  });
});
