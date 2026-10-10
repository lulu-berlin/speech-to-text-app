import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { taskQueued } from '../../shared/api/fixtures';
import { server } from '../../test/server';
import { ThemedProviders } from '../../test/render';
import languages from '../../../../docs/api/languages.json';
import { QueueView } from './QueueView';

function renderQueue() {
  return render(
    <ThemedProviders>
      <MemoryRouter>
        <QueueView />
      </MemoryRouter>
    </ThemedProviders>,
  );
}

// Reads one text field out of a multipart body (the upload options fields).
function multipartField(body: string, name: string): string {
  const match = body.match(new RegExp(`name="${name}"\\r\\n\\r\\n([^\\r]*)\\r\\n`));
  return match?.[1] ?? '';
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

describe('the queue view upload flow', () => {
  it('uploads dropped files one at a time in drop order, each with the batch options', async () => {
    // jsdom + MSW cannot parse an XHR FormData body (mswjs/interceptors#684), so the
    // handler reads the raw multipart text. jsdom drops the file's content, but the
    // file's Content-Type header survives and identifies which file the request carries.
    const arrivals: Array<{
      contentType: string;
      language: string;
      speakers: string;
      maxSpeakers: string;
    }> = [];
    const { promise: gate, resolve: release } = Promise.withResolvers<void>();

    // The handler records each upload's file and options, and holds the response until
    // the test releases it, so the test can see that the next file does not start while
    // the previous one is still uploading.
    server.use(
      http.post('/api/tasks', async ({ request }) => {
        const body = await request.text();
        arrivals.push({
          contentType: body.match(/Content-Type: ([^\r]+)/)?.[1] ?? '',
          language: multipartField(body, 'language'),
          speakers: multipartField(body, 'speakers'),
          maxSpeakers: multipartField(body, 'maxSpeakers'),
        });
        await gate;
        return HttpResponse.json(taskQueued, { status: 201 });
      }),
    );

    renderQueue();

    // The batch options: language de, speakers fixed 3, max speakers 10.
    fireEvent.click(screen.getByRole('combobox', { name: 'Language' }));
    fireEvent.mouseDown(
      within(screen.getByRole('listbox', { name: 'Language' })).getByRole('option', {
        name: 'german (de)',
      }),
    );
    fireEvent.change(screen.getByLabelText('Speakers'), { target: { value: 'fixed' } });
    fireEvent.change(screen.getByLabelText('Speaker count'), { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: /advanced/i }));
    fireEvent.change(screen.getByLabelText('Max speakers'), { target: { value: '10' } });

    // Drop three files in order, each with a distinct content type so the requests can
    // be told apart (jsdom has no DataTransfer, so the drop carries a minimal double
    // with just the files; the component only reads dataTransfer.files).
    const files = [
      new File(['x'], 'a.mp3', { type: 'audio/mpeg' }),
      new File(['x'], 'b.wav', { type: 'audio/wav' }),
      new File(['x'], 'c.ogg', { type: 'audio/ogg' }),
    ];
    fireEvent.drop(screen.getByRole('button', { name: /drop/i }), { dataTransfer: { files } });

    // The first file starts immediately, with the batch options.
    await waitFor(() => expect(arrivals).toHaveLength(1));
    expect(arrivals[0]).toEqual({
      contentType: 'audio/mpeg',
      language: 'de',
      speakers: '3',
      maxSpeakers: '10',
    });

    // The rows appear in drop order; only the first file is uploading (it alone has a
    // progress bar), the others are queued.
    const rows = within(screen.getByRole('list')).getAllByRole('listitem');
    expect(rows.map((row) => row.textContent)).toEqual([
      expect.stringContaining('a.mp3'),
      expect.stringContaining('b.wav'),
      expect.stringContaining('c.ogg'),
    ]);
    expect(within(rows[0] as HTMLElement).getByRole('progressbar')).toBeInTheDocument();
    expect(rows[1]?.textContent).toContain('queued');
    expect(rows[2]?.textContent).toContain('queued');

    // The next file does not start while the first is still uploading.
    const { promise: pause, resolve: resume } = Promise.withResolvers<void>();
    setTimeout(resume, 50);
    await pause;
    expect(arrivals).toHaveLength(1);

    // Releasing the first response lets the second start, then the third.
    release();
    await waitFor(() => expect(arrivals).toHaveLength(2));
    expect(arrivals[1]).toEqual({
      contentType: 'audio/wav',
      language: 'de',
      speakers: '3',
      maxSpeakers: '10',
    });
    await waitFor(() => expect(arrivals).toHaveLength(3));
    expect(arrivals[2]).toEqual({
      contentType: 'audio/ogg',
      language: 'de',
      speakers: '3',
      maxSpeakers: '10',
    });
  });

  it("shows each uploading file's upload progress on its row", async () => {
    const { promise: proceed, resolve: release } = Promise.withResolvers<void>();

    // jsdom + MSW fire no XHR upload progress events, so the test swaps in a fake XHR
    // that simulates the browser: 50% sent, then (once released) 100% and a 201 response.
    class FakeXhr {
      upload = {
        onprogress: null as
          ((event: { lengthComputable: true; loaded: number; total: number }) => void) | null,
      };
      responseType = '';
      status = 0;
      response: unknown = null;
      onload: (() => void) | null = null;
      onerror: (() => void) | null = null;
      open(): void {}
      send(): void {
        this.upload.onprogress?.({ lengthComputable: true, loaded: 5, total: 10 });
        void proceed.then(() => {
          this.upload.onprogress?.({ lengthComputable: true, loaded: 10, total: 10 });
          this.status = 201;
          this.response = taskQueued;
          this.onload?.();
        });
      }
    }
    vi.stubGlobal('XMLHttpRequest', FakeXhr);
    try {
      renderQueue();

      const files = [new File(['x'], 'a.mp3', { type: 'audio/mpeg' })];
      fireEvent.drop(screen.getByRole('button', { name: /drop/i }), { dataTransfer: { files } });

      // The uploading file's row shows its progress.
      const progress = await screen.findByRole('progressbar', { name: /a\.mp3/i });
      expect(progress).toHaveAttribute('aria-valuenow', '50');

      // Once the upload completes, the progress bar leaves the row.
      release();
      await waitFor(() => expect(screen.queryByRole('progressbar')).not.toBeInTheDocument());
      expect(screen.getByText('a.mp3')).toBeInTheDocument();
      expect(screen.getByText('done')).toBeInTheDocument();
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
