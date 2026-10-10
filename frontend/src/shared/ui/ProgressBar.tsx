import { ProgressFill, ProgressRow, ProgressTrack, ProgressValue } from './progressParts';

export interface ProgressBarProps {
  // The completed fraction, 0..1.
  value: number;
  // Accessible name; required when the bar has no visible label.
  label?: string;
}

// The transcription progress: a token track with an accent fill, its percentage as small
// muted text to the right of the track (item 25).
export function ProgressBar({ value, label }: ProgressBarProps) {
  const percent = Math.round(value * 100);
  return (
    <ProgressRow>
      <ProgressTrack
        role="progressbar"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percent}
      >
        <ProgressFill $value={value} />
      </ProgressTrack>
      <ProgressValue>{percent}%</ProgressValue>
    </ProgressRow>
  );
}
