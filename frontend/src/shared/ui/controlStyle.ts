import { css } from 'styled-components';
import { focusRing } from './focusRing';

// The look of the form controls (selects; text, search and number inputs): a token border
// on the surface fill, with the shared focus ring (item 25; the Queue's upload options
// and History's search and status filter).
export const controlStyle = css`
  padding: ${(props) => props.theme.spacing.sm} ${(props) => props.theme.spacing.md};
  border: ${(props) => props.theme.borderWidths.thin} solid ${(props) => props.theme.colors.border};
  border-radius: ${(props) => props.theme.radii.button};
  background: ${(props) => props.theme.colors.surface};
  color: ${(props) => props.theme.colors.text};
  font: inherit;

  ${focusRing}
`;
