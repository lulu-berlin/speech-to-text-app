import { useEffect } from 'react';
import { useMachine } from '@xstate/react';
import { Link } from 'react-router';
import styled from 'styled-components';
import { ConnectionContext } from '../../app/ConnectionContext';
import { ApiError } from '../../shared/api/types';
import type { Task } from '../../shared/api/types';
import { ListRow } from '../../shared/ui/ListRow';
import { StatusBadge } from '../../shared/ui/StatusBadge';
import { Button } from '../../shared/ui/Button';
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog';
import { Row, Stack } from '../../shared/ui/layout';
import { Text } from '../../shared/ui/typography';
import { commandMachine } from '../../shared/state/commandMachine';
import { confirmActionMachine } from '../../shared/state/confirmActionMachine';
import { isAllowedCommand } from '../../shared/state/transitions';

const Filename = styled.span`
  color: ${(props) => props.theme.colors.text};
  font-weight: ${(props) => props.theme.fontWeights.medium};
`;

const Meta = styled(Text)`
  margin: 0;
  color: ${(props) => props.theme.colors.textMuted};
  font-size: ${(props) => props.theme.fontSizes.small};
`;

const ErrorLine = styled(Text)`
  margin: 0;
  color: ${(props) => props.theme.colors.danger};
  font-size: ${(props) => props.theme.fontSizes.small};
`;

const TaskLink = styled(Link)`
  color: inherit;
  text-decoration: none;
`;

// The fixed month names and UTC rendering of the finish date, so the text is the same for
// every user (a toLocaleDateString would follow the reader's locale).
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
function formatFinishDate(finishedAtMs: number): string {
  const date = new Date(finishedAtMs);
  return `${MONTHS[date.getUTCMonth()]} ${date.getUTCDate()}, ${date.getUTCFullYear()}`;
}

// The task duration: mm:ss under an hour, h:mm:ss from one hour on.
function formatDuration(durationMs: number): string {
  const totalSeconds = Math.floor(durationMs / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const padded = (value: number) => String(value).padStart(2, '0');
  return hours > 0
    ? `${hours}:${padded(minutes)}:${padded(seconds)}`
    : `${padded(minutes)}:${padded(seconds)}`;
}

// One finished task in the history list. Retry runs through commandMachine and delete
// through confirmActionMachine, so the sending/done/failed state and any inline error live
// in the machines (ADR 0031), not in React state.
export function HistoryRow({ task }: { task: Task }) {
  const connection = ConnectionContext.useActorRef();

  const retry = useMachine(commandMachine, {
    input: { command: 'retry', taskId: task.id, status: task.status },
  });
  const [retryState, retrySend, retryActor] = retry;

  const deleteResult = useMachine(confirmActionMachine, { input: { taskId: task.id } });
  const [deleteState, deleteSend, deleteActor] = deleteResult;

  // On a successful retry the returned task (QUEUED) updates the store, which removes it
  // from the history list; on a successful delete the task is removed by id.
  useEffect(() => {
    if (retryState.matches('done') && retryState.context.task !== null) {
      connection.send({ type: 'task-updated', task: retryState.context.task });
    }
  }, [retryState, connection]);

  useEffect(() => {
    if (deleteState.matches('done')) {
      connection.send({ type: 'task-deleted', id: task.id });
    }
  }, [deleteState, connection, task.id]);

  const errorText = (error: unknown) => (error instanceof ApiError ? error.message : String(error));
  const retryError = retryState.matches('failed') ? retryState.context.error : null;
  const deleteError = deleteState.matches('failed') ? deleteState.context.error : null;

  return (
    <ListRow variant="card">
      <Stack gap="xs">
        <Row gap="sm">
          <TaskLink to={`/history/${task.id}`}>
            <Filename>{task.filename}</Filename>
          </TaskLink>
          <StatusBadge status={task.status} />
        </Row>
        <Meta>
          {task.finishedAt !== null ? formatFinishDate(task.finishedAt) : ''}
          {task.finishedAt !== null && task.durationMs !== null ? ' · ' : ''}
          {task.durationMs !== null ? formatDuration(task.durationMs) : ''}
        </Meta>
        {task.error !== null ? <Meta>{task.error}</Meta> : null}
        <Row gap="sm">
          {isAllowedCommand('retry', task.status) ? (
            <Button
              variant="secondary"
              onClick={() => {
                if (retryState.matches('failed') || retryState.matches('done')) {
                  retryActor.stop();
                  retryActor.start();
                }
                retrySend({ type: 'START' });
              }}
            >
              Retry
            </Button>
          ) : null}
          <Button
            variant="danger"
            onClick={() => {
              if (deleteState.matches('failed') || deleteState.matches('done')) {
                deleteActor.stop();
                deleteActor.start();
              }
              deleteSend({ type: 'REQUEST' });
            }}
          >
            Delete
          </Button>
        </Row>
        {retryError ? <ErrorLine>Retry failed: {String(retryError)}</ErrorLine> : null}
        {deleteError ? <ErrorLine>Delete failed: {String(deleteError)}</ErrorLine> : null}
      </Stack>
      <ConfirmDialog
        open={deleteState.matches('confirming') || deleteState.matches('running')}
        title="Delete task?"
        confirmLabel="Delete"
        onConfirm={() => deleteSend({ type: 'CONFIRM' })}
        onCancel={() => deleteSend({ type: 'CANCEL' })}
      >
        {task.filename} and its transcript and audio will be deleted.
      </ConfirmDialog>
    </ListRow>
  );
}
