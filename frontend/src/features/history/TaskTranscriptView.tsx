import { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router';
import styled, { useTheme } from 'styled-components';
import { ConnectionContext } from '../../app/ConnectionContext';
import { getTurns } from '../../shared/api/client';
import type { Turn, Turns } from '../../shared/api/types';
import { Page, Stack } from '../../shared/ui/layout';
import { PageHeading, Text } from '../../shared/ui/typography';
import { speakerTint } from '../../shared/theme/speakerPalette';

function formatTime(ms: number): string {
  const totalSeconds = Math.floor(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

const Title = styled(PageHeading)`
  margin: 0;
`;

const TurnText = styled(Text)`
  margin: 0;
`;

const SpeakerName = styled.span<{ $color: string }>`
  color: ${(props) => props.$color};
  font-weight: ${(props) => props.theme.fontWeights.medium};
`;

const Timestamp = styled.span`
  color: ${(props) => props.theme.colors.textMuted};
  font-size: ${(props) => props.theme.fontSizes.small};
`;

export function TaskTranscriptView() {
  const { taskId } = useParams();
  const id = Number(taskId);
  const tasks = ConnectionContext.useSelector((state) => state.context.tasks);
  const task = tasks.find((candidate) => candidate.id === id);
  const [turns, setTurns] = useState<Turns | null>(null);

  useEffect(() => {
    if (task === undefined) {
      return;
    }
    let cancelled = false;
    getTurns(id)
      .then((result) => {
        if (!cancelled) {
          setTurns(result);
        }
      })
      .catch(() => {
        /* The turns fetch failing is handled by a later item (the failed/status page and
           the not-found view); until then a failed load leaves the turns empty. */
        if (!cancelled) {
          setTurns(null);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [id, task]);

  const theme = useTheme();
  const tints = theme.colors.speakerTints;
  const turnsBySpeaker = useMemo(() => {
    if (turns === null || task?.speakersMode === 'off') {
      return null;
    }
    return turns.turns.map((turn) => ({ turn, speaker: turns.speakers[turn.speakerId ?? 0] }));
  }, [turns, task?.speakersMode]);

  if (task === undefined) {
    return (
      <Page>
        <Title>Transcript</Title>
        <Text>Loading…</Text>
      </Page>
    );
  }

  return (
    <Page>
      <Stack gap="md">
        <Title>Transcript</Title>
        {turnsBySpeaker === null ? (
          <Text>Loading…</Text>
        ) : (
          <Stack gap="md">
            {turnsBySpeaker.map(({ turn, speaker }, index) => (
              <Stack key={index} gap="xs">
                <TurnText>
                  <Timestamp>
                    {formatTime(turn.startMs)} – {formatTime(turn.endMs)}
                  </Timestamp>{' '}
                  <SpeakerName $color={speakerTint(tints, speaker?.speakerId ?? 0)}>
                    {speaker?.speaker ?? ''}
                  </SpeakerName>{' '}
                  {turn.text}
                </TurnText>
              </Stack>
            ))}
          </Stack>
        )}
      </Stack>
    </Page>
  );
}
