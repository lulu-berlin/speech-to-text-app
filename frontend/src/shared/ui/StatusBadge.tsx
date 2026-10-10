import styled from 'styled-components';
import type { TaskStatus } from '../api/types';
import type { StatusGroup } from '../theme/theme';

// The status→group mapping (ADR 0033): the exact status stays visible as the badge text,
// only the treatment groups them. Items 14 and 15 reuse this.
const STATUS_GROUPS: Record<TaskStatus, StatusGroup> = {
  QUEUED: 'pending',
  PAUSED: 'pending',
  RUNNING: 'active',
  COMPLETED: 'done',
  FAILED: 'problem',
  CANCELED: 'gone',
};

export function statusGroup(status: TaskStatus): StatusGroup {
  return STATUS_GROUPS[status];
}

const Badge = styled.span<{ $group: StatusGroup }>`
  display: inline-block;
  padding: ${(props) => props.theme.spacing.xs} ${(props) => props.theme.spacing.sm};
  border-radius: ${(props) => props.theme.radii.pill};
  background: ${(props) => props.theme.colors.status[props.$group].background};
  color: ${(props) => props.theme.colors.status[props.$group].text};
`;

export interface StatusBadgeProps {
  status: TaskStatus;
}

// A pill carrying the task's exact status, tinted by its status group.
export function StatusBadge({ status }: StatusBadgeProps) {
  return <Badge $group={statusGroup(status)}>{status}</Badge>;
}
