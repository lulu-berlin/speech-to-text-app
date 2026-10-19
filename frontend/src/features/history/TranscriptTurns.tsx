import styled, { useTheme } from 'styled-components';
import type { Segment, Turn, TurnSpeaker } from '../../shared/api/types';
import { speakerTint } from '../../shared/theme/speakerPalette';
import { focusRing } from '../../shared/ui/focusRing';
import { Stack } from '../../shared/ui/layout';
import { Text } from '../../shared/ui/typography';
import { formatDuration } from '../../shared/formatDuration';

// One turn of the reading column: its start time in a left gutter of one shared width,
// and the speaker's name on its own line above the turn's text. On a phone the gutter
// folds into one column, the time above the speaker.
const TurnRow = styled.div`
  display: grid;
  grid-template-columns: ${(props) => props.theme.sizes.timeGutter} 1fr;
  gap: ${(props) => props.theme.spacing.md};

  @media (max-width: ${(props) => props.theme.breakpoints.sm}) {
    grid-template-columns: 1fr;
  }
`;

// A turn's start time in the gutter: muted, small, fixed-width digits.
const TurnGutter = styled.span`
  color: ${(props) => props.theme.colors.textMuted};
  font-size: ${(props) => props.theme.fontSizes.small};
  font-variant-numeric: tabular-nums;
`;

const TurnBody = styled.div`
  min-width: 0;
`;

// The turns list: the large gap between turns.
const TurnsList = styled(Stack)`
  gap: ${(props) => props.theme.spacing.lg};
`;

// The turn's text at the transcript's reading line height.
const TurnText = styled(Text)`
  margin: 0;
  line-height: ${(props) => props.theme.lineHeights.reading};
  overflow-wrap: anywhere;
`;

const SpeakerName = styled.span<{ $color: string }>`
  display: block;
  color: ${(props) => props.$color};
  font-weight: ${(props) => props.theme.fontWeights.medium};
`;

// A segment of a turn: inline text that seeks the player to its start (item 9), highlighted
// while the playhead is inside it. An inline element with the button role, not a <button>:
// a <button> is an inline-block box that wraps as a whole and centers its own lines, so a
// turn would not read as one paragraph (item 29).
const SegmentButton = styled.span<{ $active: boolean }>`
  background: ${(props) => (props.$active ? props.theme.colors.accent : 'transparent')};
  color: ${(props) => (props.$active ? props.theme.colors.accentText : 'inherit')};
  cursor: pointer;
  // Follow mode (item 31, item 59): the active segment is scrolled into view while
  // playing, and the browser honors the target's scroll margin for every scrolled
  // ancestor, so this top margin — the text-only compact header's height plus its border
  // — keeps the playing segment clear of the sticky header. The phone's wrapped header
  // is taller, so it reserves more there.
  scroll-margin-top: calc(
    ${(props) => props.theme.sizes.compactHeader} + ${(props) => props.theme.borderWidths.thin}
  );
  @media (max-width: ${(props) => props.theme.breakpoints.sm}) {
    scroll-margin-top: calc(
      ${(props) => props.theme.sizes.compactHeaderPhone} +
        ${(props) => props.theme.borderWidths.thin}
    );
  }
  ${focusRing}
`;

export interface TranscriptTurnsProps {
  // Each turn with its speaker (undefined when speakers are off).
  turnsBySpeaker: { turn: Turn; speaker: TurnSpeaker | undefined }[];
  // The segment the playhead is in, or null.
  activeSegment: Segment | null;
  // A segment was clicked: seek to its start and play.
  onSegmentClick: (startMs: number) => void;
}

// The transcript's turns in the reading column (item 25): the start time in the gutter,
// the speaker in their tint above the text, and the segments as seek buttons.
export function TranscriptTurns({
  turnsBySpeaker,
  activeSegment,
  onSegmentClick,
}: TranscriptTurnsProps) {
  const tints = useTheme().colors.speakerTints;
  return (
    <TurnsList gap="lg">
      {turnsBySpeaker.map(({ turn, speaker }, index) => (
        <TurnRow key={index}>
          <TurnGutter>{formatDuration(turn.startMs)}</TurnGutter>
          <TurnBody>
            <Stack gap="xs">
              {speaker !== undefined ? (
                <SpeakerName $color={speakerTint(tints, speaker.speakerId)}>
                  {speaker.speaker}
                </SpeakerName>
              ) : null}
              <TurnText>
                {turn.segments.map((segment) => (
                  <span key={`${segment.startMs}-${segment.endMs}`}>
                    <SegmentButton
                      role="button"
                      tabIndex={0}
                      $active={segment === activeSegment}
                      data-active={segment === activeSegment || undefined}
                      onClick={() => onSegmentClick(segment.startMs)}
                      onKeyDown={(event) => {
                        // A native button's keys: Enter and Space activate, and Space must
                        // not scroll the page.
                        if (event.key === 'Enter' || event.key === ' ') {
                          event.preventDefault();
                          onSegmentClick(segment.startMs);
                        }
                      }}
                    >
                      {segment.text}
                    </SegmentButton>{' '}
                  </span>
                ))}
              </TurnText>
            </Stack>
          </TurnBody>
        </TurnRow>
      ))}
    </TurnsList>
  );
}
