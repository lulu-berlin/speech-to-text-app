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
// and by keyboard (ADR 0038 keeps the order itself in the reorder machine). While a drag
// is on, the row is offset by dnd-kit's transform — the dragged row follows the pointer,
// its neighbours slide aside — and the dragged row itself is lifted (item 25).
export function SortableTaskRow({ task, index, count, onMoveTask }: SortableTaskRowProps) {
  const { attributes, listeners, setNodeRef, transform, isDragging } = useSortable({
    id: task.id,
  });
  const dragOffset = transform === null ? null : ({ x: transform.x, y: transform.y } as const);
  return (
    <TaskRow
      task={task}
      index={index}
      count={count}
      onMoveTask={onMoveTask}
      rowRef={setNodeRef}
      dragHandleProps={{ ...attributes, ...listeners }}
      dragOffset={dragOffset}
      lifted={isDragging}
    />
  );
}
