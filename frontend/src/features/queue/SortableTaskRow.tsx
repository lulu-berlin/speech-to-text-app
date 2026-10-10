import { useSortable } from '@dnd-kit/sortable';
import type { Task } from '../../shared/api/types';
import { TaskRow } from './TaskRow';

export interface SortableTaskRowProps {
  task: Task;
  index: number;
  count: number;
  onMoveTask: (id: number, to: number) => void;
}

// One queue row with its dnd-kit wiring: the row node is the sortable, and the row's
// handle carries the drag attributes and listeners, so the row can be moved by pointer
// and by keyboard (ADR 0038 keeps the order itself in the reorder machine).
export function SortableTaskRow({ task, index, count, onMoveTask }: SortableTaskRowProps) {
  const { attributes, listeners, setNodeRef } = useSortable({ id: task.id });
  return (
    <TaskRow
      task={task}
      index={index}
      count={count}
      onMoveTask={onMoveTask}
      rowRef={setNodeRef}
      dragHandleProps={{ ...attributes, ...listeners }}
    />
  );
}
