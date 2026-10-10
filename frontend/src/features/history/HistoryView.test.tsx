import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { App } from '../../app/App';
import { eventStreamResponse } from '../../shared/api/handlers';
import {
  error as errorFixture,
  taskCanceled,
  taskCompleted,
  taskFailed,
  taskQueued,
  taskRunning,
} from '../../shared/api/fixtures';
import { server } from '../../test/server';

// Mock addition (settle with item 12): the snapshot holds a COMPLETED (3), FAILED (4) and
// CANCELED (5) task plus a RUNNING (6) and a QUEUED (7) one, so the history view's filtering
// and newest-first order are observable. The default snapshot.json holds no CANCELED task.
const historySnapshot = {
  tasks: [
    { ...taskRunning, id: 6, finishedAt: null },
    { ...taskQueued, id: 7, finishedAt: null },
    { ...taskCompleted, id: 3, finishedAt: 300 },
    { ...taskFailed, id: 4, finishedAt: 500 },
    { ...taskCanceled, id: 5, finishedAt: 100 },
  ],
  queue: { paused: false, taskIds: [6, 7] },
};

function renderHistory() {
  render(
    <MemoryRouter initialEntries={['/history']}>
      <App />
    </MemoryRouter>,
  );
  return screen.findByText('Live');
}

// The finished-task row carrying a file name; a test queries its controls with `within`.
function rowOf(filename: string) {
  return screen.getAllByRole('listitem').find((row) => row.textContent?.includes(filename))!;
}

