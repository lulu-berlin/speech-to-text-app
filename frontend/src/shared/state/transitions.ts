// The allowed task status transitions, shared with the backend as docs/api/task-transitions.json
// (ADR 0031). The frontend reads it; the backend checks that its TaskStatus table equals it.
import { taskTransitions } from '../api/fixtures';
import type { TaskStatus } from '../api/types';

const transitions: Record<string, string[]> = taskTransitions.transitions;

export type TaskCommand = keyof typeof taskTransitions.commands;

// Whether the transition table allows `from` -> `to`. A null `from` (a task the state does
// not hold yet) has no previous status to check.
export function isAllowedTransition(from: TaskStatus, to: TaskStatus): boolean {
  return (transitions[from] ?? []).includes(to);
}

// The statuses a task command is allowed from (docs/api/task-transitions.json).
export function isAllowedCommand(command: TaskCommand, status: TaskStatus): boolean {
  return taskTransitions.commands[command].from.includes(status);
}
