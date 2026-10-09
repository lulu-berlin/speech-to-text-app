import type { Task, TaskList } from './types';

const json = async <T>(response: Response): Promise<T> => {
  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as {
      error?: string;
      message?: string;
      taskId?: number;
    } | null;
    const message = body?.message ?? response.statusText;
    throw new Error(`${response.status} ${message}`);
  }
  return (await response.json()) as T;
};

export async function listTasks(): Promise<TaskList> {
  const response = await fetch('/api/tasks');
  return json<TaskList>(response);
}
