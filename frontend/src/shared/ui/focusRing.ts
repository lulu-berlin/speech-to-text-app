import { css } from 'styled-components';

// The keyboard focus indicator: a token ring in place of the default outline.
export const focusRing = css`
  &:focus-visible {
    outline: none;
    box-shadow: 0 0 0 ${(props) => props.theme.borderWidths.ring}
      ${(props) => props.theme.colors.focusRing};
  }
`;

// The same ring for containers that are not focusable themselves but hold a focusable
// control (list rows): shown when the control inside is keyboard-focused.
export const focusWithinRing = css`
  &:focus-within {
    box-shadow: 0 0 0 ${(props) => props.theme.borderWidths.ring}
      ${(props) => props.theme.colors.focusRing};
  }
`;
