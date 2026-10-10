import type { LucideIcon } from 'lucide-react';
import { MoreVertical, Pause, Play, X } from 'lucide-react';
import { useEffect } from 'react';
import { useMachine } from '@xstate/react';
import { styled } from 'styled-components';
import { ConnectionContext } from '../../app/ConnectionContext';
import type { Task, TaskPhase } from '../../shared/api/types';
import { isAllowedCommand } from '../../shared/state/transitions';
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog';
import { DiarizationProgressBar } from '../../shared/ui/DiarizationProgressBar';
import { DropdownMenu } from '../../shared/ui/DropdownMenu';
import { IconButton } from '../../shared/ui/IconButton';
import { ListRow } from '../../shared/ui/ListRow';
import { ProgressBar } from '../../shared/ui/ProgressBar';
import { StatusBadge } from '../../shared/ui/StatusBadge';
import { Row, Stack } from '../../shared/ui/layout';
import { rowActionMachine } from './rowActionMachine';
import { RowContent, RowName } from './rowParts';

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
}

// One pending task of the queue list: its filename, phase and status, its controls, and
// the two progress bars (transcription; diarization by stage, hidden when it does not
// apply). Each action runs through the row's action machine (ADR 0038): a control is
// offered only when docs/api/task-transitions.json allows it, a confirmation opens only
// where work would be lost, and the action's result is applied to the live state at
// once (the server's own events, when they arrive, carry the same task).
export function TaskRow({ task }: TaskRowProps) {
  const connection = ConnectionContext.useActorRef();
  const [action, send] = useMachine(rowActionMachine, { input: { taskId: task.id } });

  // Work would be lost when cancelling a running task or a paused task that has started;
  // a queued or never-started paused task is cancelled directly (its chunks were never
  // processed, and the task can be retried from History).
  const confirmCancel =
    task.status === 'RUNNING' || (task.status === 'PAUSED' && task.startedAt !== null);

  // A finished action updates the live state: a delete removes the task, any command
  // applies its returned task (both are idempotent with the server's own events).
  useEffect(() => {
    if (!action.matches('done')) {
      return;
    }
    if (action.context.action === 'delete') {
      connection.send({ type: 'task-deleted', id: action.context.taskId });
    } else if (action.context.task !== null) {
      connection.send({ type: 'task-updated', task: action.context.task });
    }
  }, [action, connection]);

  const confirmingAction = action.matches('confirming') ? action.context.action : null;

  return (
    <ListRow>
      <Stack gap="sm">
        <RowContent>
          <RowName>{task.filename}</RowName>
          <Row>
            {task.phase !== null ? <RowPhase>{PHASE_LABELS[task.phase]}</RowPhase> : null}
            <StatusBadge status={task.status} />
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
              items={[
                {
                  label: 'Delete',
                  danger: true,
                  onSelect: () =>
                    send({
                      type: 'ACTIVATE',
                      action: 'delete',
                      status: task.status,
                      confirm: true,
                    }),
                },
              ]}
            />
          </Row>
        </RowContent>
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
        open={confirmingAction === 'delete'}
        title={`Delete ${task.filename}?`}
        confirmLabel="Delete task"
        onConfirm={() => send({ type: 'CONFIRM' })}
        onCancel={() => send({ type: 'DISMISS' })}
      >
        Removes the task, its transcript and audio for good.
      </ConfirmDialog>
    </ListRow>
  );
}
