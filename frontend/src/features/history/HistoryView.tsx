import { Link } from 'react-router';
import { useState } from 'react';
import styled from 'styled-components';
import { ConnectionContext } from '../../app/ConnectionContext';
import { List } from '../../shared/ui/List';
import { ListRow } from '../../shared/ui/ListRow';
import { StatusBadge } from '../../shared/ui/StatusBadge';
import type { Task } from '../../shared/api/types';
import { Page, Row, Stack } from '../../shared/ui/layout';
import { PageHeading } from '../../shared/ui/typography';

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
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');

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
              <TaskLink to={`/history/${task.id}`}>
                <Stack gap="xs">
                  <Row gap="sm">
                    <Filename>{task.filename}</Filename>
                    <StatusBadge status={task.status} />
                  </Row>
                  {task.error !== null ? <Meta>{task.error}</Meta> : null}
                </Stack>
              </TaskLink>
            </ListRow>
          ))}
        </List>
      </Stack>
    </Page>
  );
}
