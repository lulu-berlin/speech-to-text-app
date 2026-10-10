import { Navigate, Route, Routes } from 'react-router';
import styled, { createGlobalStyle, ThemeProvider } from 'styled-components';
import { HistoryView } from '../features/history/HistoryView';
import { TaskTranscriptView } from '../features/history/TaskTranscriptView';
import { QueueView } from '../features/queue/QueueView';
import plexSansUrl from '../shared/theme/fonts/ibm-plex-sans-latin-wght-normal.woff2?url';
import { resolveTheme, useThemeMode } from '../shared/theme/themeMode';
import { usePrefersDark } from '../shared/theme/usePrefersDark';
import { AppHeader } from './AppHeader';
import { ConnectionContext } from './ConnectionContext';
import { NotFoundView } from './NotFoundView';

// The document itself carries the theme, so overscroll and the whole page match the tokens.
// The self-hosted font is declared here; its URL comes from the asset import, because a
// relative URL inside this template is not rewritten by the bundler.
const GlobalStyle = createGlobalStyle`
  @font-face {
    font-family: "IBM Plex Sans";
    src: url(${plexSansUrl}) format("woff2");
    font-weight: 100 700;
    font-display: swap;
  }

  body {
    margin: 0;
    background: ${(props) => props.theme.colors.background};
    color: ${(props) => props.theme.colors.text};
    font-family: ${(props) => props.theme.fontFamilies.sans};
    font-size: ${(props) => props.theme.fontSizes.body};
    line-height: ${(props) => props.theme.lineHeights.body};
  }
`;

const Shell = styled.div`
  min-height: ${(props) => props.theme.sizes.viewportMinHeight};
  font-family: ${(props) => props.theme.fontFamilies.sans};
  font-size: ${(props) => props.theme.fontSizes.body};
  line-height: ${(props) => props.theme.lineHeights.body};
  background: ${(props) => props.theme.colors.background};
  color: ${(props) => props.theme.colors.text};
`;

const Main = styled.main`
  background: ${(props) => props.theme.colors.background};
  color: ${(props) => props.theme.colors.text};
`;

export function App() {
  const [themeMode, cycleMode] = useThemeMode();
  const theme = resolveTheme(themeMode, usePrefersDark());
  return (
    <ThemeProvider theme={theme}>
      <GlobalStyle />
      <ConnectionContext.Provider>
        <Shell>
          <AppHeader themeMode={themeMode} onCycleThemeMode={cycleMode} />
          <Main>
            <Routes>
              <Route path="/" element={<Navigate to="/queue" replace />} />
              <Route path="/queue" element={<QueueView />} />
              <Route path="/history" element={<HistoryView />} />
              <Route path="/history/:taskId" element={<TaskTranscriptView />} />
              <Route path="*" element={<NotFoundView />} />
            </Routes>
          </Main>
        </Shell>
      </ConnectionContext.Provider>
    </ThemeProvider>
  );
}
