import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import {
  error as errorFixture,
  errorDuplicateAudio,
  errorInvalidLanguage,
  settingsFallback,
  snapshot as snapshotFixture,
  taskQueued,
  turns as turnsFixture,
} from '../shared/api/fixtures';
import { server } from '../test/server';
import { App } from './App';

// The reason every write control names while the connection is not live (item 87).
const WRITES_UNAVAILABLE = "Can't reach the app's service. Reconnecting…";

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

// The /api/events stream with a snapshot and a handle to drop it on demand: the test opens
// the transcript's write controls while live, then cuts the connection. Every request the
// EventSource makes (the reconnect included) gets the snapshot, so the connection comes back;
// the retry frame sets how soon.
function openEventStream(snapshot: unknown, retryMs = 60_000) {
  const encoder = new TextEncoder();
  let controller: ReadableStreamDefaultController<Uint8Array> | undefined;
  server.use(
    http.get(
      '/api/events',
      () =>
        new HttpResponse(
          new ReadableStream<Uint8Array>({
            start(streamController) {
              controller = streamController;
              streamController.enqueue(encoder.encode(`retry: ${retryMs}\n\n`));
              streamController.enqueue(
                encoder.encode(`event: snapshot\ndata: ${JSON.stringify(snapshot)}\n\n`),
              );
            },
          }),
          { headers: { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' } },
        ),
    ),
  );
  return {
    drop() {
      controller?.close();
    },
  };
}

// turns.json with a merge to undo and an edited segment, so all three transcript writes have
// a control: Reset all merges, and Revert beside the editor's Save.
const editedTurns = {
  ...turnsFixture,
  hasMerges: true,
  turns: turnsFixture.turns.map((turn) => ({
    ...turn,
    segments: turn.segments.map((segment) =>
      segment.id === 2 ? { ...segment, text: 'Corrected sentence.', edited: true } : segment,
    ),
  })),
};

// The upload list's rows by filename (the batch tests): the list itself, a row element, and
// its text (the status line or the progress percentage).
function uploadRows() {
  const list = () => screen.getByRole('list', { name: 'Uploads' });
  const rowOf = (filename: string) => {
    const row = within(list()).getByText(filename).closest('li');
    if (row === null) {
      throw new Error('the upload row did not render');
    }
    return row;
  };
  return { list, rowOf, rowText: (filename: string) => rowOf(filename).textContent ?? '' };
}

describe('writes wait for the connection', () => {
  it('disables every write control, each naming why, while the connection is not live', async () => {
    const stream = openEventStream(snapshotFixture);
    server.use(
      http.get('/api/settings', () => HttpResponse.json(settingsFallback)),
      http.get('/api/tasks/:id/turns', () => HttpResponse.json(editedTurns)),
    );
    renderAt('/history/3');

    // Live: open the merge form, so its write button is rendered when the connection drops.
    await screen.findByText('Live');
    const panel = await screen.findByRole('region', { name: 'Speakers' });
    fireEvent.click(within(panel).getByRole('button', { name: 'Merge speakers…' }));

    // The stream ends: the connection is no longer live.
    stream.drop();
    await screen.findByText('Reconnecting…');

    // The speakers panel.
    const name = within(panel).getByRole('textbox', { name: 'Name of speaker 1' });
    expect(name).toBeDisabled();
    expect(name).toHaveAttribute('title', WRITES_UNAVAILABLE);
    const resetName = within(panel).getByRole('button', { name: 'Reset name of speaker 1' });
    expect(resetName).toBeDisabled();
    expect(resetName).toHaveAttribute('title', WRITES_UNAVAILABLE);
    const merge = within(panel).getByRole('button', { name: 'Merge' });
    expect(merge).toBeDisabled();
    expect(merge).toHaveAttribute('title', WRITES_UNAVAILABLE);
    const resetMerges = within(panel).getByRole('button', { name: 'Reset all merges' });
    expect(resetMerges).toBeDisabled();
    expect(resetMerges).toHaveAttribute('title', WRITES_UNAVAILABLE);
    // Opening the editor is local; its Save and Revert are the writes.
    fireEvent.click(screen.getByRole('button', { name: 'Corrected sentence.' }));
    fireEvent.click(screen.getByRole('button', { name: 'Edit segment' }));
    const save = screen.getByRole('button', { name: 'Save' });
    expect(save).toBeDisabled();
    expect(save).toHaveAttribute('title', WRITES_UNAVAILABLE);
    const revert = screen.getByRole('button', { name: 'Revert' });
    expect(revert).toBeDisabled();
    expect(revert).toHaveAttribute('title', WRITES_UNAVAILABLE);

    // Close the editor (Discard writes nothing) and go to the queue.
    fireEvent.keyDown(screen.getByLabelText('Segment text'), { key: 'Escape' });
    fireEvent.click(screen.getByRole('link', { name: 'Queue' }));

    // Upload, pause, reorder and the row commands.
    const dropZone = await screen.findByRole('button', {
      name: 'Drop audio files here or click to choose',
    });
    expect(dropZone).toBeDisabled();
    expect(dropZone).toHaveAttribute('title', WRITES_UNAVAILABLE);
    const queuePause = screen.getByRole('switch', { name: 'Pause the queue' });
    expect(queuePause).toBeDisabled();
    expect(queuePause).toHaveAttribute('title', WRITES_UNAVAILABLE);
    for (const reorder of screen.getAllByRole('button', { name: /^Reorder / })) {
      expect(reorder).toBeDisabled();
      expect(reorder).toHaveAttribute('title', WRITES_UNAVAILABLE);
    }
    const cancel = screen.getByRole('button', { name: 'Cancel memo.wav' });
    expect(cancel).toBeDisabled();
    expect(cancel).toHaveAttribute('title', WRITES_UNAVAILABLE);
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Actions for lecture.m4a' }), {
      button: 0,
    });
    await screen.findByRole('menu');
    for (const item of screen.getAllByRole('menuitem', { name: /Move / })) {
      expect(item).toHaveAttribute('data-disabled');
    }
    expect(screen.getByRole('menuitem', { name: 'Delete' })).toHaveAttribute('data-disabled');
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'Escape' });

    // The CPU-fallback notice's dismissal.
    const dismiss = screen.getByRole('button', { name: 'Dismiss' });
    expect(dismiss).toBeDisabled();
    expect(dismiss).toHaveAttribute('title', WRITES_UNAVAILABLE);

    // History's retries and deletes.
    fireEvent.click(screen.getByRole('link', { name: 'History' }));
    for (const retry of await screen.findAllByRole('button', { name: 'Retry' })) {
      expect(retry).toBeDisabled();
      expect(retry).toHaveAttribute('title', WRITES_UNAVAILABLE);
    }
    for (const remove of screen.getAllByRole('button', { name: 'Delete' })) {
      expect(remove).toBeDisabled();
      expect(remove).toHaveAttribute('title', WRITES_UNAVAILABLE);
    }

    // The settings fields, the restore and the dismissal.
    fireEvent.click(screen.getByRole('link', { name: 'Settings' }));
    for (const field of [
      await screen.findByLabelText('Device'),
      screen.getByLabelText('Threads'),
      screen.getByRole('combobox', { name: 'Language' }),
      screen.getByLabelText('Speakers'),
      screen.getByLabelText('Max speakers'),
    ]) {
      expect(field).toBeDisabled();
      expect(field).toHaveAttribute('title', WRITES_UNAVAILABLE);
    }
    const restore = screen.getByRole('button', { name: 'Restore default settings' });
    expect(restore).toBeDisabled();
    expect(restore).toHaveAttribute('title', WRITES_UNAVAILABLE);
    // The journey opens four pages and several panels; under a loaded run it needs more
    // than Testing Library's default 1 s waits allow inside vitest's 5 s test budget.
  }, 20_000);

  it('keeps the queue, a transcript, its turns, playback, scrolling and the settings readable while not live', async () => {
    const stream = openEventStream(snapshotFixture);
    server.use(http.get('/api/tasks/:id/turns', () => HttpResponse.json(turnsFixture)));
    renderAt('/queue');
    await screen.findByText('Live');
    stream.drop();
    await screen.findByText('Reconnecting…');

    // The queue's pending tasks are still shown.
    const pending = screen.getByRole('list', { name: 'Pending tasks' });
    expect(within(pending).getByText('lecture.m4a')).toBeInTheDocument();
    expect(within(pending).getByText('memo.wav')).toBeInTheDocument();

    // Scrolling still moves the page: the header compacts and returns at the top.
    Object.defineProperty(window, 'scrollY', { value: 200, configurable: true });
    fireEvent.scroll(window);
    expect(screen.getByRole('banner').querySelector('a svg')).toBeNull();
    Object.defineProperty(window, 'scrollY', { value: 0, configurable: true });
    fireEvent.scroll(window);
    await waitFor(() => expect(screen.getByRole('banner').querySelector('a svg')).not.toBeNull());
    // jsdom's scrollY is a readonly prototype accessor; the own property is removed again,
    // so later tests read the default.
    const windowWithScrollShadow = window as { scrollY?: number };
    delete windowWithScrollShadow.scrollY;

    // Pages switch, and History leads into a transcript with its turns.
    fireEvent.click(screen.getByRole('link', { name: 'History' }));
    fireEvent.click(await screen.findByRole('link', { name: 'interview.mp3' }));
    expect(await screen.findByText('Good morning.')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Transcript' })).toBeInTheDocument();

    // The loaded audio plays.
    const player = screen.getByRole('group', { name: 'Audio player' });
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const play = vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    fireEvent.click(within(player).getByRole('button', { name: 'Play' }));
    expect(play).toHaveBeenCalledTimes(1);

    // The settings page shows the stored values.
    fireEvent.click(screen.getByRole('link', { name: 'Settings' }));
    expect(await screen.findByRole('heading', { name: 'Settings' })).toBeInTheDocument();
    expect(screen.getByLabelText('Device')).toHaveValue('auto');
  }, 20_000);

  it('offers the same write controls again when the connection is live again, without a reload', async () => {
    // The reconnect is the client's own: the EventSource retries after the retry frame, and
    // the handler answers that new stream with a fresh snapshot.
    const stream = openEventStream(snapshotFixture, 1_000);
    renderAt('/queue');
    await screen.findByText('Live');
    const dropZone = () =>
      screen.getByRole('button', { name: 'Drop audio files here or click to choose' });

    stream.drop();
    await screen.findByText('Reconnecting…');
    expect(dropZone()).toBeDisabled();

    // The snapshot from the reconnect brings the controls back on the same mounted page.
    await screen.findByText('Live', undefined, { timeout: 5_000 });
    expect(dropZone()).toBeEnabled();
    expect(screen.getByRole('switch', { name: 'Pause the queue' })).toBeEnabled();
    for (const reorder of screen.getAllByRole('button', { name: /^Reorder / })) {
      expect(reorder).toBeEnabled();
    }
    expect(screen.getByRole('button', { name: 'Cancel memo.wav' })).toBeEnabled();
  }, 20_000);

  it("keeps the server's message for a rejected write, never the transport one", async () => {
    server.use(
      http.get('/api/tasks/:id/turns', () => HttpResponse.json(turnsFixture)),
      http.put('/api/tasks/:id/speakers/:speakerId', () =>
        HttpResponse.json({ ...errorFixture, error: 'duplicate-speaker-name' }, { status: 409 }),
      ),
    );
    renderAt('/history/3');
    await screen.findByText('Live');
    const panel = screen.getByRole('region', { name: 'Speakers' });
    const field = within(panel).getByRole('textbox', { name: 'Name of speaker 1' });
    fireEvent.change(field, { target: { value: 'Bob' } });
    fireEvent.keyDown(field, { key: 'Enter' });

    // The 409 body's message, and none of the app's unreachable-service wording.
    expect(await within(panel).findByText(errorFixture.message)).toBeInTheDocument();
    expect(within(panel).queryByText(/Not saved/)).not.toBeInTheDocument();
  }, 20_000);

  it("says a read can't reach the service, keeps the write wording, and never doubles the prefix", async () => {
    server.use(
      http.get('/api/settings', () => HttpResponse.error()),
      http.post('/api/tasks/:id/retry', () => HttpResponse.error()),
      http.delete('/api/tasks/:id', () => HttpResponse.error()),
    );
    renderAt('/history');
    await screen.findByText('Live');

    // A read: the service is unreachable, and nothing was being saved.
    fireEvent.click(screen.getByRole('link', { name: 'Settings' }));
    expect(
      await screen.findByText("Can't reach the app's service. Reconnecting…"),
    ).toBeInTheDocument();
    expect(screen.queryByText(/Not saved/)).not.toBeInTheDocument();

    // A write keeps the "Not saved" wording, and the History row adds no prefix of its own.
    fireEvent.click(screen.getByRole('link', { name: 'History' }));
    const callRow = (await screen.findByText('call.ogg')).closest('li');
    if (callRow === null) {
      throw new Error('the history row did not render');
    }
    fireEvent.click(within(callRow).getByRole('button', { name: 'Retry' }));
    expect(
      await within(callRow).findByText("Not saved: can't reach the app's service. Reconnecting…"),
    ).toBeInTheDocument();
    expect(within(callRow).queryByText(/Retry failed:/)).not.toBeInTheDocument();
    const interviewRow = screen.getByText('interview.mp3').closest('li');
    if (interviewRow === null) {
      throw new Error('the history row did not render');
    }
    fireEvent.click(within(interviewRow).getByRole('button', { name: 'Delete' }));
    fireEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Delete' }),
    );
    expect(
      await within(interviewRow).findByText(
        "Not saved: can't reach the app's service. Reconnecting…",
      ),
    ).toBeInTheDocument();
    expect(within(interviewRow).queryByText(/Delete failed:/)).not.toBeInTheDocument();

    // The failed task's status page says the same, never "Delete failed: Not saved".
    fireEvent.click(screen.getByRole('link', { name: 'call.ogg' }));
    await screen.findByRole('heading', { name: 'call.ogg' });
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Actions for call.ogg' }), {
      button: 0,
    });
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Delete' }));
    fireEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Delete' }),
    );
    expect(
      await screen.findByText("Not saved: can't reach the app's service. Reconnecting…"),
    ).toBeInTheDocument();
    expect(screen.queryByText(/Delete failed:/)).not.toBeInTheDocument();
  }, 20_000);

  it('waits out the connection and sends a cut-off upload again from the start', async () => {
    // a.mp3's first request fails at the transport; b.wav's first request hangs until the drop
    // aborts it. The resend of each is a fresh request of the whole file.
    let mp3Attempts = 0;
    let wavAttempts = 0;
    const sentTypes: string[] = [];
    server.use(
      http.post('/api/tasks', async ({ request }) => {
        const type = (await request.text()).match(/Content-Type: ([^\r]+)/)?.[1] ?? '';
        sentTypes.push(type);
        if (type === 'audio/mpeg') {
          mp3Attempts += 1;
          return mp3Attempts === 1
            ? HttpResponse.error()
            : HttpResponse.json({ ...taskQueued, id: 101, filename: 'a.mp3' }, { status: 201 });
        }
        wavAttempts += 1;
        if (wavAttempts === 1) {
          // The service never answers: the row hangs until the connection drops.
          return await new Promise<Response>(() => {});
        }
        return HttpResponse.json({ ...taskQueued, id: 102, filename: 'b.wav' }, { status: 201 });
      }),
    );
    const stream = openEventStream(snapshotFixture, 1_000);
    renderAt('/queue');
    await screen.findByText('Live');
    const { list: uploadList, rowOf, rowText } = uploadRows();

    // The first attempt fails outright: the row waits, it is not an error row.
    fireEvent.drop(screen.getByRole('button', { name: /drop audio files here/i }), {
      dataTransfer: {
        files: [
          new File(['a'], 'a.mp3', { type: 'audio/mpeg' }),
          new File(['b'], 'b.wav', { type: 'audio/wav' }),
        ],
      },
    });
    await waitFor(() => expect(mp3Attempts).toBe(1));
    await waitFor(() => expect(rowText('a.mp3')).toContain('Waiting for the connection…'));
    expect(rowText('a.mp3')).not.toContain("can't reach");
    expect(uploadList()).not.toHaveTextContent(/Not saved|NetworkError/);

    // While the stream is down nothing starts; both rows wait.
    stream.drop();
    await screen.findByText('Reconnecting…');
    expect(mp3Attempts).toBe(1);
    expect(wavAttempts).toBe(0);

    // The resend goes out at once, and the batch continues to b.wav, whose request hangs.
    await screen.findByText('Live', undefined, { timeout: 5_000 });
    await waitFor(() => expect(mp3Attempts).toBe(2));
    await waitFor(() => expect(wavAttempts).toBe(1));
    expect(
      within(rowOf('b.wav')).getByRole('progressbar', { name: 'Uploading b.wav' }),
    ).toBeInTheDocument();

    // The drop aborts the hung request; the row returns to waiting, not to an error.
    stream.drop();
    await screen.findByText('Reconnecting…');
    await waitFor(() => expect(rowText('b.wav')).toContain('Waiting for the connection…'));
    expect(uploadList()).not.toHaveTextContent(/Not saved|NetworkError/);

    // Live again: b.wav is sent again from the start and the batch finishes.
    await screen.findByText('Live', undefined, { timeout: 5_000 });
    await waitFor(() => expect(wavAttempts).toBe(2));
    expect(sentTypes).toEqual(['audio/mpeg', 'audio/mpeg', 'audio/wav', 'audio/wav']);
    expect(uploadList()).not.toHaveTextContent(/Not saved|NetworkError/);
  }, 30_000);

  it('starts no row of the batch while the live stream is down', async () => {
    // The first request never answers, so the batch is stuck on it when the connection drops.
    const started: string[] = [];
    server.use(
      http.post('/api/tasks', async ({ request }) => {
        const type = (await request.text()).match(/Content-Type: ([^\r]+)/)?.[1] ?? '';
        started.push(type);
        if (started.length === 1) {
          return await new Promise<Response>(() => {});
        }
        return HttpResponse.json(
          {
            ...taskQueued,
            id: 100 + started.length,
            filename: type === 'audio/mpeg' ? 'a.mp3' : 'b.wav',
          },
          { status: 201 },
        );
      }),
    );
    const stream = openEventStream(snapshotFixture, 1_000);
    renderAt('/queue');
    await screen.findByText('Live');
    const { rowText } = uploadRows();

    fireEvent.drop(screen.getByRole('button', { name: /drop audio files here/i }), {
      dataTransfer: {
        files: [
          new File(['a'], 'a.mp3', { type: 'audio/mpeg' }),
          new File(['b'], 'b.wav', { type: 'audio/wav' }),
        ],
      },
    });
    await waitFor(() => expect(started).toEqual(['audio/mpeg']));

    // The drop stops the batch as a whole: the cut-off row goes back to the queue, the row
    // behind it never started, and nothing new starts while the stream is down.
    stream.drop();
    await screen.findByText('Reconnecting…');
    await waitFor(() => expect(rowText('a.mp3')).toContain('Waiting for the connection…'));
    expect(rowText('b.wav')).toContain('Waiting for the connection…');
    expect(started).toEqual(['audio/mpeg']);

    // Live again: the cut-off row is sent again, then the row behind it starts.
    await screen.findByText('Live', undefined, { timeout: 5_000 });
    await waitFor(() => expect(started).toEqual(['audio/mpeg', 'audio/mpeg', 'audio/wav']));
  }, 20_000);

  it('shows the usual duplicate notice when a resend finds the upload already landed', async () => {
    // The first attempt hangs; its resend answers 409 duplicate-audio, naming task 3.
    let attempts = 0;
    server.use(
      http.post('/api/tasks', async () => {
        attempts += 1;
        if (attempts === 1) {
          return await new Promise<Response>(() => {});
        }
        return HttpResponse.json(errorDuplicateAudio, { status: 409 });
      }),
    );
    const stream = openEventStream(snapshotFixture, 1_000);
    renderAt('/queue');
    await screen.findByText('Live');
    const { rowOf } = uploadRows();
    fireEvent.drop(screen.getByRole('button', { name: /drop audio files here/i }), {
      dataTransfer: { files: [new File(['a'], 'a.mp3', { type: 'audio/mpeg' })] },
    });
    await waitFor(() => expect(attempts).toBe(1));

    stream.drop();
    await screen.findByText('Reconnecting…');
    await screen.findByText('Live', undefined, { timeout: 5_000 });
    await waitFor(() => expect(attempts).toBe(2));

    // The first attempt had in fact landed: the usual notice names the existing task.
    const row = rowOf('a.mp3');
    expect(await within(row).findByText(/Already uploaded/)).toBeInTheDocument();
    expect(within(row).getByRole('link', { name: 'open' })).toHaveAttribute('href', '/history/3');
    expect(within(row).queryByText(/Not saved|can't reach/)).not.toBeInTheDocument();
  }, 20_000);

  it("disables the reassign mode's entry, Move and confirmation while the connection is not live", async () => {
    // Item 73's mode landed on main after this branch: the merge note applies the same gate.
    // A merge to undo makes the move ask for confirmation first.
    server.use(http.get('/api/tasks/:id/turns', () => HttpResponse.json(editedTurns)));
    const stream = openEventStream(snapshotFixture, 1_000);
    renderAt('/history/3');
    await screen.findByText('Live');
    const panel = await screen.findByRole('region', { name: 'Speakers' });
    const entry = within(panel).getByRole('button', { name: 'Reassign speakers' });
    expect(entry).toBeEnabled();

    // Live: select a turn and open the target picker, so the mode's Move is rendered.
    fireEvent.click(entry);
    fireEvent.click(screen.getAllByRole('checkbox', { name: /^Select turn starting/ })[0]!);
    fireEvent.click(screen.getByRole('button', { name: 'Move to…' }));
    const barMove = screen.getByRole('button', { name: 'Move' });
    expect(barMove).toBeEnabled();

    // The drop waits the mode's write; leaving the mode is local and brings the entry back.
    stream.drop();
    await screen.findByText('Reconnecting…');
    expect(barMove).toBeDisabled();
    expect(barMove).toHaveAttribute('title', WRITES_UNAVAILABLE);
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    const entryAgain = within(panel).getByRole('button', { name: 'Reassign speakers' });
    expect(entryAgain).toBeDisabled();
    expect(entryAgain).toHaveAttribute('title', WRITES_UNAVAILABLE);

    // Live again: open the confirmation, then drop while it is open.
    await screen.findByText('Live', undefined, { timeout: 5_000 });
    expect(entryAgain).toBeEnabled();
    fireEvent.click(entryAgain);
    fireEvent.click(screen.getAllByRole('checkbox', { name: /^Select turn starting/ })[0]!);
    fireEvent.click(screen.getByRole('button', { name: 'Move to…' }));
    fireEvent.click(screen.getByRole('button', { name: 'Move' }));
    const dialog = await screen.findByRole('dialog');
    stream.drop();
    await screen.findByText('Reconnecting…');
    const confirm = within(dialog).getByRole('button', { name: 'Move' });
    expect(confirm).toBeDisabled();
    expect(confirm).toHaveAttribute('title', WRITES_UNAVAILABLE);
  }, 30_000);

  it('keeps the server message for a real refusal and never sends it again', async () => {
    // The service answers without a duplicate: a real refusal, not a cut-off upload.
    let attempts = 0;
    server.use(
      http.post('/api/tasks', () => {
        attempts += 1;
        return HttpResponse.json(errorInvalidLanguage, { status: 400 });
      }),
    );
    const stream = openEventStream(snapshotFixture, 1_000);
    renderAt('/queue');
    await screen.findByText('Live');
    const { rowOf } = uploadRows();
    fireEvent.drop(screen.getByRole('button', { name: /drop audio files here/i }), {
      dataTransfer: { files: [new File(['a'], 'a.mp3', { type: 'audio/mpeg' })] },
    });
    await waitFor(() => expect(attempts).toBe(1));

    // The server's message stays on the row, which is an error rather than a waiting row.
    expect(
      await within(rowOf('a.mp3')).findByText(errorInvalidLanguage.message),
    ).toBeInTheDocument();

    // A drop and the reconnect do not send it again.
    stream.drop();
    await screen.findByText('Reconnecting…');
    expect(attempts).toBe(1);
    await screen.findByText('Live', undefined, { timeout: 5_000 });
    expect(attempts).toBe(1);
    expect(within(rowOf('a.mp3')).getByText(errorInvalidLanguage.message)).toBeInTheDocument();
  }, 20_000);

  it('ends a transport failure as an error row when the stream stays live, and continues the batch', async () => {
    // The stream never drops: only the upload request fails at the transport.
    let mp3Attempts = 0;
    let wavAttempts = 0;
    server.use(
      http.post('/api/tasks', async ({ request }) => {
        const type = (await request.text()).match(/Content-Type: ([^\r]+)/)?.[1] ?? '';
        if (type === 'audio/mpeg') {
          mp3Attempts += 1;
          return HttpResponse.error();
        }
        wavAttempts += 1;
        return HttpResponse.json({ ...taskQueued, id: 103, filename: 'b.wav' }, { status: 201 });
      }),
    );
    openEventStream(snapshotFixture);
    renderAt('/queue');
    await screen.findByText('Live');
    const { rowOf, rowText } = uploadRows();
    fireEvent.drop(screen.getByRole('button', { name: /drop audio files here/i }), {
      dataTransfer: {
        files: [
          new File(['a'], 'a.mp3', { type: 'audio/mpeg' }),
          new File(['b'], 'b.wav', { type: 'audio/wav' }),
        ],
      },
    });
    await waitFor(() => expect(mp3Attempts).toBe(1));

    // During the grace the row is not an error and the batch behind it has not started.
    expect(rowText('a.mp3')).not.toContain("can't reach");
    expect(wavAttempts).toBe(0);

    // The stream stayed live: after the grace the row names the service and the batch goes on.
    expect(
      await within(rowOf('a.mp3')).findByText(
        "Not saved: can't reach the app's service. Reconnecting…",
        undefined,
        { timeout: 6_000 },
      ),
    ).toBeInTheDocument();
    await waitFor(() => expect(wavAttempts).toBe(1));
    expect(rowText('a.mp3')).not.toContain('queued');
  }, 20_000);

  it('says a waiting upload row is waiting for the connection, never the internal queued', async () => {
    server.use(
      http.post('/api/tasks', async () => {
        return await new Promise<Response>(() => {});
      }),
    );
    const stream = openEventStream(snapshotFixture, 1_000);
    renderAt('/queue');
    await screen.findByText('Live');
    const { rowOf } = uploadRows();
    fireEvent.drop(screen.getByRole('button', { name: /drop audio files here/i }), {
      dataTransfer: { files: [new File(['a'], 'a.mp3', { type: 'audio/mpeg' })] },
    });
    expect(await within(uploadRows().list()).findByText('a.mp3')).toBeInTheDocument();
    stream.drop();
    await screen.findByText('Reconnecting…');

    const row = rowOf('a.mp3');
    expect(row).toHaveTextContent('Waiting for the connection…');
    expect(row).not.toHaveTextContent('queued');
  }, 20_000);

  it('drops the waiting rows when the page is left and stores nothing for them', async () => {
    // The first request never answers; the drop leaves the row waiting.
    let attempts = 0;
    server.use(
      http.post('/api/tasks', async () => {
        attempts += 1;
        return await new Promise<Response>(() => {});
      }),
    );
    const stream = openEventStream(snapshotFixture, 1_000);
    renderAt('/queue');
    await screen.findByText('Live');
    fireEvent.drop(screen.getByRole('button', { name: /drop audio files here/i }), {
      dataTransfer: { files: [new File(['a'], 'a.mp3', { type: 'audio/mpeg' })] },
    });
    await waitFor(() => expect(attempts).toBe(1));
    stream.drop();
    await screen.findByText('Reconnecting…');
    expect(
      within(screen.getByRole('list', { name: 'Uploads' })).getByText('a.mp3'),
    ).toBeInTheDocument();

    // Leaving the queue unmounts its batch: the rows and their File handles go with it.
    fireEvent.click(screen.getByRole('link', { name: 'History' }));
    expect(screen.queryByRole('list', { name: 'Uploads' })).not.toBeInTheDocument();
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);

    // Returning shows no waiting row, and the request is not sent again.
    fireEvent.click(screen.getByRole('link', { name: 'Queue' }));
    expect(screen.queryByRole('list', { name: 'Uploads' })).not.toBeInTheDocument();
    expect(screen.queryByText('a.mp3')).not.toBeInTheDocument();
    expect(attempts).toBe(1);
  }, 20_000);
});
