import { Link } from 'react-router';
import { useState } from 'react';
import styled from 'styled-components';
import { ConnectionContext } from '../../app/ConnectionContext';
import { List } from '../../shared/ui/List';
import { ListRow } from '../../shared/ui/ListRow';
import { StatusBadge } from '../../shared/ui/StatusBadge';
import type { Task } from '../../shared/api/types';
import { deleteTask } from '../../shared/api/client';
import { isAllowedCommand } from '../../shared/state/transitions';
import { Button } from '../../shared/ui/Button';
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog';
import { Page, Row, Stack } from '../../shared/ui/layout';
import { PageHeading } from '../../shared/ui/typography';
import { useTaskCommand } from './useTaskCommand';

// The finished statuses the history list shows; pending and active tasks stay in the queue.
const FINISHED: ReadonlySet<Task['status']> = new Set(['COMPLETED', 'FAILED', 'CANCELED']);

// The status filter options: "all statuses" plus the three finished statuses.
const FILTER_STATUSES: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'ALL', label: 'All statuses' },
  { value: 'COMPLETED', label: 'Completed' },
  { value: 'FAILED', label: 'Failed' },
  { value: 'CANCELED', label: 'Canceled' },
];

const Filename = styled.span`
  color: ${(props) => props.theme.colors.text};
  font-weight: ${(props) => props.theme.fontWeights.medium};
`;

const Meta = styled.span`
  color: ${(props) => props.theme.colors.textMuted};
  font-size: ${(props) => props.theme.fontSizes.small};
`;

const Title = styled(PageHeading)`
  margin: 0;
`;

const TaskLink = styled(Link)`
  color: inherit;
  text-decoration: none;
`;

const Controls = styled(Row)`
  flex-wrap: wrap;
`;

export function HistoryView() {
  const tasks = ConnectionContext.useSelector((state) => state.context.tasks);
  const connection = ConnectionContext.useActorRef();
  const runCommand = useTaskCommand();
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');
  // The task whose deletion awaits confirmation (delete is always confirmed, item 14).
  const [pendingDelete, setPendingDelete] = useState<Task | null>(null);

  // On 204 the task leaves the store as a task-deleted event, the same event the server
  // sends to every client.
  const confirmDelete = (task: Task) => {
    setPendingDelete(null);
    void deleteTask(task.id).then(() => connection.send({ type: 'task-deleted', id: task.id }));
  };

  const finished = tasks
    .filter((task) => FINISHED.has(task.status))
    .sort((a, b) => (b.finishedAt ?? 0) - (a.finishedAt ?? 0))
    .filter(
      (task) =>
        (statusFilter === 'ALL' || task.status === statusFilter) &&
        task.filename.toLowerCase().includes(search.trim().toLowerCase()),
    );

  return (
    <Page>
      <Stack gap="md">
        <Title>History</Title>
        <Controls gap="sm">
          <label>
            Search
            <input
              type="search"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              aria-label="Search by filename"
            />
          </label>
          <label>
            Status
            <select
              value={statusFilter}
              onChange={(event) => setStatusFilter(event.target.value)}
              aria-label="Filter by status"
            >
              {FILTER_STATUSES.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
        </Controls>
        <List>
          {finished.map((task) => (
            <ListRow key={task.id} variant="card">
              <Stack gap="xs">
                <Row gap="sm">
                  <TaskLink to={`/history/${task.id}`}>
                    <Filename>{task.filename}</Filename>
                  </TaskLink>
                  <StatusBadge status={task.status} />
                </Row>
                {task.error !== null ? <Meta>{task.error}</Meta> : null}
                <Row gap="sm">
                  {isAllowedCommand('retry', task.status) ? (
                    <Button variant="secondary" onClick={() => runCommand('retry', task)}>
                      Retry
                    </Button>
                  ) : null}
                  <Button variant="danger" onClick={() => setPendingDelete(task)}>
                    Delete
                  </Button>
                </Row>
              </Stack>
            </ListRow>
          ))}
        </List>
      </Stack>
      <ConfirmDialog
        open={pendingDelete !== null}
        title="Delete task?"
        confirmLabel="Delete"
        onConfirm={() => {
          if (pendingDelete !== null) {
            confirmDelete(pendingDelete);
          }
        }}
        onCancel={() => setPendingDelete(null)}
      >
        {pendingDelete?.filename} and its transcript and audio will be deleted.
      </ConfirmDialog>
    </Page>
  );
}
