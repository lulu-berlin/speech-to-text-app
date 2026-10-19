import styled, { css } from 'styled-components';
import { focusRing } from './focusRing';

// A quiet, text-style button: accent text on no fill, for secondary actions that sit in
// a line of text, a form or a card (the Queue's "Advanced" toggle, History's "Clear
// filters" and its cards' Retry); the danger tone is for destructive ones, and the quiet
// tone keeps a destructive action muted until it is hovered (item 59).
export const TextButton = styled.button.attrs({ type: 'button' })<{
  $tone?: 'accent' | 'danger' | 'quiet';
}>`
  padding: ${(props) => props.theme.spacing.xs} ${(props) => props.theme.spacing.sm};
  border: none;
  border-radius: ${(props) => props.theme.radii.button};
  background: transparent;
  color: ${(props) =>
    props.$tone === 'quiet'
      ? props.theme.colors.textMuted
      : props.theme.colors[props.$tone ?? 'accent']};
  font: inherit;
  cursor: pointer;

  ${(props) =>
    props.$tone === 'quiet'
      ? css`
          &:hover {
            color: ${props.theme.colors.danger};
          }
        `
      : ''}

  ${focusRing}
`;
