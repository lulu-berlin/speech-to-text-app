import { useEffect } from 'react';
import { CircleAlert } from 'lucide-react';
import { useMachine } from '@xstate/react';
import { Link } from 'react-router';
import styled from 'styled-components';
import { ConnectionContext } from '../../app/ConnectionContext';
import type { Task } from '../../shared/api/types';
import { ListRow } from '../../shared/ui/ListRow';
import { StatusBadge } from '../../shared/ui/StatusBadge';
import { focusRing } from '../../shared/ui/focusRing';
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog';
import { Icon } from '../../shared/ui/Icon';
import { Stack } from '../../shared/ui/layout';
import { TextButton } from '../../shared/ui/TextButton';
import { MetaText, Text } from '../../shared/ui/typography';
import { commandMachine } from '../../shared/state/commandMachine';
import { confirmActionMachine } from '../../shared/state/confirmActionMachine';
import { isAllowedCommand } from '../../shared/state/transitions';


const Filename = styled.span`
  color: ${(props) => props.theme.colors.text};
  font-weight: ${(props) => props.theme.fontWeights.medium};
`;

const ErrorLine = styled(Text)`
  margin: 0;
  color: ${(props) => props.theme.colors.danger};
  font-size: ${(props) => props.theme.fontSizes.small};
`;

const TaskLink = styled(Link)`
  color: inherit;
  text-decoration: none;

  ${focusRing}
`;

// The card's first line: the filename on the left, the status badge on the right.
const TitleRow = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: ${(props) => props.theme.spacing.sm};
`;

// A FAILED card's error: a small danger line led by a decorative alert icon.
const CardError = styled.p`
  display: flex;
  align-items: center;
  gap: ${(props) => props.theme.spacing.xs};
  margin: 0;
  color: ${(props) => props.theme.colors.danger};
  font-size: ${(props) => props.theme.fontSizes.small};
`;

// The card's quiet actions, right-aligned at the bottom.
const Actions = styled.div`
  display: flex;
  justify-content: flex-end;
  gap: ${(props) => props.theme.spacing.xs};
`;

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

  const retryError = retryState.matches('failed') ? retryState.context.error : null;
  const deleteError = deleteState.matches('failed') ? deleteState.context.error : null;

  return (
    <ListRow variant="card">
      <Stack gap="xs">
        <TitleRow>
          <TaskLink to={`/history/${task.id}`}>
            <Filename>{task.filename}</Filename>
          </TaskLink>
          <StatusBadge status={task.status} />
        </TitleRow>
        <MetaText>{formatTaskMeta(task)}</MetaText>
        {task.error !== null ? (
          <CardError>
            <Icon icon={CircleAlert} tone="danger" size="sm" />
            <span>{task.error}</span>
          </CardError>
        ) : null}
        <Actions>
          {isAllowedCommand('retry', task.status) ? (
            <TextButton
              onClick={() => {
                if (retryState.matches('failed') || retryState.matches('done')) {
                  retryActor.stop();
                  retryActor.start();
                }
                retrySend({ type: 'START' });
              }}
            >
              Retry
            </TextButton>
          ) : null}
          <TextButton
            $tone="danger"
            onClick={() => {
              if (deleteState.matches('failed') || deleteState.matches('done')) {
                deleteActor.stop();
                deleteActor.start();
              }
              deleteSend({ type: 'REQUEST' });
            }}
          >
            Delete
          </TextButton>
        </Actions>
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
