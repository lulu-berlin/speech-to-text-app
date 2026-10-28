import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { RenderResult } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { ConnectionContext } from '../../app/ConnectionContext';
import { SettingsContext, SettingsSync } from '../../app/SettingsContext';
import {
  error as errorFixture,
  errorDuplicateAudio,
  errorInvalidLanguage,
  errorUploadTooLarge,
  queue as queueFixture,
  taskCanceled,
  taskCompleted,
  taskFailed,
  taskDiarizing,
  taskPaused,
  taskQueued,
  taskRunning,
} from '../../shared/api/fixtures';
import { lightTheme } from '../../shared/theme/theme';
import { asRgb, mediaRulesFor, px, required } from '../../test/dom';
import { controllableEventStream } from '../../test/eventStream';
import { server } from '../../test/server';
import { ThemedProviders } from '../../test/render';
import languages from '../../../../docs/api/languages.json';
import settingsFallback from '../../../../docs/api/settings.fallback.json';
import settingsFixed from '../../../../docs/api/settings.fixed.json';
import settingsUpdatedFallback from '../../../../docs/api/settings-updated.fallback.json';
import { QueueView } from './QueueView';

function renderQueue() {
  return render(
    <ThemedProviders>
      <ConnectionContext.Provider>
        <SettingsContext.Provider>
          <SettingsSync />
          <MemoryRouter>
            <QueueView />
          </MemoryRouter>
        </SettingsContext.Provider>
      </ConnectionContext.Provider>
    </ThemedProviders>,
  );
}

// Reads one text field out of a multipart body (the upload options fields).
function multipartField(body: string, name: string): string {
  const match = body.match(new RegExp(`name="${name}"\\r\\n\\r\\n([^\\r]*)\\r\\n`));
  return match?.[1] ?? '';
}

// jsdom has no layout, so dnd-kit would measure every droppable at 0x0 and a keyboard
// drag could not move past it. Each element is placed 40px below its previous sibling,
// which gives the queue rows their positions. Returns the restore function.
function stubRowLayout() {
  const original = Element.prototype.getBoundingClientRect;
  Element.prototype.getBoundingClientRect = function getBoundingClientRect(this: Element): DOMRect {
    const index = Array.prototype.indexOf.call(this.parentElement?.children ?? [], this);
    const top = index * 40;
    const rect = {
      x: 0,
      y: top,
      top,
      bottom: top + 40,
      left: 0,
      right: 200,
      width: 200,
      height: 40,
    };
    return { ...rect, toJSON: () => rect } as DOMRect;
  };
  return () => {
    Element.prototype.getBoundingClientRect = original;
  };
}

// The upload options sit in a disclosure under the drop zone (item 59); these tests open
// it before reaching the fields.
function openUploadOptions() {
  fireEvent.click(screen.getByRole('button', { name: 'Upload options' }));
}

