import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { App } from '../../app/App';
import { eventStreamResponse } from '../../shared/api/handlers';
import {
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

    const rowOf = (filename: string) =>
      screen.getAllByRole('listitem').find((row) => row.textContent?.includes(filename))!;

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

    const rowOf = (filename: string) =>
      screen.getAllByRole('listitem').find((row) => row.textContent?.includes(filename))!;

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
});
