import type { RefObject } from 'react';
import { Pause, Play } from 'lucide-react';
import styled from 'styled-components';
import { IconButton } from '../../shared/ui/IconButton';
import { Row, Stack } from '../../shared/ui/layout';
import { Slider } from '../../shared/ui/Slider';
import { Surface } from '../../shared/ui/Surface';
import { formatDuration } from './formatTask';

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
  // The audio element started or stopped playing.
  onPlayingChange: (playing: boolean) => void;
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
}: AudioPlayerProps) {
  const toggle = () => {
    const audio = audioRef.current;
    if (audio === null) {
      return;
    }
    if (playing) {
      audio.pause();
    } else {
      audio.play().catch(() => {
        /* A browser may refuse playback (autoplay policy); the element's own events keep
           the button in step with what actually happens. */
      });
    }
  };

  return (
    <Surface role="group" aria-label="Audio player" padding="md">
      <audio
        ref={audioRef}
        src={src}
        aria-label={`Audio for ${filename}`}
        onTimeUpdate={(event) => onPlayhead(event.currentTarget.currentTime * 1000)}
        onSeeked={(event) => onPlayhead(event.currentTarget.currentTime * 1000)}
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
            onClick={toggle}
          />
          <Times>{`${formatDuration(currentTimeMs)} / ${formatDuration(durationMs)}`}</Times>
        </ControlsRow>
      </Stack>
    </Surface>
  );
}