describe('the queue view upload options', () => {
  it('offers the languages from docs/api/languages.json with auto as the default, and bounds speakers and max speakers to 1–20', () => {
    renderQueue();
    openUploadOptions();

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

describe('the queue view upload defaults', () => {
  it('starts the upload options with the stored default language, speakers mode and max speakers', async () => {
    // settings.fixed.json: German, 3 fixed speakers, 12 max speakers (item 32).
    server.use(http.get('/api/settings', () => HttpResponse.json(settingsFixed)));
    renderQueue();
    openUploadOptions();

    await waitFor(() =>
      expect(screen.getByRole('combobox', { name: 'Language' })).toHaveValue('german'),
    );
    expect(screen.getByLabelText('Speakers')).toHaveValue('fixed');
    expect(screen.getByLabelText('Speaker count')).toHaveValue(3);
    fireEvent.click(screen.getByRole('button', { name: /advanced/i }));
    expect(screen.getByLabelText('Max speakers')).toHaveValue(12);
  });
});

describe('the queue view CPU-fallback banner', () => {
  it('names the unavailable GPU and the switch to the CPU until it is dismissed', async () => {
    // settings.fallback.json: the device is cpu and the notice names gpu:1 (item 32).
    let dismissals = 0;
    server.use(
      http.get('/api/settings', () => HttpResponse.json(settingsFallback)),
      http.delete('/api/settings/device-fallback', () => {
        dismissals += 1;
        return HttpResponse.json({ ...settingsFallback, deviceFallback: null });
      }),
    );
    renderQueue();

    const banner = await screen.findByRole('alert');
    expect(banner).toHaveTextContent('GPU 1 was unavailable');
    expect(banner).toHaveTextContent('CPU');

    fireEvent.click(within(banner).getByRole('button', { name: 'Dismiss' }));
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
    expect(dismissals).toBe(1);
  });

  it('says so when dismissing the notice fails, and leaves it to try again', async () => {
    server.use(
      http.get('/api/settings', () => HttpResponse.json(settingsFallback)),
      http.delete('/api/settings/device-fallback', () =>
        HttpResponse.json(errorFixture, { status: 500 }),
      ),
    );
    renderQueue();

    // The banner stays (the notice is still stored) and the failure is announced, rather
    // than the click vanishing silently (item 72).
    const banner = await screen.findByRole('alert');
    fireEvent.click(within(banner).getByRole('button', { name: 'Dismiss' }));
    expect(await screen.findByText('Could not dismiss the notice')).toBeInTheDocument();
    expect(screen.getByText('Task 42 does not exist')).toBeInTheDocument();
    expect(screen.getByText(/was unavailable/)).toBeInTheDocument();
    // The failed dismissal settles back to idle: the user can try again.
    await waitFor(() =>
      expect(within(banner).getByRole('button', { name: 'Dismiss' })).toBeEnabled(),
    );
  });

  it('appears when a settings-updated event brings a notice, without a reload', async () => {
    const events = controllableEventStream();
    renderQueue();
    await screen.findByText('lecture.m4a');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    // settings-updated.fallback.json: the engine switched gpu:1 to the CPU. The name comes
    // from the fetched settings (settings.json's gpuNames), not the event.
    events.send('settings-updated', settingsUpdatedFallback);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'GPU 1 — AMD Radeon RX 6600 was unavailable',
    );
  });
});

describe('the queue view upload flow', () => {
  it('keeps a finished upload row until the backend confirms the task', async () => {
    renderQueue();
    await screen.findByText('lecture.m4a');

    const files = [new File(['x'], 'a.mp3', { type: 'audio/mpeg' })];
    fireEvent.drop(screen.getByRole('button', { name: /drop/i }), { dataTransfer: { files } });

    // The upload completes; the mock's fresh id is not in the live state and the mock
    // stream never announces it, so the row stays in the upload list instead of
    // vanishing (the task leaves it only once the backend confirms it in the queue).
    await waitFor(() => expect(screen.getByText('done')).toBeInTheDocument());
    expect(
      within(screen.getByRole('list', { name: 'Uploads' })).getByText('a.mp3'),
    ).toBeInTheDocument();

    // The pending list is unchanged.
    const rows = within(screen.getByRole('list', { name: 'Pending tasks' })).getAllByRole(
      'listitem',
    );
    expect(rows.map((row) => row.textContent)).toEqual([
      expect.stringContaining('lecture.m4a'),
      expect.stringContaining('memo.wav'),
    ]);
  });
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
    openUploadOptions();

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

  it('permits a drop in a real browser: dragOver is default-prevented, and dropping still enqueues', async () => {
    const arrivals: string[] = [];
    server.use(
      http.post('/api/tasks', async ({ request }) => {
        const body = await request.text();
        arrivals.push(body.match(/Content-Type: ([^\r]+)/)?.[1] ?? '');
        return HttpResponse.json(taskQueued, { status: 201 });
      }),
    );
    renderQueue();
    const dropZone = screen.getByRole('button', { name: /drop audio/i });

    // A browser only permits a drop after a dragOver whose default is prevented (jsdom's
    // synthetic drop skips that gate, which is why the other drop tests were green), and
    // the zone asks for a copy drop.
    const dragData = { dropEffect: 'none', files: [] };
    expect(fireEvent.dragOver(dropZone, { dataTransfer: dragData })).toBe(false);
    expect(dragData.dropEffect).toBe('copy');

    const files = [new File(['x'], 'a.mp3', { type: 'audio/mpeg' })];
    fireEvent.drop(dropZone, { dataTransfer: { files } });
    await waitFor(() => expect(arrivals).toEqual(['audio/mpeg']));
  });

  it("gives an uploading file's progress bar a fixed width beside the filename, and the full row width on a phone", async () => {
    // A fake XHR that reports 50% sent and never completes, so the row stays uploading.
    class StalledXhr {
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
      }
    }
    vi.stubGlobal('XMLHttpRequest', StalledXhr);
    try {
      renderQueue();
      const files = [new File(['x'], 'a.mp3', { type: 'audio/mpeg' })];
      fireEvent.drop(screen.getByRole('button', { name: /drop/i }), { dataTransfer: { files } });
      const uploads = screen.getByRole('list', { name: 'Uploads' });
      const bar = await within(uploads).findByRole('progressbar', { name: /a\.mp3/i });

      // The bar's slot beside the filename has a width of its own (16rem) and does not
      // shrink, so the track does not collapse to its border.
      const slot = required(required(bar.parentElement).parentElement) as HTMLElement;
      expect(getComputedStyle(slot).width).toBe(px('16rem'));
      expect(getComputedStyle(slot).flexShrink).toBe('0');

      // On a phone (the row stacks below the small breakpoint) the slot spans the row.
      const narrow = mediaRulesFor(slot, `(max-width: ${lightTheme.breakpoints.sm})`);
      expect(narrow.map((rule) => rule.style.width)).toContain('100%');
    } finally {
      vi.unstubAllGlobals();
    }
  });
});

describe('the empty queue', () => {
  it('turns the drop zone into the empty state once live with no tasks, and back to its compact form when a task arrives', async () => {
    const events = controllableEventStream({ tasks: [], queue: { paused: false, taskIds: [] } });
    const pick = vi.spyOn(HTMLInputElement.prototype, 'click').mockImplementation(() => {});

    renderQueue();

    // Before the snapshot the shell's connecting region is the loading state: the view
    // does not claim yet that the queue is empty.
    expect(screen.queryByText(/No tasks yet/)).not.toBeInTheDocument();

    // Live with no tasks: the drop zone carries the one line of guidance and leads with
    // the mascot at the empty-state size (decorative).
    const zone = await screen.findByRole('button', {
      name: 'No tasks yet — drop audio files here, or click to choose them',
    });
    const mascot = required(zone.querySelector('svg'));
    expect(getComputedStyle(mascot).width).toBe(px(lightTheme.sizes.stenoEmpty));
    expect(mascot).toHaveAttribute('aria-hidden', 'true');

    // The zone itself is the primary action: clicking it opens the file picker.
    fireEvent.click(zone);
    expect(pick).toHaveBeenCalledTimes(1);

    // A task arrives: the zone returns to its compact form, without the mascot.
    events.send('task-updated', taskQueued);
    events.send('queue-updated', { paused: false, taskIds: [taskQueued.id] });
    const compact = await screen.findByRole('button', {
      name: 'Drop audio files here or click to choose',
    });
    expect(compact.querySelector('svg')).toBeNull();
    pick.mockRestore();
  });
});

describe('the drop zone while dragging', () => {
  it('shows a solid accent border, the accent tint and "Release to upload" while files are over it, through its children, until they leave or drop', async () => {
    controllableEventStream({ tasks: [], queue: { paused: false, taskIds: [] } });
    renderQueue();
    const idleName = 'No tasks yet — drop audio files here, or click to choose them';
    const zone = await screen.findByRole('button', { name: idleName });
    const mascot = required(zone.querySelector('svg')) as SVGElement;
    const idle = getComputedStyle(zone);
    expect(idle.borderTopStyle).toBe('dashed');
    expect(idle.backgroundColor).toBe(asRgb(lightTheme.colors.surface));

    // Files enter the zone: the drag-over look and text; the mascot stays.
    fireEvent.dragEnter(zone, { dataTransfer: { types: ['Files'], files: [] } });
    expect(zone).toHaveAccessibleName('Release to upload');
    expect(zone).toContainElement(mascot);
    const over = getComputedStyle(zone);
    expect(over.borderTopStyle).toBe('solid');
    expect(over.borderTopColor).toBe(asRgb(lightTheme.colors.accent));
    expect(over.backgroundColor).toBe(asRgb(lightTheme.colors.accentTint));

    // Moving over the mascot (a child) and back off it does not end the drag-over state.
    fireEvent.dragEnter(mascot, { dataTransfer: { types: ['Files'], files: [] } });
    fireEvent.dragLeave(mascot);
    expect(zone).toHaveAccessibleName('Release to upload');

    // Leaving the zone ends it.
    fireEvent.dragLeave(zone);
    expect(zone).toHaveAccessibleName(idleName);
    expect(getComputedStyle(zone).borderTopStyle).toBe('dashed');

    // So does a drop.
    fireEvent.dragEnter(zone, { dataTransfer: { types: ['Files'], files: [] } });
    expect(zone).toHaveAccessibleName('Release to upload');
    fireEvent.drop(zone, { dataTransfer: { files: [] } });
    expect(zone).toHaveAccessibleName(idleName);
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

  it('shows one status line with the phase and percentage, no RUNNING badge, one bar while transcribing, and stacks the row on a narrow screen', async () => {
    controllableEventStream();

    renderQueue();

    // The running task's row (docs/api/snapshot.json: RUNNING, TRANSCRIBING, 42%,
    // diarization not started) carries one line instead of a badge: the phase and the
    // percentage (item 59).
    await screen.findByText('lecture.m4a');
    const lecture = screen.getByText('lecture.m4a').closest('li') as HTMLElement;
    expect(within(lecture).getByText(/Transcribing… 42%/)).toBeInTheDocument();
    expect(within(lecture).queryByText('RUNNING')).not.toBeInTheDocument();
    expect(within(lecture).getByRole('progressbar', { name: 'Transcription' })).toBeInTheDocument();
    // Chunking and transcribing keep one bar; the diarization bar joins only while
    // diarizing (item 59).
    expect(
      within(lecture).queryByRole('progressbar', { name: 'Speaker diarization' }),
    ).not.toBeInTheDocument();

    // Below the small breakpoint the row's top line becomes a column: the filename on its
    // own line, the phase, status and controls on the line below, left-aligned.
    const topLine = required(screen.getByText('lecture.m4a').parentElement);
    const narrowRules = mediaRulesFor(topLine, `(max-width: ${lightTheme.breakpoints.sm})`);
    expect(narrowRules.map((rule) => rule.style.flexDirection)).toContain('column');
    expect(narrowRules.map((rule) => rule.style.alignItems)).toContain('flex-start');
  });

  it('shows the diarization bar only while diarizing: its stage and progress update, and it hides when diarization does not apply', async () => {
    const events = controllableEventStream();

    renderQueue();

    // The snapshot's task is TRANSCRIBING (diarizationProgress 0.0, stage null): only the
    // transcription bar shows, never a second empty one (item 59).
    await screen.findByText('lecture.m4a');
    const lecture = screen.getByText('lecture.m4a').closest('li') as HTMLElement;
    expect(within(lecture).getByRole('progressbar', { name: 'Transcription' })).toHaveAttribute(
      'aria-valuenow',
      '42',
    );
    expect(
      within(lecture).queryByRole('progressbar', { name: 'Speaker diarization' }),
    ).not.toBeInTheDocument();

    // The task reaching its diarizing phase brings the bar in, with its stage.
    events.send('task-updated', { ...taskDiarizing, id: 5, filename: 'lecture.m4a' });
    await waitFor(() =>
      expect(
        within(lecture).getByRole('progressbar', { name: 'Speaker diarization' }),
      ).toHaveAttribute('aria-valuetext', 'Clustering 37%'),
    );

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
    ).toHaveAttribute('aria-valuetext', 'Clustering 40%');

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
    expect(within(lecture).getByText(/Transcribing… 42%/)).toBeInTheDocument();
    expect(within(lecture).queryByText('RUNNING')).not.toBeInTheDocument();
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

  it('resumes a paused task: the resume control calls the resume endpoint', async () => {
    const events = controllableEventStream();
    const resumes: string[] = [];
    server.use(
      http.post('/api/tasks/:id/resume', ({ params }) => {
        resumes.push(String(params.id));
        // The revised command mock answers with the requested task, resumed: memo.wav
        // (task 7) is QUEUED.
        return HttpResponse.json(taskQueued);
      }),
    );

    renderQueue();

    // Pause task 7 first: resume is offered only while the task is paused.
    await screen.findByText('memo.wav');
    const memo = screen.getByText('memo.wav').closest('li') as HTMLElement;
    events.send('task-updated', taskPaused);
    await waitFor(() =>
      expect(within(memo).getByRole('button', { name: 'Resume memo.wav' })).toBeInTheDocument(),
    );
    expect(within(memo).getByText('PAUSED')).toBeInTheDocument();

    fireEvent.click(within(memo).getByRole('button', { name: 'Resume memo.wav' }));

    // The command's response puts the task back to QUEUED; the resume control disappears.
    await waitFor(() => expect(within(memo).getByText('QUEUED')).toBeInTheDocument());
    expect(within(memo).queryByRole('button', { name: 'Resume memo.wav' })).not.toBeInTheDocument();
    expect(resumes).toEqual(['7']);
  });

  it('runs a second command on the same row: pause, then resume, calls both endpoints', async () => {
    // Item 40 (discovered): after one successful command the row must still accept the
    // next one. The stream sends nothing after the snapshot, so the row's state comes
    // from the commands' responses.
    const calls: string[] = [];
    server.use(
      http.post('/api/tasks/:id/pause', ({ params }) => {
        calls.push(`pause ${String(params.id)}`);
        return HttpResponse.json({ ...taskRunning, status: 'PAUSED' });
      }),
      http.post('/api/tasks/:id/resume', ({ params }) => {
        calls.push(`resume ${String(params.id)}`);
        return HttpResponse.json(taskRunning);
      }),
    );

    renderQueue();

    await screen.findByText('lecture.m4a');
    const lecture = screen.getByText('lecture.m4a').closest('li') as HTMLElement;
    fireEvent.click(within(lecture).getByRole('button', { name: 'Pause lecture.m4a' }));
    await waitFor(() => expect(within(lecture).getByText('PAUSED')).toBeInTheDocument());

    fireEvent.click(within(lecture).getByRole('button', { name: 'Resume lecture.m4a' }));

    // The resume response puts the task back to RUNNING; pause is offered again.
    await waitFor(() =>
      expect(within(lecture).getByText(/Transcribing… \d+%/)).toBeInTheDocument(),
    );
    expect(within(lecture).getByRole('button', { name: 'Pause lecture.m4a' })).toBeInTheDocument();
    expect(calls).toEqual(['pause 5', 'resume 5']);
  });

  it("disables the row's commands while one is in flight, and enables them once it settles, successfully or not", async () => {
    // Item 81: the buttons follow the request, not the task's status. Each command's
    // response is held until the test releases it.
    const pauseAnswer = Promise.withResolvers<void>();
    const resumeAnswer = Promise.withResolvers<void>();
    server.use(
      http.post('/api/tasks/:id/pause', async () => {
        await pauseAnswer.promise;
        return HttpResponse.json({ ...taskRunning, status: 'PAUSED' });
      }),
      // 409 illegal-transition has no docs/api example, so error.json stands in.
      http.post('/api/tasks/:id/resume', async () => {
        await resumeAnswer.promise;
        return HttpResponse.json(errorFixture, { status: 409 });
      }),
    );

    renderQueue();
    await screen.findByText('lecture.m4a');
    const lecture = screen.getByText('lecture.m4a').closest('li') as HTMLElement;

    // In flight: both of the running task's commands are disabled.
    fireEvent.click(within(lecture).getByRole('button', { name: 'Pause lecture.m4a' }));
    await waitFor(() =>
      expect(within(lecture).getByRole('button', { name: 'Pause lecture.m4a' })).toBeDisabled(),
    );
    expect(within(lecture).getByRole('button', { name: 'Cancel lecture.m4a' })).toBeDisabled();

    // Settled successfully: the paused task's commands are enabled.
    pauseAnswer.resolve();
    await waitFor(() => expect(within(lecture).getByText('PAUSED')).toBeInTheDocument());
    expect(within(lecture).getByRole('button', { name: 'Resume lecture.m4a' })).toBeEnabled();
    expect(within(lecture).getByRole('button', { name: 'Cancel lecture.m4a' })).toBeEnabled();

    // In flight again, then settled with a failure: enabled again, beside the error.
    fireEvent.click(within(lecture).getByRole('button', { name: 'Resume lecture.m4a' }));
    await waitFor(() =>
      expect(within(lecture).getByRole('button', { name: 'Resume lecture.m4a' })).toBeDisabled(),
    );
    expect(within(lecture).getByRole('button', { name: 'Cancel lecture.m4a' })).toBeDisabled();

    resumeAnswer.resolve();
    await waitFor(() =>
      expect(within(lecture).getByText('Task 42 does not exist')).toBeInTheDocument(),
    );
    expect(within(lecture).getByRole('button', { name: 'Resume lecture.m4a' })).toBeEnabled();
    expect(within(lecture).getByRole('button', { name: 'Cancel lecture.m4a' })).toBeEnabled();
  });

  it('confirms cancel only when work would be lost, then calls the cancel endpoint', async () => {
    const events = controllableEventStream();
    const canceled: string[] = [];
    server.use(
      http.post('/api/tasks/:id/cancel', ({ params }) => {
        canceled.push(String(params.id));
        return HttpResponse.json({ ...taskRunning, id: Number(params.id), status: 'CANCELED' });
      }),
    );

    renderQueue();
    await screen.findByText('lecture.m4a');

    // A queued task cancels directly, without asking.
    const memo = screen.getByText('memo.wav').closest('li') as HTMLElement;
    fireEvent.click(within(memo).getByRole('button', { name: 'Cancel memo.wav' }));
    await waitFor(() => expect(canceled).toEqual(['7']));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByText('memo.wav')).not.toBeInTheDocument();

    // A running task asks for confirmation first.
    const lecture = screen.getByText('lecture.m4a').closest('li') as HTMLElement;
    fireEvent.click(within(lecture).getByRole('button', { name: 'Cancel lecture.m4a' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Cancel lecture.m4a?')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel task' }));
    await waitFor(() => expect(canceled).toEqual(['7', '5']));
    expect(screen.queryByText('lecture.m4a')).not.toBeInTheDocument();

    // A paused task that has started also asks for confirmation.
    events.send('task-updated', { ...taskPaused, id: 9, filename: 'notes.mp3' });
    events.send('queue-updated', { ...queueFixture, taskIds: [9] });
    const notesRow = (await screen.findByText('notes.mp3')).closest('li') as HTMLElement;
    fireEvent.click(within(notesRow).getByRole('button', { name: 'Cancel notes.mp3' }));
    const pauseDialog = await screen.findByRole('dialog');
    fireEvent.click(within(pauseDialog).getByRole('button', { name: 'Cancel task' }));
    await waitFor(() => expect(canceled).toEqual(['7', '5', '9']));
    expect(screen.queryByText('notes.mp3')).not.toBeInTheDocument();

    // A paused task that never started cancels directly.
    events.send('task-updated', { ...taskPaused, id: 10, filename: 'draft.wav', startedAt: null });
    events.send('queue-updated', { ...queueFixture, taskIds: [10] });
    const draftRow = (await screen.findByText('draft.wav')).closest('li') as HTMLElement;
    fireEvent.click(within(draftRow).getByRole('button', { name: 'Cancel draft.wav' }));
    await waitFor(() => expect(canceled).toEqual(['7', '5', '9', '10']));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByText('draft.wav')).not.toBeInTheDocument();
  });

  it('deletes a task only after confirmation: the delete endpoint is called and the row leaves', async () => {
    const deleted: string[] = [];
    server.use(
      http.delete('/api/tasks/:id', ({ params }) => {
        deleted.push(String(params.id));
        return new HttpResponse(null, { status: 204 });
      }),
    );

    renderQueue();
    await screen.findByText('lecture.m4a');

    // Delete (in the row's menu) always asks for confirmation: cancelling the dialog
    // sends no request.
    const lecture = screen.getByText('lecture.m4a').closest('li') as HTMLElement;
    const openMenu = () => {
      fireEvent.pointerDown(
        within(lecture).getByRole('button', { name: 'Actions for lecture.m4a' }),
        { button: 0 },
      );
    };
    openMenu();
    await screen.findByRole('menu');
    fireEvent.click(screen.getByRole('menuitem', { name: 'Delete' }));
    const cancelDialog = await screen.findByRole('dialog');
    fireEvent.click(within(cancelDialog).getByRole('button', { name: 'Cancel' }));
    expect(deleted).toEqual([]);
    expect(screen.getByText('lecture.m4a')).toBeInTheDocument();

    // Confirming deletes it: the endpoint is called and the row leaves the list.
    openMenu();
    await screen.findByRole('menu');
    fireEvent.click(screen.getByRole('menuitem', { name: 'Delete' }));
    const confirmDialog = await screen.findByRole('dialog');
    fireEvent.click(within(confirmDialog).getByRole('button', { name: 'Delete task' }));
    await waitFor(() => expect(deleted).toEqual(['5']));
    expect(screen.queryByText('lecture.m4a')).not.toBeInTheDocument();

    // A queued task goes through the same confirmation.
    const memo = screen.getByText('memo.wav').closest('li') as HTMLElement;
    fireEvent.pointerDown(within(memo).getByRole('button', { name: 'Actions for memo.wav' }), {
      button: 0,
    });
    await screen.findByRole('menu');
    fireEvent.click(screen.getByRole('menuitem', { name: 'Delete' }));
    const memoDialog = await screen.findByRole('dialog');
    fireEvent.click(within(memoDialog).getByRole('button', { name: 'Delete task' }));
    await waitFor(() => expect(deleted).toEqual(['5', '7']));
    expect(screen.queryByText('memo.wav')).not.toBeInTheDocument();
  });

  it('offers no controls for transitions the table does not allow', async () => {
    const events = controllableEventStream();
    renderQueue();
    await screen.findByText('lecture.m4a');

    const lecture = screen.getByText('lecture.m4a').closest('li') as HTMLElement;
    const memo = screen.getByText('memo.wav').closest('li') as HTMLElement;

    // RUNNING (docs/api/task-transitions.json): pause and cancel, no resume.
    expect(within(lecture).getByRole('button', { name: 'Pause lecture.m4a' })).toBeInTheDocument();
    expect(
      within(lecture).queryByRole('button', { name: 'Resume lecture.m4a' }),
    ).not.toBeInTheDocument();
    expect(within(lecture).getByRole('button', { name: 'Cancel lecture.m4a' })).toBeInTheDocument();

    // QUEUED: pause and cancel, no resume.
    expect(within(memo).getByRole('button', { name: 'Pause memo.wav' })).toBeInTheDocument();
    expect(within(memo).queryByRole('button', { name: 'Resume memo.wav' })).not.toBeInTheDocument();
    expect(within(memo).getByRole('button', { name: 'Cancel memo.wav' })).toBeInTheDocument();

    // PAUSED: resume and cancel, no pause.
    events.send('task-updated', taskPaused);
    await waitFor(() => expect(within(memo).getByText('PAUSED')).toBeInTheDocument());
    expect(within(memo).queryByRole('button', { name: 'Pause memo.wav' })).not.toBeInTheDocument();
    expect(within(memo).getByRole('button', { name: 'Resume memo.wav' })).toBeInTheDocument();
    expect(within(memo).getByRole('button', { name: 'Cancel memo.wav' })).toBeInTheDocument();
  });
});

describe('the queue reorder', () => {
  it('sends the full ordered id list to PUT /api/queue/order from a drag or a menu move', async () => {
    const ordered: number[][] = [];
    server.use(
      http.put('/api/queue/order', async ({ request }) => {
        const body = (await request.json()) as { taskIds: number[] };
        ordered.push(body.taskIds);
        return HttpResponse.json({ paused: false, taskIds: body.taskIds });
      }),
    );

    // Dragging: the queue grows by keyboard (Space to grab, ArrowDown to move, Space to
    // drop) with stubbed row rects, since jsdom has no layout.
    const restoreLayout = stubRowLayout();
    let dragged: RenderResult | undefined;
    try {
      dragged = renderQueue();
      await screen.findByText('lecture.m4a');
      const handle = screen.getByRole('button', { name: 'Reorder lecture.m4a' });
      handle.focus();
      fireEvent.keyDown(handle, { code: 'Space', key: ' ' });
      await new Promise((resolve) => setTimeout(resolve, 50));
      fireEvent.keyDown(handle, { code: 'ArrowDown', key: 'ArrowDown' });
      await new Promise((resolve) => setTimeout(resolve, 50));
      fireEvent.keyDown(handle, { code: 'Space', key: ' ' });
      await waitFor(() => expect(ordered).toEqual([[7, 5]]));
      const draggedRows = screen.getAllByRole('listitem');
      expect(draggedRows.map((row) => row.textContent)).toEqual([
        expect.stringContaining('memo.wav'),
        expect.stringContaining('lecture.m4a'),
      ]);
    } finally {
      dragged?.unmount();
      restoreLayout();
    }

    // Menu: move memo.wav to the top, then lecture.m4a back up.
    renderQueue();
    await screen.findByText('lecture.m4a');
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Actions for memo.wav' }), {
      button: 0,
    });
    await screen.findByRole('menu');
    fireEvent.click(screen.getByRole('menuitem', { name: 'Move to top' }));
    await waitFor(() =>
      expect(ordered).toEqual([
        [7, 5],
        [7, 5],
      ]),
    );
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Actions for lecture.m4a' }), {
      button: 0,
    });
    await screen.findByRole('menu');
    fireEvent.click(screen.getByRole('menuitem', { name: 'Move up' }));
    await waitFor(() =>
      expect(ordered).toEqual([
        [7, 5],
        [7, 5],
        [5, 7],
      ]),
    );
    const menuRows = screen.getAllByRole('listitem');
    expect(menuRows.map((row) => row.textContent)).toEqual([
      expect.stringContaining('lecture.m4a'),
      expect.stringContaining('memo.wav'),
    ]);
  });

  it('lifts the dragged row while it moves: raised above its neighbours, which slide aside, until the drop settles both', async () => {
    server.use(
      http.put('/api/queue/order', async ({ request }) => {
        const body = (await request.json()) as { taskIds: number[] };
        return HttpResponse.json({ paused: false, taskIds: body.taskIds });
      }),
    );
    const settle = () => new Promise((resolve) => setTimeout(resolve, 50));
    const restoreLayout = stubRowLayout();
    try {
      renderQueue();
      await screen.findByText('lecture.m4a');
      const lecture = screen.getByText('lecture.m4a').closest('li') as HTMLElement;
      const memo = screen.getByText('memo.wav').closest('li') as HTMLElement;
      const handle = screen.getByRole('button', { name: 'Reorder lecture.m4a' });

      // Picked up by keyboard: the row is raised — the raised fill, the card shadow, and
      // stacked above its neighbours. Keyboard focus is on its handle, so the row keeps the
      // focus ring beside the shadow.
      handle.focus();
      fireEvent.keyDown(handle, { code: 'Space', key: ' ' });
      await settle();
      const lifted = getComputedStyle(lecture);
      expect(lifted.backgroundColor).toBe(asRgb(lightTheme.colors.surfaceRaised));
      expect(lifted.boxShadow).toContain(lightTheme.shadows.raised);
      expect(lifted.position).toBe('relative');
      expect(lifted.zIndex).toBe('1');

      // Moved one place down: the row follows (the stubbed rows are 40px apart) and its
      // neighbour slides up into the gap — animated only when motion is welcome.
      fireEvent.keyDown(handle, { code: 'ArrowDown', key: 'ArrowDown' });
      await settle();
      expect(lecture.style.top).toBe('40px');
      expect(memo.style.top).toBe('-40px');
      const slide = mediaRulesFor(memo, '(prefers-reduced-motion: no-preference)');
      expect(slide.map((rule) => rule.style.transitionProperty)).toContain('top,left');

      // Dropped: both settle, and the lift ends.
      fireEvent.keyDown(handle, { code: 'Space', key: ' ' });
      await settle();
      const rows = screen.getAllByRole('listitem');
      for (const row of rows) {
        expect(row.style.top).toBe('');
        expect(getComputedStyle(row).boxShadow).not.toBe(lightTheme.shadows.card);
      }
    } finally {
      restoreLayout();
    }
  });

  it('restores the server order and shows the error when a reorder is rejected', async () => {
    const ordered: number[][] = [];
    server.use(
      http.put('/api/queue/order', async ({ request }) => {
        const body = (await request.json()) as { taskIds: number[] };
        ordered.push(body.taskIds);
        // 409 reorder-mismatch: docs/api has no example for it, so error.json stands in.
        return HttpResponse.json(errorFixture, { status: 409 });
      }),
    );

    renderQueue();
    await screen.findByText('lecture.m4a');

    fireEvent.pointerDown(screen.getByRole('button', { name: 'Actions for memo.wav' }), {
      button: 0,
    });
    await screen.findByRole('menu');
    fireEvent.click(screen.getByRole('menuitem', { name: 'Move to top' }));

    // The server rejected the new order: the rows fall back to the old order and the
    // error message is shown.
    await waitFor(() => expect(ordered).toEqual([[7, 5]]));
    await screen.findByText('Task 42 does not exist');
    const menuRows = screen.getAllByRole('listitem');
    expect(menuRows.map((row) => row.textContent)).toEqual([
      expect.stringContaining('lecture.m4a'),
      expect.stringContaining('memo.wav'),
    ]);
  });
});

