import styled, { ThemeProvider } from 'styled-components';
import { theme } from '../shared/theme/theme';

const Title = styled.h1`
  color: ${(props) => props.theme.colors.text};
  font-size: ${(props) => props.theme.fontSizes.title};
`;

export function App() {
  return (
    <ThemeProvider theme={theme}>
      <Title>Speech-to-Text</Title>
    </ThemeProvider>
  );
}
