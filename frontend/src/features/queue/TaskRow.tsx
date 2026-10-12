import type { LucideIcon } from 'lucide-react';
import { GripVertical, MoreVertical, Pause, Play, X } from 'lucide-react';
import { useEffect } from 'react';
import type { HTMLAttributes, Ref } from 'react';
import { useMachine } from '@xstate/react';
import { css, styled } from 'styled-components';
import { ConnectionContext } from '../../app/ConnectionContext';
import { errorMessage } from '../../shared/api/types';
import type { Task, TaskPhase } from '../../shared/api/types';
import { isAllowedCommand } from '../../shared/state/transitions';
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog';
import { DiarizationProgressBar } from '../../shared/ui/DiarizationProgressBar';
import { DropdownMenu } from '../../shared/ui/DropdownMenu';
import type { DropdownMenuItem } from '../../shared/ui/DropdownMenu';
import { IconButton } from '../../shared/ui/IconButton';
import { ListRow } from '../../shared/ui/ListRow';
import { ProgressBar } from '../../shared/ui/ProgressBar';
import { StatusBadge } from '../../shared/ui/StatusBadge';
import { Row, Stack } from '../../shared/ui/layout';
import { confirmActionMachine } from '../../shared/state/confirmActionMachine';
import { rowActionMachine } from './rowActionMachine';
import { RowContent, RowControls, RowName } from './rowParts';

// The phase a task is in, as a text label next to its status (none while queued).
const PHASE_LABELS: Record<NonNullable<TaskPhase>, string> = {
  CHUNKING: 'Chunking',
  TRANSCRIBING: 'Transcribing',
  DIARIZING: 'Diarizing',
};

const RowPhase = styled.span`
  color: ${(props) => props.theme.colors.textMuted};
  font-size: ${(props) => props.theme.fontSizes.small};
`;

// The inline error of a failed action (item 17), cleared by the next action.
const RowActionError = styled.span`
  color: ${(props) => props.theme.colors.danger};
`;

// A queue row that can be dragged: positioned so it can be offset while a drag is on. The
// dragged row is raised — the raised fill, the card shadow (kept beside the focus ring
// when its handle has keyboard focus) and stacked above its neighbours; the neighbours
// slide aside, animated only when motion is welcome (item 25).
const SortableRow = styled(ListRow)<{ $lifted: boolean }>`
  position: relative;

  @media (prefers-reduced-motion: no-preference) {
    transition-property: top, left;
    transition-duration: ${(props) => props.theme.motion.slideDuration};
    transition-timing-function: ${(props) => props.theme.motion.slideEasing};
  }

  ${(props) =>
    props.$lifted
      ? css`
          z-index: 1;
          transition: none;
          background: ${props.theme.colors.surfaceRaised};
          box-shadow: ${props.theme.shadows.card};

          &:focus-within {
            box-shadow:
              0 0 0 ${props.theme.borderWidths.ring} ${props.theme.colors.focusRing},
              ${props.theme.shadows.card};
          }
        `
      : ''}
`;

// The per-task command controls, in display order. A control is offered only when
// docs/api/task-transitions.json allows its command for the task's status.
const CONTROLS: {
  command: 'pause' | 'resume' | 'cancel';
  icon: LucideIcon;
  verb: string;
}[] = [
  { command: 'pause', icon: Pause, verb: 'Pause' },
  { command: 'resume', icon: Play, verb: 'Resume' },
  { command: 'cancel', icon: X, verb: 'Cancel' },
];

export interface TaskRowProps {
  task: Task;
  // The row's position in the queue (the move actions and their bounds).
  index: number;
  count: number;
  // A drag or a menu move asks the parent to reorder this row to `to`.
  onMoveTask: (id: number, to: number) => void;
  // The dnd-kit sortable wiring: the row node ref, and the drag handle's attributes and
  // listeners (pointer and keyboard dragging).
  rowRef?: Ref<HTMLLIElement>;
  dragHandleProps?: HTMLAttributes<HTMLButtonElement>;
  // While a drag is on: this row's offset from its place (the dragged row follows the
  // pointer; its neighbours slide aside), and whether it is the dragged row (item 25).
  dragOffset?: { x: number; y: number } | null;
  lifted?: boolean;
}

