import { css } from 'styled-components';

// The floating panels (tooltips, menus): a raised surface with a border, rounded corners
// and a soft elevation shadow. Padding stays with each consumer.
export const floatingPanel = css`
  border: ${(props) => props.theme.borderWidths.thin} solid ${(props) => props.theme.colors.border};
  border-radius: ${(props) => props.theme.radii.card};
  background: ${(props) => props.theme.colors.surfaceRaised};
  color: ${(props) => props.theme.colors.text};
  box-shadow: ${(props) => props.theme.shadows.card};
`;
