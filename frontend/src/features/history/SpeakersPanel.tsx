import { useEffect, type ReactNode } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { useMachine } from '@xstate/react';
import styled from 'styled-components';
import type { TurnSpeaker } from '../../shared/api/types';
import { useErrorText } from '../../shared/i18n/useErrorText';
import { useMessages } from '../../shared/i18n/useMessages';
import { useWriteControl } from '../../app/ConnectionContext';
import { Button } from '../../shared/ui/Button';
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog';
import { controlStyle } from '../../shared/ui/controlStyle';
import { Row, Stack } from '../../shared/ui/layout';
import { IsolatedText } from '../../shared/ui/IsolatedText';
import { Text } from '../../shared/ui/typography';
import { standaloneDirection } from '../../shared/textDirection';
import { speakerRenameMachine } from './speakerRenameMachine';
import { resetMergesMachine, speakerMergeMachine } from './speakerMergeMachine';

// The speaker-name field: the shared control look with its focus ring (item 25).
const NameInput = styled.input`
  ${controlStyle}
`;

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
  // Item 73's amendment: whether the transcript's reassignment mode is on.
  reassigning: boolean;
  // The "Reassign speakers" action was pressed.
  onStartReassign: () => void;
}

const ErrorText = styled(Text)`
  color: ${(props) => props.theme.colors.danger};
  margin: 0;
`;

// "A", "A and B", "A, B and C" in English, as the catalog reads today; every other locale gets
// its own conjunction from Intl (zh `A、B和C`, fr `A, B et C`), so the confirmation never shows
// an English "and" inside a translated sentence. Each name is an isolated element in its own
// direction (item 76), so it cannot reorder the sentence around it.
function joinNames(names: string[], locale: string): ReactNode[] {
  const parts: { type: string; value: string }[] = locale.toLowerCase().startsWith('en')
    ? names.flatMap((value, index) => [
        ...(index === 0
          ? []
          : [{ type: 'literal', value: index === names.length - 1 ? ' and ' : ', ' }]),
        { type: 'element', value },
      ])
    : new Intl.ListFormat(locale, { type: 'conjunction' }).formatToParts(names);
  return parts.map((part, index) =>
    part.type === 'element' ? <IsolatedText key={index} text={part.value} /> : part.value,
  );
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
  reassigning,
  onStartReassign,
}: SpeakersPanelProps) {
  const t = useMessages();
  const intl = useIntl();
  const errorText = useErrorText();
  const [merge, mergeSend] = useMachine(
    speakerMergeMachine.provide({ actions: { merged: () => onMergesChanged() } }),
    { input: { taskId } },
  );
  const [reset, resetSend] = useMachine(
    resetMergesMachine.provide({ actions: { reset: () => onMergesChanged() } }),
    { input: { taskId } },
  );
  const writes = useWriteControl();
  const nameOf = (speakerId: number) =>
    speakers.find((speaker) => speaker.speakerId === speakerId)?.speaker ?? '';
  const { group, into } = merge.context;
  const survivor = into === null ? '' : nameOf(into);
  const folded = group.filter((speakerId) => speakerId !== into).map(nameOf);

  return (
    <section aria-label={t('transcript.speakers.label')}>
      <Stack gap="sm">
        {speakers.map((speaker) => (
          <SpeakerNameField
            key={speaker.speakerId}
            taskId={taskId}
            speaker={speaker}
            disabled={reassigning}
            onSpeakersChanged={onSpeakersChanged}
          />
        ))}
        <Row gap="sm">
          {!reassigning ? (
            <Button
              type="button"
              variant="secondary"
              disabled={writes.disabled}
              title={writes.title}
              onClick={onStartReassign}
            >
              {t('transcript.speakers.reassign')}
            </Button>
          ) : null}
          {speakers.length >= 2 && merge.matches('closed') ? (
            <Button
              type="button"
              variant="secondary"
              disabled={reassigning}
              onClick={() => mergeSend({ type: 'OPEN' })}
            >
              {t('transcript.speakers.merge')}
            </Button>
          ) : null}
          {hasMerges ? (
            <Button
              type="button"
              variant="secondary"
              disabled={reassigning || reset.matches('sending') || writes.disabled}
              title={writes.title}
              onClick={() => resetSend({ type: 'RESET' })}
            >
              {t('transcript.speakers.resetMerges')}
            </Button>
          ) : null}
        </Row>
        {reset.context.error !== null ? (
          <ErrorText>{errorText(reset.context.error)}</ErrorText>
        ) : null}
        {merge.matches('closed') || reassigning ? null : (
          <Stack gap="xs">
            {speakers.map((speaker) => (
              <label key={speaker.speakerId}>
                <input
                  type="checkbox"
                  checked={group.includes(speaker.speakerId)}
                  onChange={() => mergeSend({ type: 'TOGGLE', speakerId: speaker.speakerId })}
                />
                <IsolatedText text={speaker.speaker} />
              </label>
            ))}
            <label>
              {t('transcript.speakers.keep')}
              <select
                aria-label={t('transcript.speakers.keep')}
                value={into === null ? '' : String(into)}
                onChange={(event) =>
                  mergeSend({ type: 'KEEP', speakerId: Number(event.target.value) })
                }
              >
                {group.map((speakerId) => (
                  <option
                    key={speakerId}
                    value={String(speakerId)}
                    dir={standaloneDirection(nameOf(speakerId))}
                  >
                    {nameOf(speakerId)}
                  </option>
                ))}
              </select>
            </label>
            <Row gap="sm">
              <Button
                type="button"
                disabled={writes.disabled}
                title={writes.title}
                onClick={() => mergeSend({ type: 'REQUEST' })}
              >
                {t('transcript.speakers.mergeAction')}
              </Button>
              <Button
                type="button"
                variant="secondary"
                onClick={() => mergeSend({ type: 'CLOSE' })}
              >
                {t('transcript.speakers.close')}
              </Button>
            </Row>
            {merge.context.error !== null ? (
              <ErrorText>{errorText(merge.context.error)}</ErrorText>
            ) : null}
          </Stack>
        )}
      </Stack>
      <ConfirmDialog
        open={!reassigning && (merge.matches('confirming') || merge.matches('sending'))}
        title={t('transcript.speakers.mergeTitle')}
        confirmLabel={t('transcript.speakers.mergeAction')}
        confirmDisabled={writes.disabled}
        confirmTitle={writes.title}
        onConfirm={() => mergeSend({ type: 'CONFIRM' })}
        onCancel={() => mergeSend({ type: 'CANCEL' })}
      >
        <FormattedMessage
          id="transcript.speakers.mergeBody"
          values={{
            names: joinNames(folded, intl.locale),
            survivor: <IsolatedText text={survivor} />,
          }}
        />
      </ConfirmDialog>
    </section>
  );
}

