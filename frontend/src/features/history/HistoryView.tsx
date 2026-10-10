import { useState } from 'react';
import { Link } from 'react-router';
import styled from 'styled-components';
import { ConnectionContext } from '../../app/ConnectionContext';
import { EmptyState } from '../../shared/ui/EmptyState';
import { List } from '../../shared/ui/List';
import type { Task } from '../../shared/api/types';
import { focusRing } from '../../shared/ui/focusRing';
import { Page, Row, Stack } from '../../shared/ui/layout';
import { PageHeading } from '../../shared/ui/typography';
import { HistoryRow } from './HistoryRow';

// The finished statuses the history list shows; pending and active tasks stay in the queue.
const FINISHED: ReadonlySet<Task['status']> = new Set(['COMPLETED', 'FAILED', 'CANCELED']);

// The status filter options: "all statuses" plus the three finished statuses.
const FILTER_STATUSES: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'ALL', label: 'All statuses' },
  { value: 'COMPLETED', label: 'Completed' },
  { value: 'FAILED', label: 'Failed' },
  { value: 'CANCELED', label: 'Canceled' },
];

const Title = styled(PageHeading)`
  margin: 0;
`;

const Controls = styled(Row)`
  flex-wrap: wrap;
`;

// The empty state's action, an inline link in the guidance line.
const QueueLink = styled(Link)`
  color: ${(props) => props.theme.colors.accent};

  ${focusRing}
`;

// The no-matches state (item 25): a compact muted line with the action that clears the
// search and the filter; no mascot, since it is not an empty or a loading state.
const NoMatches = styled(Row)`
  flex-wrap: wrap;
`;

const NoMatchesLine = styled.p`
  margin: 0;
  color: ${(props) => props.theme.colors.textMuted};
`;

const ClearFilters = styled.button`
  padding: ${(props) => props.theme.spacing.xs} ${(props) => props.theme.spacing.sm};
  border: none;
  border-radius: ${(props) => props.theme.radii.button};
  background: transparent;
  color: ${(props) => props.theme.colors.accent};
  font: inherit;
  cursor: pointer;

  ${focusRing}
`;

export function HistoryView() {
  const tasks = ConnectionContext.useSelector((state) => state.context.tasks);
  const live = ConnectionContext.useSelector((state) => state.value === 'live');
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');

  const allFinished = tasks
    .filter((task) => FINISHED.has(task.status))
    .sort((a, b) => (b.finishedAt ?? 0) - (a.finishedAt ?? 0));
  const finished = allFinished.filter(
    (task) =>
      (statusFilter === 'ALL' || task.status === statusFilter) &&
      task.filename.toLowerCase().includes(search.trim().toLowerCase()),
  );

  // Nothing has finished: once live the empty state, before that nothing (the shell's
  // connecting region is the loading state); the search and filter have nothing to search.
  if (allFinished.length === 0) {
    return (
      <Page>
        <Stack gap="md">
          <Title>History</Title>
          {live ? (
            <EmptyState title="No transcripts yet">
              Finished tasks appear here — <QueueLink to="/queue">go to the queue</QueueLink> to add
              audio
            </EmptyState>
          ) : null}
        </Stack>
      </Page>
    );
  }

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
        {finished.length > 0 ? (
          <List>
            {finished.map((task) => (
              <HistoryRow key={task.id} task={task} />
            ))}
          </List>
        ) : (
          <NoMatches gap="sm">
            <NoMatchesLine>No tasks match your search and filter.</NoMatchesLine>
            <ClearFilters
              type="button"
              onClick={() => {
                setSearch('');
                setStatusFilter('ALL');
              }}
            >
              Clear filters
            </ClearFilters>
          </NoMatches>
        )}
      </Stack>
    </Page>
  );
}
