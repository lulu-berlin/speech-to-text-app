import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { ConnectionContext } from '../../app/ConnectionContext';
import {
  errorDuplicateAudio,
  errorInvalidLanguage,
  queue as queueFixture,
  snapshot as snapshotFixture,
  taskPaused,
  taskQueued,
  taskRunning,
} from '../../shared/api/fixtures';
import { server } from '../../test/server';
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

// Reads one text field out of a multipart body (the upload options fields).
function multipartField(body: string, name: string): string {
  const match = body.match(new RegExp(`name="${name}"\\r\\n\\r\\n([^\\r]*)\\r\\n`));
  return match?.[1] ?? '';
}

// A live /api/events stream for one test: it opens with the snapshot
// (docs/api/snapshot.json), then sends each event the test pushes, so the test can check
// one state before the next event arrives.
function controllableEventStream() {
  const encoder = new TextEncoder();
  let controller: ReadableStreamDefaultController<Uint8Array> | undefined;
  const events = {
    send(name: string, data: unknown) {
      if (controller === undefined) {
        throw new Error('the event stream is not connected yet');
      }
      controller.enqueue(encoder.encode(`event: ${name}\ndata: ${JSON.stringify(data)}\n\n`));
    },
  };
  server.use(
    http.get(
      '/api/events',
      () =>
        new HttpResponse(
          new ReadableStream<Uint8Array>({
            start(streamController) {
              controller = streamController;
              events.send('snapshot', snapshotFixture);
            },
          }),
          { headers: { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' } },
        ),
    ),
  );
  return events;
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
    const rows = within(screen.getByRole('list', { name: 'Uploads' })).getAllByRole('listitem');
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
          // A fresh id: the snapshot already holds task 7, and a done row leaves once
          // its task is in the live state.
          this.response = { ...taskQueued, id: 99 };
          this.onload?.();
        });
      }
    }
    vi.stubGlobal('XMLHttpRequest', FakeXhr);
    try {
      renderQueue();

      const files = [new File(['x'], 'a.mp3', { type: 'audio/mpeg' })];
      fireEvent.drop(screen.getByRole('button', { name: /drop/i }), { dataTransfer: { files } });

      // The uploading file's row shows its progress (scoped to the uploads list: the
      // task rows carry their own progress bars).
      const uploads = screen.getByRole('list', { name: 'Uploads' });
      const progress = await within(uploads).findByRole('progressbar', { name: /a\.mp3/i });
      expect(progress).toHaveAttribute('aria-valuenow', '50');

      // Once the upload completes, the progress bar leaves the row.
      release();
      await waitFor(() =>
        expect(within(uploads).queryByRole('progressbar')).not.toBeInTheDocument(),
      );
      expect(screen.getByText('a.mp3')).toBeInTheDocument();
      expect(screen.getByText('done')).toBeInTheDocument();
    } finally {
      vi.unstubAllGlobals();
    }
  });
});

