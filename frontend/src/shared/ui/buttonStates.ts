import { css } from 'styled-components';

// The state blocks every token-styled button shares: the keyboard focus ring and the
// disabled fill. Hover and active fills differ per control, so they stay with each
// component.
export const interactiveStates = css`
  &:focus-visible {
    outline: none;
    box-shadow: 0 0 0 ${(props) => props.theme.borderWidths.ring}
      ${(props) => props.theme.colors.focusRing};
  }

  &:disabled {
    cursor: default;
    background: ${(props) => props.theme.colors.surface};
    color: ${(props) => props.theme.colors.textMuted};
  }
`;
