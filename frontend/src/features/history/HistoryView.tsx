import { useState } from 'react';
import { FormattedMessage } from 'react-intl';
import styled from 'styled-components';
import { ConnectionContext } from '../../app/ConnectionContext';
import { EmptyState } from '../../shared/ui/EmptyState';
import { List } from '../../shared/ui/List';
import { controlStyle } from '../../shared/ui/controlStyle';
import { Field } from '../../shared/ui/Field';
import { useMessages } from '../../shared/i18n/useMessages';
import type { Task } from '../../shared/api/types';
import { Page, Row, Stack } from '../../shared/ui/layout';
import { PageHeading } from '../../shared/ui/typography';
import { TextLink } from '../../shared/ui/TextLink';
import { TextButton } from '../../shared/ui/TextButton';
import { HistoryRow } from './HistoryRow';

// The finished statuses the history list shows; pending and active tasks stay in the queue.
const FINISHED: ReadonlySet<Task['status']> = new Set(['COMPLETED', 'FAILED', 'CANCELED']);

// The status filter options: "all statuses" plus the three finished statuses.
const FILTER_STATUSES: ReadonlyArray<{ value: string; labelId: string }> = [
  { value: 'ALL', labelId: 'history.status.all' },
  { value: 'COMPLETED', labelId: 'history.status.completed' },
  { value: 'FAILED', labelId: 'history.status.failed' },
  { value: 'CANCELED', labelId: 'history.status.canceled' },
];

const Title = styled(PageHeading)`
  margin: 0;
`;

const Controls = styled(Row)`
  flex-wrap: wrap;
`;

// History's search and status filter take the shared form-control look (item 25).
const SearchInput = styled.input`
  ${controlStyle}
`;

const StatusSelect = styled.select`
  ${controlStyle}
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

export function HistoryView() {
  const t = useMessages();
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

  // Under the heading: the search, the filter and the list once something has finished;
  // otherwise, once live, the empty state (before that nothing — the shell's connecting
  // region is the loading state).
  return (
    <Page>
      <Stack gap="md">
        <Title>{t('history.title')}</Title>
        {allFinished.length > 0 ? (
          <>
            <Controls gap="sm">
              <Field label={t('history.search')} htmlFor="history-search">
                <SearchInput
                  id="history-search"
                  type="search"
                  value={search}
                  onChange={(event) => setSearch(event.target.value)}
                  aria-label={t('history.searchLabel')}
                />
              </Field>
              <Field label={t('history.status')} htmlFor="history-status">
                <StatusSelect
                  id="history-status"
                  value={statusFilter}
                  onChange={(event) => setStatusFilter(event.target.value)}
                  aria-label={t('history.statusLabel')}
                >
                  {FILTER_STATUSES.map((option) => (
                    <option key={option.value} value={option.value}>
                      {t(option.labelId)}
                    </option>
                  ))}
                </StatusSelect>
              </Field>
            </Controls>
            {finished.length > 0 ? (
              <List>
                {finished.map((task) => (
                  <HistoryRow key={task.id} task={task} />
                ))}
              </List>
            ) : (
              <NoMatches gap="sm">
                <NoMatchesLine>{t('history.noMatches')}</NoMatchesLine>
                <TextButton
                  onClick={() => {
                    setSearch('');
                    setStatusFilter('ALL');
                  }}
                >
                  {t('history.clearFilters')}
                </TextButton>
              </NoMatches>
            )}
          </>
        ) : live ? (
          <EmptyState title={t('history.empty.title')}>
            <FormattedMessage
              id="history.empty.body"
              values={{ link: (chunks) => <TextLink to="/queue">{chunks}</TextLink> }}
            />
          </EmptyState>
        ) : null}
      </Stack>
    </Page>
  );
}