describe('the queue pause', () => {
  it('pauses and resumes the queue with the switch, reflecting the queue state', async () => {
    const calls: string[] = [];
    server.use(
      http.post('/api/queue/pause', () => {
        calls.push('pause');
        return HttpResponse.json({ paused: true, taskIds: queueFixture.taskIds });
      }),
      http.post('/api/queue/resume', () => {
        calls.push('resume');
        return HttpResponse.json({ paused: false, taskIds: queueFixture.taskIds });
      }),
    );

    renderQueue();
    await screen.findByText('lecture.m4a');

    const toggle = screen.getByRole('switch', { name: 'Pause the queue' });
    expect(toggle).toHaveAttribute('aria-checked', 'false');

    fireEvent.click(toggle);
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'true'));
    expect(calls).toEqual(['pause']);

    fireEvent.click(toggle);
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'false'));
    expect(calls).toEqual(['pause', 'resume']);
  });

  it("shows a failed pause's error beside the switch and leaves the switch at the queue's state", async () => {
    // 409 queue-not-paused has no docs/api example, so error.json stands in.
    server.use(
      http.post('/api/queue/pause', () => HttpResponse.json(errorFixture, { status: 409 })),
    );

    renderQueue();
    await screen.findByText('lecture.m4a');

    const toggle = screen.getByRole('switch', { name: 'Pause the queue' });
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    fireEvent.click(toggle);

    // The rejection is an alert beside the control, naming the command and the server's message.
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not pause the queue');
    expect(alert).toHaveTextContent('Task 42 does not exist');

    // The queue never paused, so the switch shows that real state and is usable again.
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    expect(toggle).toBeEnabled();
  });

  it("shows a failed resume's error beside the switch and clears it at the next command", async () => {
    const calls: string[] = [];
    let resumes = 0;
    server.use(
      http.post('/api/queue/pause', () => {
        calls.push('pause');
        return HttpResponse.json({ ...queueFixture, paused: true });
      }),
      http.post('/api/queue/resume', () => {
        calls.push('resume');
        resumes += 1;
        // The first resume is rejected (409 queue-not-paused has no docs/api example, so
        // error.json stands in); the retry is served.
        return resumes === 1
          ? HttpResponse.json(errorFixture, { status: 409 })
          : HttpResponse.json({ ...queueFixture, paused: false });
      }),
    );

    renderQueue();
    await screen.findByText('lecture.m4a');
    const toggle = screen.getByRole('switch', { name: 'Pause the queue' });

    // Pause first, so the failed resume starts from a paused queue.
    fireEvent.click(toggle);
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'true'));

    fireEvent.click(toggle);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not resume the queue');
    expect(alert).toHaveTextContent('Task 42 does not exist');
    // The queue never resumed, so the switch still shows it paused.
    expect(toggle).toHaveAttribute('aria-checked', 'true');
    expect(calls).toEqual(['pause', 'resume']);

    // The switch is the retry: the next command clears the message, and its own answer lands.
    fireEvent.click(toggle);
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'false'));
    expect(calls).toEqual(['pause', 'resume', 'resume']);
  });
});

