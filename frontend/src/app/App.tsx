import { Navigate, Route, Routes } from 'react-router';
import { useCallback, useMemo, useState } from 'react';
import styled, { createGlobalStyle, ThemeProvider } from 'styled-components';
import { HistoryView } from '../features/history/HistoryView';
import { TaskTranscriptView } from '../features/history/TaskTranscriptView';
import { QueueView } from '../features/queue/QueueView';
import { PlaceholderMascotContext } from '../shared/brand/PlaceholderMascot';
import plexSansUrl from '../shared/theme/fonts/ibm-plex-sans-latin-wght-normal.woff2?url';
import { resolveTheme, useThemeMode } from '../shared/theme/themeMode';
import { usePrefersDark } from '../shared/theme/usePrefersDark';
import { AppHeader } from './AppHeader';
import { ConnectingRegion } from './ConnectingRegion';
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
  // The one-mascot rule (item 31): the shell counts the placeholder mascots on screen so
  // the header can swap to the static logo while one shows the artwork. Registration is
  // stable; only the count recreates the handle.
  const [placeholderCount, setPlaceholderCount] = useState(0);
  const registerPlaceholder = useCallback(() => {
    setPlaceholderCount((count) => count + 1);
    return () => setPlaceholderCount((count) => count - 1);
  }, []);
  const placeholderHandle = useMemo(
    () => ({ count: placeholderCount, register: registerPlaceholder }),
    [placeholderCount, registerPlaceholder],
  );
  return (
    <ThemeProvider theme={theme}>
      <GlobalStyle />
      <ConnectionContext.Provider>
        <PlaceholderMascotContext.Provider value={placeholderHandle}>
          <Shell>
            <AppHeader themeMode={themeMode} onCycleThemeMode={cycleMode} />
            <ConnectingRegion />
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
        </PlaceholderMascotContext.Provider>
      </ConnectionContext.Provider>
    </ThemeProvider>
  );
}
