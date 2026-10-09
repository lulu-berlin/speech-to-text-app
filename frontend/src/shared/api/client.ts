import { ApiError } from './types';
import type { ApiErrorBody, Task, TaskList } from './types';

const json = async <T>(response: Response): Promise<T> => {
  if (!response.ok) {
    // A body that is not the contract's error shape (e.g. a proxy's 502 page) falls back to
    // the HTTP status and status text.
    const body = (await response.json().catch(() => null)) as ApiErrorBody | null;
    const code = body?.error ?? String(response.status);
    const message = body?.message ?? response.statusText;
    throw new ApiError(code, message, body?.taskId);
  }
  return (await response.json()) as T;
};

export async function listTasks(): Promise<TaskList> {
  const response = await fetch('/api/tasks');
  return json<TaskList>(response);
}

export async function getTask(id: number): Promise<Task> {
  const response = await fetch(`/api/tasks/${id}`);
  return json<Task>(response);
}

// Item 12 contract: POST /api/tasks, multipart with a `file` field.
export async function uploadTask(file: File): Promise<Task> {
  const form = new FormData();
  form.append('file', file);
  const response = await fetch('/api/tasks', { method: 'POST', body: form });
  return json<Task>(response);
}