describe('the queue list', () => {
  it('shows a new task in the queue list after its upload', async () => {
    const newTask = { ...taskQueued, id: 99, filename: 'a.mp3' };
    const newQueue = { ...queueFixture, taskIds: [...queueFixture.taskIds, 99] };
    server.use(http.post('/api/tasks', () => HttpResponse.json(newTask, { status: 201 })));
    const events = controllableEventStream();

    renderQueue();

    // The snapshot's pending tasks are listed in queue order.
    await screen.findByText('lecture.m4a');
    expect(screen.getByText('memo.wav')).toBeInTheDocument();

    // Upload a file; it completes before the server announces the new task.
    const files = [new File(['x'], 'a.mp3', { type: 'audio/mpeg' })];
    fireEvent.drop(screen.getByRole('button', { name: /drop/i }), { dataTransfer: { files } });
    await waitFor(() => expect(screen.getByText('done')).toBeInTheDocument());

    // The real backend announces a new upload with task-updated plus the queue change:
    // the task joins the queue list at the end, and its upload row leaves.
    events.send('task-updated', newTask);
    events.send('queue-updated', newQueue);
    await waitFor(() => expect(screen.queryByText('done')).not.toBeInTheDocument());
    const rows = screen.getAllByRole('listitem');
    expect(rows.map((row) => row.textContent)).toEqual([
      expect.stringContaining('lecture.m4a'),
      expect.stringContaining('memo.wav'),
      expect.stringContaining('a.mp3'),
    ]);
    expect(within(rows[2] as HTMLElement).getByText('QUEUED')).toBeInTheDocument();
  });

  it("updates a task's two progress bars from task-progress events; the diarization bar shows its stage and hides when diarization does not apply", async () => {
    const events = controllableEventStream();

    renderQueue();

    // The running task shows both bars from the snapshot's progress (42%, diarization 0).
    await screen.findByText('lecture.m4a');
    const lecture = screen.getByText('lecture.m4a').closest('li') as HTMLElement;
    expect(within(lecture).getByRole('progressbar', { name: 'Transcription' })).toHaveAttribute(
      'aria-valuenow',
      '42',
    );
    expect(
      within(lecture).getByRole('progressbar', { name: 'Speaker diarization' }),
    ).toHaveAttribute('aria-valuenow', '0');

    // A progress event updates both bars; the diarization bar names its stage.
    events.send('task-progress', {
      id: 5,
      transcriptionProgress: 0.75,
      diarizationStage: 'CLUSTERING',
      diarizationProgress: 0.4,
    });
    await waitFor(() =>
      expect(within(lecture).getByRole('progressbar', { name: 'Transcription' })).toHaveAttribute(
        'aria-valuenow',
        '75',
      ),
    );
    expect(
      within(lecture).getByRole('progressbar', { name: 'Speaker diarization' }),
    ).toHaveAttribute('aria-valuetext', 'Clustering 70%');

    // When diarization does not apply, its bar disappears; transcription keeps updating.
    events.send('task-progress', {
      id: 5,
      transcriptionProgress: 0.85,
      diarizationStage: null,
      diarizationProgress: null,
    });
    await waitFor(() =>
      expect(within(lecture).getByRole('progressbar', { name: 'Transcription' })).toHaveAttribute(
        'aria-valuenow',
        '85',
      ),
    );
    expect(
      within(lecture).queryByRole('progressbar', { name: 'Speaker diarization' }),
    ).not.toBeInTheDocument();
  });

  it("updates a task's status and phase from task-updated events", async () => {
    const events = controllableEventStream();

    renderQueue();

    // From the snapshot: task 5 is RUNNING while transcribing; task 7 is QUEUED and has
    // no phase yet, so it shows no phase label.
    await screen.findByText('lecture.m4a');
    const lecture = screen.getByText('lecture.m4a').closest('li') as HTMLElement;
    const memo = screen.getByText('memo.wav').closest('li') as HTMLElement;
    expect(within(lecture).getByText('RUNNING')).toBeInTheDocument();
    expect(within(lecture).getByText('Transcribing')).toBeInTheDocument();
    expect(within(memo).getByText('QUEUED')).toBeInTheDocument();
    expect(within(memo).queryByText(/Chunking|Transcribing|Diarizing/)).not.toBeInTheDocument();

    // task.paused.json is task 7 (memo.wav) paused while transcribing.
    events.send('task-updated', taskPaused);
    await waitFor(() => expect(within(memo).getByText('PAUSED')).toBeInTheDocument());
    expect(within(memo).getByText('Transcribing')).toBeInTheDocument();
  });
});

