import { Pause, Play, X } from 'lucide-react';
import { useState } from 'react';
import { styled } from 'styled-components';
import { createActor } from 'xstate';
import { ConnectionContext } from '../../app/ConnectionContext';
import type { Task, TaskPhase } from '../../shared/api/types';
import { commandMachine } from '../../shared/state/commandMachine';
import { isAllowedCommand } from '../../shared/state/transitions';
import type { TaskCommand } from '../../shared/state/transitions';
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog';
import { DiarizationProgressBar } from '../../shared/ui/DiarizationProgressBar';
import { IconButton } from '../../shared/ui/IconButton';
import { ListRow } from '../../shared/ui/ListRow';
import { ProgressBar } from '../../shared/ui/ProgressBar';
import { StatusBadge } from '../../shared/ui/StatusBadge';
import { Row, Stack } from '../../shared/ui/layout';
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

export interface TaskRowProps {
  task: Task;
}

// One pending task of the queue list: its filename, phase and status, its controls, and
// the two progress bars (transcription; diarization by stage, hidden when it does not
// apply). Each control runs one command machine (ADR 0031) and is offered only when
// docs/api/task-transitions.json allows it; the task the command returns updates the
// live state at once (the server's task-updated, when it arrives, carries the same task).
export function TaskRow({ task }: TaskRowProps) {
  const connection = ConnectionContext.useActorRef();
  const [cancelOpen, setCancelOpen] = useState(false);

  // Work would be lost when cancelling a running task or a paused task that has started;
  // a queued or never-started paused task is cancelled directly (its chunks were never
  // processed, and the task can be retried from History).
  const confirmCancel =
    task.status === 'RUNNING' || (task.status === 'PAUSED' && task.startedAt !== null);

  const run = (command: TaskCommand) => {
    const actor = createActor(commandMachine, {
      input: { command, taskId: task.id, status: task.status },
    });
    actor.subscribe((snapshot) => {
      if (snapshot.matches('done') && snapshot.context.task !== null) {
        connection.send({ type: 'task-updated', task: snapshot.context.task });
        actor.stop();
      }
    });
    actor.start();
    actor.send({ type: 'START' });
  };

  return (
    <ListRow>
      <Stack gap="sm">
        <RowContent>
          <RowName>{task.filename}</RowName>
          <Row>
            {task.phase !== null ? <RowPhase>{PHASE_LABELS[task.phase]}</RowPhase> : null}
            <StatusBadge status={task.status} />
            {isAllowedCommand('pause', task.status) ? (
              <IconButton
                icon={Pause}
                label={`Pause ${task.filename}`}
                onClick={() => run('pause')}
              />
            ) : null}
            {isAllowedCommand('resume', task.status) ? (
              <IconButton
                icon={Play}
                label={`Resume ${task.filename}`}
                onClick={() => run('resume')}
              />
            ) : null}
            {isAllowedCommand('cancel', task.status) ? (
              <IconButton
                icon={X}
                label={`Cancel ${task.filename}`}
                onClick={() => (confirmCancel ? setCancelOpen(true) : run('cancel'))}
              />
            ) : null}
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
        open={cancelOpen}
        title={`Cancel ${task.filename}?`}
        confirmLabel="Cancel task"
        onConfirm={() => {
          setCancelOpen(false);
          run('cancel');
        }}
        onCancel={() => setCancelOpen(false)}
      >
        Stops the task; it can be retried from History.
      </ConfirmDialog>
    </ListRow>
  );
}