describe('finished tasks', () => {
  it('shows a notice with a link when a task leaves the queue finished, and the row leaves', async () => {
    const events = controllableEventStream();
    renderQueue();
    await screen.findByText('lecture.m4a');

    // RUNNING -> COMPLETED: a transcript link.
    events.send('task-updated', { ...taskCompleted, id: 5, filename: 'lecture.m4a' });
    await screen.findByRole('link', { name: 'open' });
    expect(screen.getByText(/lecture\.m4a transcribed/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'open' })).toHaveAttribute('href', '/history/5');

    // A fresh task, queued -> running -> failed (transitions stay in the table): a
    // History link.
    events.send('task-updated', { ...taskQueued, id: 9, filename: 'notes.mp3' });
    events.send('queue-updated', { ...queueFixture, taskIds: [9] });
    await screen.findByText('notes.mp3');
    events.send('task-updated', { ...taskRunning, id: 9, filename: 'notes.mp3' });
    events.send('task-updated', { ...taskFailed, id: 9, filename: 'notes.mp3' });
    await screen.findByText(/notes\.mp3 failed/);

    // Queued -> canceled (another fresh task): a History link too.
    events.send('task-updated', { ...taskQueued, id: 10, filename: 'draft.wav' });
    events.send('queue-updated', { ...queueFixture, taskIds: [10] });
    await screen.findByText('draft.wav');
    events.send('task-updated', { ...taskCanceled, id: 10, filename: 'draft.wav' });
    await screen.findByText(/draft\.wav canceled/);

    // Every finished task's notice links to History (completed links to its transcript).
    const historyLinks = screen.getAllByRole('link', { name: 'see History' });
    expect(historyLinks).toHaveLength(2);
    for (const link of historyLinks) {
      expect(link).toHaveAttribute('href', '/history');
    }

    // The finished rows left the queue list (the notices are not rows).
    expect(screen.queryAllByRole('listitem')).toHaveLength(0);
  });
});

