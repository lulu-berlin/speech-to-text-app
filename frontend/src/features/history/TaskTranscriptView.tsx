import { useEffect, useMemo, useRef, useState } from 'react';
import { useParams } from 'react-router';
import styled, { useTheme } from 'styled-components';
import { ConnectionContext } from '../../app/ConnectionContext';
import { getTurns } from '../../shared/api/client';
import type { Segment, Turn, Turns } from '../../shared/api/types';
import { Page, Row, Stack } from '../../shared/ui/layout';
import { PageHeading, Text } from '../../shared/ui/typography';
import { speakerTint } from '../../shared/theme/speakerPalette';
import { focusRing } from '../../shared/ui/focusRing';
import { segmentAtTime } from './segmentTiming';

function formatTime(ms: number): string {
  const totalSeconds = Math.floor(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

// The transcript download formats (item 10, ADR 0035): txt, srt, vtt.
const DOWNLOAD_FORMATS = ['txt', 'srt', 'vtt'] as const;

const DownloadLink = styled.a`
  color: ${(props) => props.theme.colors.accent};
  text-decoration: none;

  &:hover {
    text-decoration: underline;
  }
`;

const Title = styled(PageHeading)`
  margin: 0;
`;

const NoAudioNote = styled(Text)`
  color: ${(props) => props.theme.colors.textMuted};
  font-size: ${(props) => props.theme.fontSizes.small};
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

const Segment = styled.button<{ $active: boolean }>`
  border: none;
  padding: 0;
  background: ${(props) => (props.$active ? props.theme.colors.accent : 'transparent')};
  color: ${(props) => (props.$active ? props.theme.colors.accentText : 'inherit')};
  font: inherit;
  cursor: pointer;
  ${focusRing}
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
    if (turns === null) {
      return null;
    }
    // For speakers off, the turns carry speakerId null and the speakers list is empty, so
    // the lookup yields undefined and no name (nor tint) is rendered.
    return turns.turns.map((turn) => ({ turn, speaker: turns.speakers[turn.speakerId ?? 0] }));
  }, [turns]);

  // The playhead on the /audio timeline in milliseconds (item 8): updated from the audio
  // element's timeupdate and seeked events; drives the playback highlight.
  const [currentTimeMs, setCurrentTimeMs] = useState(0);
  const audioRef = useRef<HTMLAudioElement>(null);

  // Clicking a segment seeks the player to the segment's start and plays from there
  // (item 9, decided 2026-10-09).
  const seekTo = (startMs: number) => {
    const audio = audioRef.current;
    if (audio === null) {
      return;
    }
    setCurrentTimeMs(startMs);
    audio.currentTime = startMs / 1000;
    audio.play().catch(() => {
      /* A browser may refuse playback before any user gesture (autoplay policy); the seek
         itself still landed, so the highlight follows. */
    });
  };

  // All segments in time order, so the highlight can be a binary search over the starts.
  const segmentsInOrder = useMemo(
    () => (turns === null ? [] : turns.turns.map((turn) => turn.segments).flat()),
    [turns],
  );
  const activeSegment = segmentAtTime(segmentsInOrder, currentTimeMs);

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
        <Row gap="sm">
          {DOWNLOAD_FORMATS.map((format) => (
            <DownloadLink
              key={format}
              href={`/api/tasks/${id}/transcript?format=${format}`}
              aria-label={`Download transcript (${format})`}
            >
              {format.toUpperCase()}
            </DownloadLink>
          ))}
        </Row>
        {task?.hasAudio ? (
          <audio
            ref={audioRef}
            controls
            src={`/api/tasks/${id}/audio`}
            aria-label={task?.filename !== undefined ? `Audio for ${task.filename}` : 'Audio'}
            onTimeUpdate={(event) => setCurrentTimeMs(event.currentTarget.currentTime * 1000)}
            onSeeked={(event) => setCurrentTimeMs(event.currentTarget.currentTime * 1000)}
          >
            Your browser does not support the audio element.
          </audio>
        ) : task !== undefined ? (
          <NoAudioNote>This task has no audio because it ended before chunking.</NoAudioNote>
        ) : null}
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
                  {speaker !== undefined ? (
                    <SpeakerName $color={speakerTint(tints, speaker.speakerId)}>
                      {speaker.speaker}
                    </SpeakerName>
                  ) : null}{' '}
                  {turn.segments.map((segment) => (
                    <span key={`${segment.startMs}-${segment.endMs}`}>
                      <Segment
                        $active={segment === activeSegment}
                        data-active={segment === activeSegment || undefined}
                        onClick={() => seekTo(segment.startMs)}
                      >
                        {segment.text}
                      </Segment>{' '}
                    </span>
                  ))}
                </TurnText>
              </Stack>
            ))}
          </Stack>
        )}
      </Stack>
    </Page>
  );
}
