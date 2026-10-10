import styled from 'styled-components';
import { StenographerAnimation } from '../shared/brand/StenographerAnimation';
import { ConnectionContext } from './ConnectionContext';

const ConnectingRegionBase = styled.section`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: ${(props) => props.theme.spacing.sm};
  padding: ${(props) => props.theme.spacing.xl};
`;

const ConnectingLabel = styled.p`
  margin: 0;
  color: ${(props) => props.theme.colors.textMuted};
`;

// The label follows the connection state: the first snapshot, or a drop (item 17).
const REGION_LABEL = {
  connecting: 'Connecting…',
  reconnecting: 'Reconnecting…',
} as const;

// The demo loading region (item 17): until the first snapshot arrives — and again while
// a dropped connection reconnects — the app shell waits with the mascot and a short
// label: the pattern items 14/15 copy for their loading regions when they switch from
// skeletons to the stenographer. It reads the state inside the connection provider, so
// it is its own component.
export function ConnectingRegion() {
  const state = ConnectionContext.useSelector((state) => state.value);
  if (state === 'live') {
    return null;
  }
  return (
    <ConnectingRegionBase>
      <StenographerAnimation size="empty" />
      <ConnectingLabel>{REGION_LABEL[state]}</ConnectingLabel>
    </ConnectingRegionBase>
  );
}
