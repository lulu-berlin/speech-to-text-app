import type { ReactNode } from 'react';
import { CircleAlert } from 'lucide-react';
import styled from 'styled-components';
import { Icon } from './Icon';
import { Stack } from './layout';

const CalloutBase = styled.div`
  display: flex;
  align-items: flex-start;
  gap: ${(props) => props.theme.spacing.sm};
  padding: ${(props) => props.theme.spacing.md} ${(props) => props.theme.spacing.lg};
  border-left: ${(props) => props.theme.borderWidths.ring} solid
    ${(props) => props.theme.colors.danger};
  border-radius: ${(props) => props.theme.radii.card};
  background: ${(props) => props.theme.colors.surface};
`;

const CalloutTitle = styled.p`
  margin: 0;
  color: ${(props) => props.theme.colors.text};
  font-weight: ${(props) => props.theme.fontWeights.semibold};
`;

const CalloutDetail = styled.p`
  margin: 0;
  color: ${(props) => props.theme.colors.textMuted};
  font-size: ${(props) => props.theme.fontSizes.small};
`;

export interface ErrorCalloutProps {
  // The plain-language statement of what failed.
  title: string;
  // The server's (or the browser's) message, shown as a muted second line.
  detail?: string;
  // The way forward, e.g. a Retry button.
  action?: ReactNode;
}

// The one error style of the views (item 25): an inline callout on the surface fill with a
// danger bar on its left, a decorative icon, a plain title, the detail and an action.
// It is announced as an alert, since it appears in place of content that failed to load.
export function ErrorCallout({ title, detail, action }: ErrorCalloutProps) {
  return (
    <CalloutBase role="alert">
      <Icon icon={CircleAlert} tone="danger" />
      <Stack gap="xs">
        <CalloutTitle>{title}</CalloutTitle>
        {detail !== undefined ? <CalloutDetail>{detail}</CalloutDetail> : null}
        {action}
      </Stack>
    </CalloutBase>
  );
}