describe('failed commands', () => {
  it("shows a failed command's error on the task's row until the next action", async () => {
    // 409 illegal-transition has no docs/api example, so error.json stands in.
    server.use(
      http.post('/api/tasks/:id/pause', () => HttpResponse.json(errorFixture, { status: 409 })),
    );

    renderQueue();
    await screen.findByText('lecture.m4a');
    const lecture = screen.getByText('lecture.m4a').closest('li') as HTMLElement;

    fireEvent.click(within(lecture).getByRole('button', { name: 'Pause lecture.m4a' }));
    await waitFor(() =>
      expect(within(lecture).getByText('Task 42 does not exist')).toBeInTheDocument(),
    );

    // The next action clears the error; the failed attempt changed nothing (the task is
    // still RUNNING, so its cancel asks for confirmation).
    fireEvent.click(within(lecture).getByRole('button', { name: 'Cancel lecture.m4a' }));
    await screen.findByRole('dialog');
    expect(within(lecture).queryByText('Task 42 does not exist')).not.toBeInTheDocument();
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

  it("shows a too-large and a multi-file refusal as the server's message on the file's row, adding no task", async () => {
    server.use(
      http.post('/api/tasks', async ({ request }) => {
        const body = await request.text();
        const contentType = body.match(/Content-Type: ([^\r]+)/)?.[1] ?? '';
        if (contentType === 'audio/wav') {
          return HttpResponse.json(errorUploadTooLarge, { status: 413 });
        }
        return HttpResponse.json(
          { error: 'multiple-files', message: 'Only one file may be uploaded per request' },
          { status: 400 },
        );
      }),
    );

    renderQueue();
    await screen.findByText('lecture.m4a');
    const pending = screen.getByRole('list', { name: 'Pending tasks' });
    const pendingBefore = within(pending).getAllByRole('listitem').length;

    const files = [
      new File(['x'], 'big.wav', { type: 'audio/wav' }),
      new File(['x'], 'two.mp3', { type: 'audio/mpeg' }),
    ];
    fireEvent.drop(screen.getByRole('button', { name: /drop/i }), { dataTransfer: { files } });

    const uploads = screen.getByRole('list', { name: 'Uploads' });

    // Both codes render the server's message through the generic error path.
    await waitFor(() => {
      const row = within(uploads)
        .getAllByRole('listitem')
        .find((item) => item.textContent?.includes('big.wav'));
      expect(
        within(row as HTMLElement).getByText('The upload exceeds the maximum size of 1 GiB'),
      ).toBeInTheDocument();
    });
    await waitFor(() => {
      const row = within(uploads)
        .getAllByRole('listitem')
        .find((item) => item.textContent?.includes('two.mp3'));
      expect(
        within(row as HTMLElement).getByText('Only one file may be uploaded per request'),
      ).toBeInTheDocument();
    });

    // Neither refusal became a task.
    expect(within(pending).getAllByRole('listitem')).toHaveLength(pendingBefore);
  });
});
