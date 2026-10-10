import type { ReactNode } from 'react';
import type { LucideIcon } from 'lucide-react';
import styled from 'styled-components';
import { Icon } from './Icon';
import { Stack } from './layout';

const EmptyStateBase = styled.div`
  text-align: center;
  padding: ${(props) => props.theme.spacing.xl};
`;

const EmptyTitle = styled.p`
  margin: 0;
  color: ${(props) => props.theme.colors.text};
  font-weight: ${(props) => props.theme.fontWeights.semibold};
`;

const EmptyBody = styled.p`
  margin: 0;
  color: ${(props) => props.theme.colors.textMuted};
`;

export interface EmptyStateProps {
  icon?: LucideIcon;
  title: string;
  children?: ReactNode;
}

// The empty-state block of the list views; the icon is decorative.
export function EmptyState({ icon, title, children }: EmptyStateProps) {
  return (
    <EmptyStateBase>
      <Stack gap="sm">
        {icon !== undefined ? <Icon icon={icon} tone="textMuted" size="md" /> : null}
        <EmptyTitle>{title}</EmptyTitle>
        {children !== undefined ? <EmptyBody>{children}</EmptyBody> : null}
      </Stack>
    </EmptyStateBase>
  );
}