describe('the history list', () => {
  it('shows completed, failed and canceled tasks, newest first, and no pending ones', async () => {
    server.use(http.get('/api/events', () => eventStreamResponse([['snapshot', historySnapshot]])));
    await renderHistory();

    const rows = screen.getAllByRole('listitem');
    expect(rows).toHaveLength(3);
    // Newest (highest finishedAt) first.
    expect(rows[0]).toHaveTextContent('call.ogg');
    expect(rows[1]).toHaveTextContent('interview.mp3');
    expect(rows[2]).toHaveTextContent('lecture.m4a');
    expect(screen.queryByText('memo.wav')).not.toBeInTheDocument();
  });

  it('shows a failed task its error message, and no other task an error', async () => {
    server.use(http.get('/api/events', () => eventStreamResponse([['snapshot', historySnapshot]])));
    await renderHistory();

    const rows = screen.getAllByRole('listitem');
    const failedRow = rows.find((row) => row.textContent?.includes('call.ogg'));
    expect(failedRow).toBeDefined();
    expect(failedRow).toHaveTextContent(
      'whisper-cli exited with code 1 while transcribing chunk 1: failed to allocate memory',
    );

    // The completed and canceled rows carry error: null; no error is shown in them.
    for (const row of rows) {
      if (row.textContent?.includes('call.ogg')) {
        continue;
      }
      expect(row.textContent).not.toContain('exited with code');
    }
  });

  it('narrows the list by filename search and the status filter', async () => {
    server.use(http.get('/api/events', () => eventStreamResponse([['snapshot', historySnapshot]])));
    await renderHistory();

    const search = screen.getByRole('searchbox', { name: /search/i });
    const filter = screen.getByRole('combobox', { name: /status/i });

    // Filename search narrows to the matching task.
    fireEvent.change(search, { target: { value: 'interview' } });
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    expect(screen.getByRole('listitem')).toHaveTextContent('interview.mp3');

    // Status filter narrows to that status.
    fireEvent.change(search, { target: { value: '' } });
    fireEvent.change(filter, { target: { value: 'FAILED' } });
    const onlyFailed = screen.getAllByRole('listitem');
    expect(onlyFailed).toHaveLength(1);
    expect(onlyFailed[0]).toHaveTextContent('call.ogg');

    fireEvent.change(filter, { target: { value: 'CANCELED' } });
    const onlyCanceled = screen.getAllByRole('listitem');
    expect(onlyCanceled).toHaveLength(1);
    expect(onlyCanceled[0]).toHaveTextContent('lecture.m4a');

    // Search and filter combine: the intersection of both.
    fireEvent.change(filter, { target: { value: 'COMPLETED' } });
    fireEvent.change(search, { target: { value: 'interview' } });
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    fireEvent.change(search, { target: { value: 'lecture' } });
    expect(screen.queryByRole('listitem')).not.toBeInTheDocument();
  });

  it('offers retry on failed and canceled tasks only; retrying calls the endpoint and the task leaves', async () => {
    // Mock addition: the retry response is the retried task itself, now QUEUED (the stock
    // handler answers task.queued.json, a different file), so the task leaves the list
    // because of its status, not because its filename changed.
    const retried: number[] = [];
    server.use(
      http.get('/api/events', () => eventStreamResponse([['snapshot', historySnapshot]])),
      http.post('/api/tasks/:id/retry', ({ params }) => {
        const id = Number(params.id);
        retried.push(id);
        const task = historySnapshot.tasks.find((candidate) => candidate.id === id);
        return HttpResponse.json({
          ...task,
          status: 'QUEUED',
          phase: null,
          error: null,
          finishedAt: null,
        });
      }),
    );
    await renderHistory();

    // task-transitions.json allows retry from FAILED and CANCELED, not from COMPLETED.
    expect(within(rowOf('call.ogg')).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(within(rowOf('lecture.m4a')).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(
      within(rowOf('interview.mp3')).queryByRole('button', { name: 'Retry' }),
    ).not.toBeInTheDocument();

    fireEvent.click(within(rowOf('call.ogg')).getByRole('button', { name: 'Retry' }));

    // POST /api/tasks/4/retry was sent, and the now-QUEUED task leaves the history list.
    await waitFor(() => expect(screen.queryByText('call.ogg')).not.toBeInTheDocument());
    expect(retried).toEqual([4]);
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
  });

  it('asks for confirmation before deleting; confirming calls the endpoint and the task leaves', async () => {
    // Mock addition: the delete handler records the ids it receives (the stock handler
    // answers 204 without recording).
    const deleted: number[] = [];
    server.use(
      http.get('/api/events', () => eventStreamResponse([['snapshot', historySnapshot]])),
      http.delete('/api/tasks/:id', ({ params }) => {
        deleted.push(Number(params.id));
        return new HttpResponse(null, { status: 204 });
      }),
    );
    await renderHistory();

    // Delete is offered on every finished task; it first asks.
    fireEvent.click(within(rowOf('interview.mp3')).getByRole('button', { name: 'Delete' }));
    let dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('interview.mp3');

    // Cancel sends nothing and keeps the task.
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(deleted).toEqual([]);
    expect(screen.getByText('interview.mp3')).toBeInTheDocument();

    // Confirming sends DELETE /api/tasks/3 once, and the task leaves the list.
    fireEvent.click(within(rowOf('interview.mp3')).getByRole('button', { name: 'Delete' }));
    dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(screen.queryByText('interview.mp3')).not.toBeInTheDocument());
    expect(deleted).toEqual([3]);
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
  });

  it('adds a task to the list when it finishes while the history view is open', async () => {
    // Mock addition: the snapshot holds only pending tasks, then task-updated events finish
    // two of them, so a live finish appears in the open list.
    const pendingSnapshot = {
      tasks: [
        { ...taskRunning, id: 6, finishedAt: null },
        { ...taskQueued, id: 7, finishedAt: null },
      ],
      queue: { paused: false, taskIds: [6, 7] },
    };
    const completed = { ...taskCompleted, id: 6, finishedAt: 200 };
    const failed = { ...taskFailed, id: 7, finishedAt: 100 };
    server.use(
      http.get('/api/events', () =>
        eventStreamResponse([
          ['snapshot', pendingSnapshot],
          ['task-updated', completed],
          ['task-updated', failed],
        ]),
      ),
    );
    await renderHistory();

    // task-updated to COMPLETED and FAILED lands in the open list, newest first.
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(2));
    const rows = screen.getAllByRole('listitem');
    expect(rows[0]).toHaveTextContent('interview.mp3');
    expect(rows[1]).toHaveTextContent('call.ogg');
  });

  it('shows an inline error when a retry is refused', async () => {
    // Mock addition: the retry handler answers 409 with error.json, standing in for a
    // refused command (there is no 409 illegal-transition example in docs/api/).
    server.use(
      http.get('/api/events', () => eventStreamResponse([['snapshot', historySnapshot]])),
      http.post('/api/tasks/:id/retry', () => HttpResponse.json(errorFixture, { status: 409 })),
    );
    await renderHistory();

    fireEvent.click(within(rowOf('call.ogg')).getByRole('button', { name: 'Retry' }));

    // The refusal surfaces inline; the task stays in the list.
    expect(await screen.findByText(/Task 42 does not exist/)).toBeInTheDocument();
    expect(rowOf('call.ogg')).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(3);
  });

  it('shows an inline error when a delete is refused', async () => {
    let confirmClicks = 0;
    // Mock addition: the delete handler answers 409 with error.json, standing in for a
    // refused delete.
    server.use(
      http.get('/api/events', () => eventStreamResponse([['snapshot', historySnapshot]])),
      http.delete('/api/tasks/:id', () => HttpResponse.json(errorFixture, { status: 409 })),
    );
    await renderHistory();

    fireEvent.click(within(rowOf('interview.mp3')).getByRole('button', { name: 'Delete' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));
    confirmClicks += 1;

    // The refusal surfaces inline and the dialog closes; the task stays in the list.
    expect(await screen.findByText(/Task 42 does not exist/)).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByText('interview.mp3')).toBeInTheDocument();
    expect(confirmClicks).toBe(1);
  });

  it('shows the task finish date and its duration in each row', async () => {
    // Mock addition: finished tasks with known finishedAt/durationMs, so the fixed (UTC,
    // non-locale) rendering is asserted exactly, plus a finished task with durationMs null.
    const dateSnapshot = {
      tasks: [
        {
          ...taskCompleted,
          id: 3,
          filename: 'interview.mp3',
          finishedAt: 1791537645000,
          durationMs: 1185000,
        },
        {
          ...taskCompleted,
          id: 4,
          filename: 'call.ogg',
          finishedAt: 1791537000000,
          durationMs: null,
        },
        {
          ...taskCompleted,
          id: 5,
          filename: 'lecture.m4a',
          finishedAt: 1791537680000,
          durationMs: 3661000,
        },
      ],
      queue: { paused: false, taskIds: [] },
    };
    server.use(http.get('/api/events', () => eventStreamResponse([['snapshot', dateSnapshot]])));
    await renderHistory();

    // finishedAt 1791537645000 -> "Oct 9, 2026"; durationMs 1185000 -> "19:45" (mm:ss).
    expect(rowOf('interview.mp3')).toHaveTextContent('Oct 9, 2026 · 19:45');

    // durationMs null: the date shows, the duration does not.
    const noDuration = rowOf('call.ogg');
    expect(noDuration).toHaveTextContent('Oct 9, 2026');
    expect(noDuration).not.toHaveTextContent('·');

    // One hour and up: h:mm:ss. 3661000 ms -> 1:01:01.
    expect(rowOf('lecture.m4a')).toHaveTextContent('Oct 9, 2026 · 1:01:01');
  });
});
