import { styled } from 'styled-components';
import type { Task, TaskPhase } from '../../shared/api/types';
import { DiarizationProgressBar } from '../../shared/ui/DiarizationProgressBar';
import { ListRow } from '../../shared/ui/ListRow';
import { ProgressBar } from '../../shared/ui/ProgressBar';
import { StatusBadge } from '../../shared/ui/StatusBadge';
import { Row, Stack } from '../../shared/ui/layout';
import { RowContent, RowName } from './rowParts';

// The phase a task is in, as a text label next to its status (none while queued).
const PHASE_LABELS: Record<NonNullable<TaskPhase>, string> = {
  CHUNKING: 'Chunking',
  TRANSCRIBING: 'Transcribing',
  DIARIZING: 'Diarizing',
};

const RowPhase = styled.span`
  color: ${(props) => props.theme.colors.textMuted};
  font-size: ${(props) => props.theme.fontSizes.small};
`;

export interface TaskRowProps {
  task: Task;
}

// One pending task of the queue list: its filename, phase and status, and the two
// progress bars (transcription; diarization by stage, hidden when it does not apply).
export function TaskRow({ task }: TaskRowProps) {
  return (
    <ListRow>
      <Stack gap="sm">
        <RowContent>
          <RowName>{task.filename}</RowName>
          <Row>
            {task.phase !== null ? <RowPhase>{PHASE_LABELS[task.phase]}</RowPhase> : null}
            <StatusBadge status={task.status} />
          </Row>
        </RowContent>
        <ProgressBar value={task.transcriptionProgress} label="Transcription" />
        {task.diarizationProgress !== null ? (
          <DiarizationProgressBar
            stage={task.diarizationStage}
            progress={task.diarizationProgress}
          />
        ) : null}
      </Stack>
    </ListRow>
  );
}
