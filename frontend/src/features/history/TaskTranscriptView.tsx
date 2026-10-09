import { useParams } from 'react-router';
import { PageHeading, Text } from '../../shared/ui/typography';

// Placeholder until roadmap item 15 fills the transcript view.
export function TaskTranscriptView() {
  const { taskId } = useParams();
  return (
    <>
      <PageHeading>Transcript</PageHeading>
      <Text>Task {taskId}</Text>
    </>
  );
}
