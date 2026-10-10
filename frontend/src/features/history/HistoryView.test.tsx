import { render, screen } from '@testing-library/react';
import { http } from 'msw';
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
});
