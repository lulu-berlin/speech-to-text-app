import { Navigate, Route, Routes } from 'react-router';
import { ThemeProvider } from 'styled-components';
import { HistoryView } from '../features/history/HistoryView';
import { TaskTranscriptView } from '../features/history/TaskTranscriptView';
import { QueueView } from '../features/queue/QueueView';
import { theme } from '../shared/theme/theme';
import { AppHeader } from './AppHeader';
import { ConnectionContext } from './ConnectionContext';
import { NotFoundView } from './NotFoundView';

export function App() {
  return (
    <ThemeProvider theme={theme}>
      <ConnectionContext.Provider>
        <AppHeader />
        <Routes>
          <Route path="/" element={<Navigate to="/queue" replace />} />
          <Route path="/queue" element={<QueueView />} />
          <Route path="/history" element={<HistoryView />} />
          <Route path="/history/:taskId" element={<TaskTranscriptView />} />
          <Route path="*" element={<NotFoundView />} />
        </Routes>
      </ConnectionContext.Provider>
    </ThemeProvider>
  );
}
