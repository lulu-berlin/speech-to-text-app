import { Navigate, Route, Routes } from 'react-router';
import styled, { ThemeProvider } from 'styled-components';
import { HistoryView } from '../features/history/HistoryView';
import { TaskTranscriptView } from '../features/history/TaskTranscriptView';
import { QueueView } from '../features/queue/QueueView';
import { theme } from '../shared/theme/theme';
import { NotFoundView } from './NotFoundView';

const Title = styled.h1`
  color: ${(props) => props.theme.colors.text};
  font-size: ${(props) => props.theme.fontSizes.title};
`;

export function App() {
  return (
    <ThemeProvider theme={theme}>
      <header>
        <Title>Speech-to-Text</Title>
      </header>
      <Routes>
        <Route path="/" element={<Navigate to="/queue" replace />} />
        <Route path="/queue" element={<QueueView />} />
        <Route path="/history" element={<HistoryView />} />
        <Route path="/history/:taskId" element={<TaskTranscriptView />} />
        <Route path="*" element={<NotFoundView />} />
      </Routes>
    </ThemeProvider>
  );
}