interface SpeakerNameFieldProps {
  taskId: number;
  speaker: TurnSpeaker;
  // Disabled while the transcript's reassignment mode is on (item 73's amendment).
  disabled: boolean;
  onSpeakersChanged: (speakers: TurnSpeaker[]) => void;
}

function SpeakerNameField({ taskId, speaker, disabled, onSpeakersChanged }: SpeakerNameFieldProps) {
  const t = useMessages();
  const errorText = useErrorText();
  const [state, send] = useMachine(
    speakerRenameMachine.provide({
      actions: { renamed: (_, result) => onSpeakersChanged(result.speakers) },
    }),
    { input: { taskId, speakerId: speaker.speakerId, stored: speaker.speaker } },
  );
  const writes = useWriteControl();

  // The stored name can change from outside the field (a server event): follow it.
  useEffect(() => {
    send({ type: 'STORED', name: speaker.speaker });
  }, [send, speaker.speaker]);

  return (
    <Stack gap="xs">
      <Row gap="sm">
        <NameInput
          type="text"
          aria-label={t('transcript.speakers.nameLabel', { number: speaker.speakerId + 1 })}
          value={state.context.draft}
          dir={standaloneDirection(state.context.draft)}
          disabled={disabled || state.matches('sending') || writes.disabled}
          title={writes.title}
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
          aria-label={t('transcript.speakers.resetNameLabel', {
            number: speaker.speakerId + 1,
          })}
          disabled={disabled || !state.can({ type: 'RESET' }) || writes.disabled}
          title={writes.title}
          onClick={() => send({ type: 'RESET' })}
        >
          {t('transcript.speakers.resetName')}
        </Button>
      </Row>
      {state.context.error !== null ? (
        <ErrorText>{errorText(state.context.error)}</ErrorText>
      ) : null}
    </Stack>
  );
}
