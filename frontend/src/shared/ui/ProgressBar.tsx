import { keyframes, styled } from 'styled-components';
import { ProgressFill, ProgressRow, ProgressTrack, ProgressValue } from './progressParts';

const sweep = keyframes`
  0% {
    transform: translateX(-100%);
  }
  100% {
    transform: translateX(250%);
  }
`;

// The indeterminate fill (item 59): chunking has no percentage yet, so a segment sweeps
// the track instead of the bar resting at 0 %. Under reduced motion it stays a still
// segment — the row's status line says what is happening either way.
const IndeterminateFill = styled.div`
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 40%;
  background: ${(props) => props.theme.colors.accent};

  @media (prefers-reduced-motion: no-preference) {
    animation: ${sweep} ${(props) => props.theme.motion.indeterminateDuration}
      ${(props) => props.theme.motion.indeterminateEasing} infinite;
  }

  @media (prefers-reduced-motion: reduce) {
    left: 30%;
  }
`;

export interface ProgressBarProps {
  // The completed fraction, 0..1.
  value: number;
  // Accessible name; required when the bar has no visible label.
  label?: string;
  // No percentage yet (item 59: the chunking phase): the bar carries no value and its
  // fill sweeps instead.
  indeterminate?: boolean;
  // Whether the percentage shows beside the track; a running row's status line already
  // carries it, so the row hides this copy (item 59).
  showValue?: boolean;
}

// The transcription progress: a token track with an accent fill, its percentage as small
// muted text to the right of the track (item 25).
export function ProgressBar({
  value,
  label,
  indeterminate = false,
  showValue = true,
}: ProgressBarProps) {
  const percent = Math.round(value * 100);
  return (
    <ProgressRow>
      <ProgressTrack
        role="progressbar"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={indeterminate ? undefined : percent}
      >
        {indeterminate ? <IndeterminateFill /> : <ProgressFill $value={value} />}
      </ProgressTrack>
      {indeterminate || !showValue ? null : <ProgressValue>{percent}%</ProgressValue>}
    </ProgressRow>
  );
}
