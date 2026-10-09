import { useParams } from 'react-router';
import { Page } from '../../shared/ui/layout';
import { PageHeading, Text } from '../../shared/ui/typography';

// Placeholder until roadmap item 15 fills the transcript view.
export function TaskTranscriptView() {
  const { taskId } = useParams();
  return (
    <Page>
      <PageHeading>Transcript</PageHeading>
      <Text>Task {taskId}</Text>
    </Page>
  );
}