// One pending task of the queue list: its filename, phase and status, its controls, and
// the two progress bars (transcription; diarization by stage, hidden when it does not
// apply). Each action runs through the row's action machine (ADR 0038): a control is
// offered only when docs/api/task-transitions.json allows it, a confirmation opens only
// where work would be lost, and the action's result is applied to the live state at
// once (the server's own events, when they arrive, carry the same task).
export function TaskRow({
  task,
  index,
  count,
  onMoveTask,
  rowRef,
  dragHandleProps,
  dragOffset,
  lifted = false,
}: TaskRowProps) {
  const connection = ConnectionContext.useActorRef();
  const [action, send] = useMachine(rowActionMachine, { input: { taskId: task.id } });
  // Delete runs through the shared dialog-action machine (item 28): the menu item opens
  // its dialog, done removes the task from the live state.
  const [deleteState, deleteSend, deleteActor] = useMachine(confirmActionMachine, {
    input: { taskId: task.id },
  });

  // Work would be lost when cancelling a running task or a paused task that has started;
  // a queued or never-started paused task is cancelled directly (its chunks were never
  // processed, and the task can be retried from History).
  const confirmCancel =
    task.status === 'RUNNING' || (task.status === 'PAUSED' && task.startedAt !== null);

  // A finished command applies its returned task to the live state (idempotent with the
  // server's own events); a finished delete removes the task.
  useEffect(() => {
    if (action.matches({ ready: 'done' }) && action.context.task !== null) {
      connection.send({ type: 'task-updated', task: action.context.task });
    }
  }, [action, connection]);

  useEffect(() => {
    if (deleteState.matches('done')) {
      connection.send({ type: 'task-deleted', id: task.id });
    }
  }, [deleteState, connection, task.id]);

  const confirmingAction = action.matches('confirming') ? action.context.action : null;

  // The move actions, plus delete; the bound moves are disabled.
  const menuItems: DropdownMenuItem[] = [
    {
      label: 'Move to top',
      disabled: index === 0,
      onSelect: () => onMoveTask(task.id, 0),
    },
    {
      label: 'Move up',
      disabled: index === 0,
      onSelect: () => onMoveTask(task.id, index - 1),
    },
    {
      label: 'Move down',
      disabled: index === count - 1,
      onSelect: () => onMoveTask(task.id, index + 1),
    },
    {
      label: 'Move to bottom',
      disabled: index === count - 1,
      onSelect: () => onMoveTask(task.id, count - 1),
    },
    {
      label: 'Delete',
      danger: true,
      onSelect: () => {
        // A failed or done delete machine cannot answer REQUEST from its idle state, so
        // restart it first (the same reset the history flows do).
        if (deleteState.matches('failed') || deleteState.matches('done')) {
          deleteActor.stop();
          deleteActor.start();
        }
        deleteSend({ type: 'REQUEST' });
      },
    },
  ];

  return (
    <SortableRow
      ref={rowRef}
      $lifted={lifted}
      // React adds the unit to numeric offsets; they are pointer data, not design values.
      style={dragOffset ? { left: dragOffset.x, top: dragOffset.y } : undefined}
    >
      <Stack gap="sm">
        <RowContent>
          <RowName>{task.filename}</RowName>
          <RowControls>
            {task.phase !== null ? <RowPhase>{PHASE_LABELS[task.phase]}</RowPhase> : null}
            <StatusBadge status={task.status} />
            {dragHandleProps !== undefined ? (
              <IconButton
                icon={GripVertical}
                label={`Reorder ${task.filename}`}
                {...dragHandleProps}
              />
            ) : null}
            {CONTROLS.filter(({ command }) => isAllowedCommand(command, task.status)).map(
              ({ command, icon, verb }) => (
                <IconButton
                  key={command}
                  icon={icon}
                  label={`${verb} ${task.filename}`}
                  onClick={() =>
                    send({
                      type: 'ACTIVATE',
                      action: command,
                      status: task.status,
                      confirm: command === 'cancel' && confirmCancel,
                    })
                  }
                />
              ),
            )}
            <DropdownMenu
              trigger={<IconButton icon={MoreVertical} label={`Actions for ${task.filename}`} />}
              align="end"
              items={menuItems}
            />
          </RowControls>
        </RowContent>
        {action.matches({ ready: 'failed' }) ? (
          <RowActionError>{errorMessage(action.context.error)}</RowActionError>
        ) : null}
        {deleteState.matches('failed') ? (
          <RowActionError>
            {deleteState.context.error instanceof Error
              ? deleteState.context.error.message
              : String(deleteState.context.error)}
          </RowActionError>
        ) : null}
        <ProgressBar value={task.transcriptionProgress} label="Transcription" />
        {task.diarizationProgress !== null ? (
          <DiarizationProgressBar
            stage={task.diarizationStage}
            progress={task.diarizationProgress}
          />
        ) : null}
      </Stack>
      <ConfirmDialog
        open={confirmingAction === 'cancel'}
        title={`Cancel ${task.filename}?`}
        confirmLabel="Cancel task"
        onConfirm={() => send({ type: 'CONFIRM' })}
        onCancel={() => send({ type: 'DISMISS' })}
      >
        Stops the task; it can be retried from History.
      </ConfirmDialog>
      <ConfirmDialog
        open={deleteState.matches('confirming') || deleteState.matches('running')}
        title={`Delete ${task.filename}?`}
        confirmLabel="Delete task"
        onConfirm={() => deleteSend({ type: 'CONFIRM' })}
        onCancel={() => deleteSend({ type: 'CANCEL' })}
      >
        Removes the task, its transcript and audio for good.
      </ConfirmDialog>
    </SortableRow>
  );
}
