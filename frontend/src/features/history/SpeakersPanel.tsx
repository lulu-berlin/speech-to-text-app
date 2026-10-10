import { useEffect } from 'react';
import { useMachine } from '@xstate/react';
import styled from 'styled-components';
import type { TurnSpeaker } from '../../shared/api/types';
import { Button } from '../../shared/ui/Button';
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog';
import { Row, Stack } from '../../shared/ui/layout';
import { Text } from '../../shared/ui/typography';
import { speakerRenameMachine } from './speakerRenameMachine';
import { resetMergesMachine, speakerMergeMachine } from './speakerMergeMachine';

export interface SpeakersPanelProps {
  taskId: number;
  // The task's effective speakers, as /turns lists them.
  speakers: TurnSpeaker[];
  // Whether the task has a merge to undo (/turns' hasMerges).
  hasMerges: boolean;
  // Receives the task's speakers after a stored rename (the endpoint's 200).
  onSpeakersChanged: (speakers: TurnSpeaker[]) => void;
  // Runs after a stored merge or reset; the turns themselves changed.
  onMergesChanged: () => void;
}

const ErrorText = styled(Text)`
  color: ${(props) => props.theme.colors.danger};
  margin: 0;
`;

// "A", "A and B", "A, B and C": the members folded into the survivor, for the confirmation.
function joinNames(names: string[]): string {
  if (names.length <= 1) {
    return names.join('');
  }
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
}

// The speakers panel above the transcript (item 22): one editable name per effective
// speaker, saved on Enter or blur; a merge form whose merge is confirmed in a dialog; and
// "Reset all merges" while a merge can be undone.
export function SpeakersPanel({
  taskId,
  speakers,
  hasMerges,
  onSpeakersChanged,
  onMergesChanged,
}: SpeakersPanelProps) {
  const [merge, mergeSend] = useMachine(
    speakerMergeMachine.provide({ actions: { merged: () => onMergesChanged() } }),
    { input: { taskId } },
  );
  const [reset, resetSend] = useMachine(
    resetMergesMachine.provide({ actions: { reset: () => onMergesChanged() } }),
    { input: { taskId } },
  );
  const nameOf = (speakerId: number) =>
    speakers.find((speaker) => speaker.speakerId === speakerId)?.speaker ?? '';
  const { group, into } = merge.context;
  const survivor = into === null ? '' : nameOf(into);
  const folded = group.filter((speakerId) => speakerId !== into).map(nameOf);

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
        <Row gap="sm">
          {speakers.length >= 2 && merge.matches('closed') ? (
            <Button type="button" variant="secondary" onClick={() => mergeSend({ type: 'OPEN' })}>
              Merge speakers…
            </Button>
          ) : null}
          {hasMerges ? (
            <Button
              type="button"
              variant="secondary"
              disabled={reset.matches('sending')}
              onClick={() => resetSend({ type: 'RESET' })}
            >
              Reset all merges
            </Button>
          ) : null}
        </Row>
        {reset.context.error !== null ? <ErrorText>{reset.context.error.message}</ErrorText> : null}
        {merge.matches('closed') ? null : (
          <Stack gap="xs">
            {speakers.map((speaker) => (
              <label key={speaker.speakerId}>
                <input
                  type="checkbox"
                  checked={group.includes(speaker.speakerId)}
                  onChange={() => mergeSend({ type: 'TOGGLE', speakerId: speaker.speakerId })}
                />
                {speaker.speaker}
              </label>
            ))}
            <label>
              Keep
              <select
                aria-label="Keep"
                value={into === null ? '' : String(into)}
                onChange={(event) =>
                  mergeSend({ type: 'KEEP', speakerId: Number(event.target.value) })
                }
              >
                {group.map((speakerId) => (
                  <option key={speakerId} value={String(speakerId)}>
                    {nameOf(speakerId)}
                  </option>
                ))}
              </select>
            </label>
            <Row gap="sm">
              <Button type="button" onClick={() => mergeSend({ type: 'REQUEST' })}>
                Merge
              </Button>
              <Button
                type="button"
                variant="secondary"
                onClick={() => mergeSend({ type: 'CLOSE' })}
              >
                Close
              </Button>
            </Row>
            {merge.context.error !== null ? (
              <ErrorText>{merge.context.error.message}</ErrorText>
            ) : null}
          </Stack>
        )}
      </Stack>
      <ConfirmDialog
        open={merge.matches('confirming') || merge.matches('sending')}
        title="Merge speakers?"
        confirmLabel="Merge"
        onConfirm={() => mergeSend({ type: 'CONFIRM' })}
        onCancel={() => mergeSend({ type: 'CANCEL' })}
      >
        Merge {joinNames(folded)} into {survivor}? Their turns join {survivor}; you can undo this.
      </ConfirmDialog>
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
      <Row gap="sm">
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
        <Button
          type="button"
          variant="secondary"
          aria-label={`Reset name of speaker ${speaker.speakerId + 1}`}
          disabled={state.matches('sending')}
          onClick={() => send({ type: 'RESET' })}
        >
          Reset name
        </Button>
      </Row>
      {state.context.error !== null ? <ErrorText>{state.context.error.message}</ErrorText> : null}
    </Stack>
  );
}
