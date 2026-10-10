import styled from 'styled-components';
import { focusRing } from './focusRing';

// A quiet, text-style button: accent text on no fill, for secondary actions that sit in
// a line of text or a form (the Queue's "Advanced" toggle, History's "Clear filters").
export const TextButton = styled.button.attrs({ type: 'button' })`
  padding: ${(props) => props.theme.spacing.xs} ${(props) => props.theme.spacing.sm};
  border: none;
  border-radius: ${(props) => props.theme.radii.button};
  background: transparent;
  color: ${(props) => props.theme.colors.accent};
  font: inherit;
  cursor: pointer;

  ${focusRing}
`;