describe('task controls', () => {
  it('pauses a running task: the pause control calls the pause endpoint and the row shows it paused', async () => {
    // The stock pause handler answers with task.paused.json under the URL's id, whose
    // other fields (filename, progress) belong to task 7; the real backend returns the
    // requested task, so the test answers with task 5 itself, paused.
    const paused: string[] = [];
    server.use(
      http.post('/api/tasks/:id/pause', ({ params }) => {
        paused.push(String(params.id));
        return HttpResponse.json({ ...taskRunning, status: 'PAUSED' });
      }),
    );

    renderQueue();

    await screen.findByText('lecture.m4a');
    const lecture = screen.getByText('lecture.m4a').closest('li') as HTMLElement;
    fireEvent.click(within(lecture).getByRole('button', { name: 'Pause lecture.m4a' }));

    // The stream sends nothing after the snapshot, so the paused state comes from the
    // command's response.
    await waitFor(() => expect(within(lecture).getByText('PAUSED')).toBeInTheDocument());
    expect(paused).toEqual(['5']);
  });
});

describe('upload errors', () => {
  it("keeps each error on its file's row: unsupported format, invalid input, and a duplicate with a link", async () => {
    // The three files that reach the server answer differently; the rejected file (a.txt)
    // must never reach it. The file's multipart Content-Type identifies it (jsdom drops
    // the content and the filename; see the drop-order test).
    const seen: string[] = [];
    server.use(
      http.post('/api/tasks', async ({ request }) => {
        const body = await request.text();
        const contentType = body.match(/Content-Type: ([^\r]+)/)?.[1] ?? '';
        seen.push(contentType);
        if (contentType === 'audio/ogg') {
          // c.ogg duplicates completed task 3 (interview.mp3, COMPLETED in the snapshot).
          return HttpResponse.json(errorDuplicateAudio, { status: 409 });
        }
        if (contentType === 'audio/wav') {
          // d.wav duplicates running task 5 (lecture.m4a, RUNNING in the snapshot).
          return HttpResponse.json({ ...errorDuplicateAudio, taskId: 5 }, { status: 409 });
        }
        return HttpResponse.json(errorInvalidLanguage, { status: 400 });
      }),
    );

    renderQueue();

    const files = [
      new File(['x'], 'a.txt', { type: 'text/plain' }),
      new File(['x'], 'b.mp3', { type: 'audio/mpeg' }),
      new File(['x'], 'c.ogg', { type: 'audio/ogg' }),
      new File(['x'], 'd.wav', { type: 'audio/wav' }),
    ];
    fireEvent.drop(screen.getByRole('button', { name: /drop/i }), { dataTransfer: { files } });

    const uploads = screen.getByRole('list', { name: 'Uploads' });
    const rowFor = (filename: string) =>
      within(uploads)
        .getAllByRole('listitem')
        .find((row) => row.textContent?.includes(filename));

    // The unsupported file is rejected before any upload.
    await waitFor(() =>
      expect(
        within(rowFor('a.txt') as HTMLElement).getByText('Unsupported file format'),
      ).toBeInTheDocument(),
    );
    await waitFor(() => expect(seen.length).toBe(3));

    // A server 400 stays on that file's row.
    expect(
      within(rowFor('b.mp3') as HTMLElement).getByText('Unsupported language'),
    ).toBeInTheDocument();

    // A duplicate of a finished task links to its transcript.
    expect(
      within(rowFor('c.ogg') as HTMLElement).getByText(/Already uploaded/),
    ).toBeInTheDocument();
    expect(
      within(rowFor('c.ogg') as HTMLElement).getByRole('link', { name: 'open' }),
    ).toHaveAttribute('href', '/history/3');

    // A duplicate of a pending task links to its queue row.
    expect(
      within(rowFor('d.wav') as HTMLElement).getByText(/Already uploaded/),
    ).toBeInTheDocument();
    expect(
      within(rowFor('d.wav') as HTMLElement).getByRole('link', { name: 'open' }),
    ).toHaveAttribute('href', '/queue');

    // Only the three supported files reached the server, in drop order.
    expect(seen).toEqual(['audio/mpeg', 'audio/ogg', 'audio/wav']);
  });
});
