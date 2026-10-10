import { useEffect } from 'react';
import { useMachine } from '@xstate/react';
import styled from 'styled-components';
import type { TurnSpeaker } from '../../shared/api/types';
import { Stack } from '../../shared/ui/layout';
import { Text } from '../../shared/ui/typography';
import { speakerRenameMachine } from './speakerRenameMachine';

export interface SpeakersPanelProps {
  taskId: number;
  // The task's effective speakers, as /turns lists them.
  speakers: TurnSpeaker[];
  // Receives the task's speakers after a stored change (the endpoints' 200).
  onSpeakersChanged: (speakers: TurnSpeaker[]) => void;
}

const ErrorText = styled(Text)`
  color: ${(props) => props.theme.colors.danger};
  margin: 0;
`;

// The speakers panel above the transcript (item 22): one editable name per effective
// speaker, saved on Enter or blur.
export function SpeakersPanel({ taskId, speakers, onSpeakersChanged }: SpeakersPanelProps) {
  return (
    <section aria-label="Speakers">
      <Stack gap="sm">
        {speakers.map((speaker) => (
          <SpeakerNameField
            key={speaker.speakerId}
            taskId={taskId}
            speaker={speaker}
            onSpeakersChanged={onSpeakersChanged}
          />
        ))}
      </Stack>
    </section>
  );
}

interface SpeakerNameFieldProps {
  taskId: number;
  speaker: TurnSpeaker;
  onSpeakersChanged: (speakers: TurnSpeaker[]) => void;
}

function SpeakerNameField({ taskId, speaker, onSpeakersChanged }: SpeakerNameFieldProps) {
  const [state, send] = useMachine(
    speakerRenameMachine.provide({
      actions: { renamed: (_, result) => onSpeakersChanged(result.speakers) },
    }),
    { input: { taskId, speakerId: speaker.speakerId, stored: speaker.speaker } },
  );

  // The stored name can change from outside the field (a server event): follow it.
  useEffect(() => {
    send({ type: 'STORED', name: speaker.speaker });
  }, [send, speaker.speaker]);

  return (
    <Stack gap="xs">
      <input
        type="text"
        aria-label={`Name of speaker ${speaker.speakerId + 1}`}
        value={state.context.draft}
        disabled={state.matches('sending')}
        onChange={(event) => send({ type: 'EDIT', value: event.target.value })}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            send({ type: 'SAVE' });
          }
        }}
        onBlur={() => send({ type: 'SAVE' })}
      />
      {state.context.error !== null ? <ErrorText>{state.context.error.message}</ErrorText> : null}
    </Stack>
  );
}
