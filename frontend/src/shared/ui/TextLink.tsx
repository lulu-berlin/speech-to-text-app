import { Link } from 'react-router';
import styled from 'styled-components';
import { focusRing } from './focusRing';

// An in-app link in running text or as a callout's action: accent text with the shared
// focus ring (the empty and not-found states' ways onward).
export const TextLink = styled(Link)`
  color: ${(props) => props.theme.colors.accent};

  ${focusRing}
`;
