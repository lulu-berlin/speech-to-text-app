import styled from 'styled-components';
import { StenographerAnimation } from '../brand/StenographerAnimation';

const LoadingStateBase = styled.div`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: ${(props) => props.theme.spacing.sm};
  padding: ${(props) => props.theme.spacing.xl};
`;

const LoadingLabel = styled.p`
  margin: 0;
  color: ${(props) => props.theme.colors.textMuted};
`;

export interface LoadingStateProps {
  // What is loading, e.g. "Loading transcript…".
  label: string;
}

// The loading pattern (item 17, applied by item 25): the mascot at the empty-state size
// above a short muted label, centred. The mascot is decorative; the label carries the
// meaning.
export function LoadingState({ label }: LoadingStateProps) {
  return (
    <LoadingStateBase>
      <StenographerAnimation size="empty" />
      <LoadingLabel>{label}</LoadingLabel>
    </LoadingStateBase>
  );
}
