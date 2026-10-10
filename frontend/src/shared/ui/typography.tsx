import styled from 'styled-components';

// Shared page typography. Colors and sizes come from the theme tokens only
// (frontend conventions: no literal colors or sizes in components).
// Page headings break anywhere: a heading can be a long filename (the transcript).
export const PageHeading = styled.h2`
  color: ${(props) => props.theme.colors.text};
  font-size: ${(props) => props.theme.fontSizes.title};
  overflow-wrap: anywhere;
`;

export const Text = styled.p`
  color: ${(props) => props.theme.colors.text};
`;

// A secondary line under a title (a task's date and duration, a card's error): small and
// muted, without the paragraph margin.
export const MetaText = styled(Text)`
  margin: 0;
  color: ${(props) => props.theme.colors.textMuted};
  font-size: ${(props) => props.theme.fontSizes.small};
`;
