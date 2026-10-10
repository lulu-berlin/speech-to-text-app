import { css } from 'styled-components';
import { focusRing } from './focusRing';

// The state blocks every token-styled button shares: the keyboard focus ring and the
// disabled fill. Hover and active fills differ per control, so they stay with each
// component.
export const interactiveStates = css`
  ${focusRing}

  &:disabled {
    cursor: default;
    background: ${(props) => props.theme.colors.surface};
    color: ${(props) => props.theme.colors.textMuted};
  }
`;
