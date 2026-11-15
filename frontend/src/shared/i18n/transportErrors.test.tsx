import { render, screen } from '@testing-library/react';
import { HttpResponse, http } from 'msw/http';
import { describe, expect, it } from 'vitest';
import { LocaleProvider } from '../../app/LocaleProvider';
import { server } from '../../test/server';
import { getSettings, saveSettings } from '../api/client';
import { ApiError, type SettingsUpdate } from '../api/types';
import { useErrorText } from './useErrorText';

function Probe({ write, read }: { write: unknown; read: unknown }) {
  const errorText = useErrorText();
  return (
    <>
      <p>{errorText(write)}</p>
      <p>{errorText(read)}</p>
    </>
  );
}

function codeOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    throw new Error('expected an ApiError');
  }
  return error.code;
}

describe('the transport error codes', () => {
  it('marks a read and a write with their own codes and shows the catalog message for each', async () => {
    server.use(
      http.get('/api/settings', () => HttpResponse.error()),
      http.put('/api/settings', () => HttpResponse.error()),
    );

    const readError = await getSettings().catch((error: unknown) => error);
    const update: SettingsUpdate = {
      device: 'auto',
      threads: null,
      defaultLanguage: 'auto',
      speakersMode: 'auto',
      speakersCount: null,
      maxSpeakers: 8,
      appLanguage: null,
      themeMode: 'auto',
    };
    const writeError = await saveSettings(update).catch((error: unknown) => error);

    expect(codeOf(readError)).toBe('transport-read');
    expect(codeOf(writeError)).toBe('transport-write');

    render(
      <LocaleProvider
        messages={{
          'error.transport-write': 'Write offline',
          'error.transport-read': 'Read offline',
        }}
      >
        <Probe write={writeError} read={readError} />
      </LocaleProvider>,
    );
    expect(screen.getByText('Write offline')).toBeInTheDocument();
    expect(screen.getByText('Read offline')).toBeInTheDocument();
  });
});
