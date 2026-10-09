import { describe, expect, it } from 'vitest';
import { listTasks } from './client';
import { taskList } from './fixtures';

describe('the typed API client', () => {
  it('returns typed tasks for GET /api/tasks (from docs/api/task-list.json)', async () => {
    const result = await listTasks();

    // The contract: GET /api/tasks returns { tasks: [Task] } with Task's status union.
    expect(Array.isArray(result.tasks)).toBe(true);
    expect(result.tasks).toHaveLength(taskList.tasks.length);

    // Ids and statuses come from the contract example, not from the client's output.
    const expected = taskList.tasks.map((task) => [task.id, task.status]);
    expect(result.tasks.map((task) => [task.id, task.status])).toEqual(expected);

    // Task fields mirror the item 12 contract shape.
    const first = result.tasks[0]!;
    expect(typeof first.id).toBe('number');
    expect(typeof first.filename).toBe('string');
    expect(['QUEUED', 'RUNNING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELED']).toContain(
      first.status,
    );
    expect(typeof first.durationMs === 'number' || first.durationMs === null).toBe(true);
  });
});
