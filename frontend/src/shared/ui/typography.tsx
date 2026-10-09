import styled from 'styled-components';

// Shared page typography. Colors and sizes come from the theme tokens only
// (frontend conventions: no literal colors or sizes in components).
export const PageHeading = styled.h2`
  color: ${(props) => props.theme.colors.text};
  font-size: ${(props) => props.theme.fontSizes.title};
`;

export const Text = styled.p`
  color: ${(props) => props.theme.colors.text};
`;
