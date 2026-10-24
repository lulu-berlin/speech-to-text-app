import type { RefObject } from 'react';
import { Pause, Play } from 'lucide-react';
import styled from 'styled-components';
import { IconButton } from '../../shared/ui/IconButton';
import { Row, Stack } from '../../shared/ui/layout';
import { Slider } from '../../shared/ui/Slider';
import { Surface } from '../../shared/ui/Surface';
import { formatDuration } from '../../shared/formatDuration';

const ControlsRow = styled(Row)`
  justify-content: space-between;
`;

// Current / total, muted, with fixed-width digits so the label does not jitter while the
// time runs.
const Times = styled.span`
  color: ${(props) => props.theme.colors.textMuted};
  font-size: ${(props) => props.theme.fontSizes.small};
  font-variant-numeric: tabular-nums;
`;

// The media's playhead in whole milliseconds, the unit of the segments' bounds and of every seek
// target. The element keeps seconds as a double, so a seek to a segment's start can read back a
// hair before it — 32.76 s is 32759.999999999996 ms once multiplied — and the previous segment
// would take the highlight (CI, 2026-10-24).
export function playheadMs(audio: HTMLMediaElement): number {
  return Math.round(audio.currentTime * 1000);
}

export interface AudioPlayerProps {
  src: string;
  filename: string;
  // The /audio timeline's length (the task's decoded duration).
  durationMs: number;
  currentTimeMs: number;
  // The seek bar's draft position in ms while the user drags it (null when not dragging);
  // the bar shows this instead of the playhead so they cannot fight (item 29).
  dragMs: number | null;
  playing: boolean;
  audioRef: RefObject<HTMLAudioElement | null>;
  // The playhead moved (playback, or a seek in the browser).
  onPlayhead: (ms: number) => void;
  // The seek bar was released at `ms`: move the audio there.
  onSeek: (ms: number) => void;
  // The user is dragging the seek bar towards `ms`; nothing is seeked yet.
  onDrag: (ms: number) => void;
  // The audio element started playing, or no longer plays (a pause, a stop, or the audio's end).
  onPlayingChange: (playing: boolean) => void;
  // The Play/Pause button was pressed: the owner pauses or resumes the audio (item 52: the
  // segment keys' toggle shares it, so there is one description of what the button does).
  onToggle: () => void;
  // Whether the button is disabled — an open segment editor blocks playback (item 52).
  disabled?: boolean;
}

// The themed transcript player (item 25): the audio element without native controls, and
// a stacked block — the seek bar (item 24's Slider, in seconds) full width on top, then
// play/pause on the left and current / total time on the right.
export function AudioPlayer({
  src,
  filename,
  durationMs,
  currentTimeMs,
  dragMs,
  playing,
  audioRef,
  onPlayhead,
  onSeek,
  onDrag,
  onPlayingChange,
  onToggle,
  disabled = false,
}: AudioPlayerProps) {
  return (
    <Surface role="group" aria-label="Audio player" padding="md">
      <audio
        ref={audioRef}
        src={src}
        aria-label={`Audio for ${filename}`}
        onTimeUpdate={(event) => onPlayhead(playheadMs(event.currentTarget))}
        onSeeked={(event) => onPlayhead(playheadMs(event.currentTarget))}
        onPlay={() => onPlayingChange(true)}
        onPause={() => onPlayingChange(false)}
        onEnded={() => onPlayingChange(false)}
      />
      <Stack gap="sm">
        <Slider
          value={Math.floor((dragMs ?? currentTimeMs) / 1000)}
          max={Math.floor(durationMs / 1000)}
          onChange={(seconds) => onDrag(seconds * 1000)}
          onCommit={(seconds) => onSeek(seconds * 1000)}
          label="Seek"
        />
        <ControlsRow gap="sm">
          <IconButton
            icon={playing ? Pause : Play}
            label={playing ? 'Pause' : 'Play'}
            onClick={onToggle}
            disabled={disabled}
          />
          <Times>{`${formatDuration(currentTimeMs)} / ${formatDuration(durationMs)}`}</Times>
        </ControlsRow>
      </Stack>
    </Surface>
  );
}
