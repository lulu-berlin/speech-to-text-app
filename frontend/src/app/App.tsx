import { Navigate, Route, Routes } from 'react-router';
import styled, { createGlobalStyle, ThemeProvider } from 'styled-components';
import { HistoryView } from '../features/history/HistoryView';
import { TaskTranscriptView } from '../features/history/TaskTranscriptView';
import { QueueView } from '../features/queue/QueueView';
import { themeFor } from '../shared/theme/theme';
import { usePrefersDark } from '../shared/theme/usePrefersDark';
import { AppHeader } from './AppHeader';
import { ConnectionContext } from './ConnectionContext';
import { NotFoundView } from './NotFoundView';

// The document itself carries the theme, so overscroll and the whole page match the tokens.
const GlobalStyle = createGlobalStyle`
  body {
    margin: 0;
    background: ${(props) => props.theme.colors.background};
    color: ${(props) => props.theme.colors.text};
    font-family: system-ui, -apple-system, 'Segoe UI', sans-serif;
  }
`;

const Shell = styled.div`
  min-height: ${(props) => props.theme.sizes.viewportMinHeight};
  background: ${(props) => props.theme.colors.background};
  color: ${(props) => props.theme.colors.text};
`;

const Main = styled.main`
  padding: ${(props) => props.theme.spacing.lg};
  background: ${(props) => props.theme.colors.background};
  color: ${(props) => props.theme.colors.text};
`;

export function App() {
  const theme = themeFor(usePrefersDark());
  return (
    <ThemeProvider theme={theme}>
      <GlobalStyle />
      <ConnectionContext.Provider>
        <Shell>
          <AppHeader />
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
