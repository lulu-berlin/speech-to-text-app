import { Navigate, Route, Routes } from 'react-router';
import styled, { createGlobalStyle, ThemeProvider } from 'styled-components';
import { HistoryView } from '../features/history/HistoryView';
import { TaskTranscriptView } from '../features/history/TaskTranscriptView';
import { QueueView } from '../features/queue/QueueView';
import { SettingsView } from '../features/settings/SettingsView';
import plexSansUrl from '../shared/theme/fonts/ibm-plex-sans-latin-wght-normal.woff2?url';
import { resolveTheme } from '../shared/theme/themeMode';
import { usePrefersDark } from '../shared/theme/usePrefersDark';
import { AppHeader } from './AppHeader';
import { ConnectingRegion } from './ConnectingRegion';
import { ConnectionContext } from './ConnectionContext';
import { SettingsLocaleProvider } from './LocaleProvider';
import { SettingsContext, SettingsSync } from './SettingsContext';
import { NotFoundView } from './NotFoundView';
import { TypingFavicon } from './TypingFavicon';
import { useThemeMode } from './useThemeMode';

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
  return (
    <ConnectionContext.Provider>
      <SettingsContext.Provider>
        <SettingsLocaleProvider>
          <SettingsSync />
          <ThemedApp />
        </SettingsLocaleProvider>
      </SettingsContext.Provider>
    </ConnectionContext.Provider>
  );
}

// The page in the stored theme mode (roadmap item 67): inside the settings provider, since the
// service stores the mode with the other settings.
function ThemedApp() {
  const { mode, cycle, ready } = useThemeMode();
  const theme = resolveTheme(mode, usePrefersDark());
  return (
    <ThemeProvider theme={theme}>
      <GlobalStyle />
      <TypingFavicon />
      <Shell>
        <AppHeader themeMode={mode} themeReady={ready} onCycleThemeMode={cycle} />
        <ConnectingRegion />
        <Main>
          <Routes>
            <Route path="/" element={<Navigate to="/queue" replace />} />
            <Route path="/queue" element={<QueueView />} />
            <Route path="/settings" element={<SettingsView />} />
            <Route path="/history" element={<HistoryView />} />
            <Route path="/history/:taskId" element={<TaskTranscriptView />} />
            <Route path="*" element={<NotFoundView />} />
          </Routes>
        </Main>
      </Shell>
    </ThemeProvider>
  );
}
