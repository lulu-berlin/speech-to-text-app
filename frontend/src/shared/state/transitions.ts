// The allowed task status transitions, shared with the backend as docs/api/task-transitions.json
// (ADR 0031). The frontend reads it; the backend checks that its TaskStatus table equals it.
import { taskTransitions } from '../api/fixtures';
import type { TaskStatus } from '../api/types';

const transitions: Record<string, string[]> = taskTransitions.transitions;

// Whether the transition table allows `from` -> `to`. A null `from` (a task the state does
// not hold yet) has no previous status to check.
export function isAllowedTransition(from: TaskStatus, to: TaskStatus): boolean {
  return (transitions[from] ?? []).includes(to);
}
