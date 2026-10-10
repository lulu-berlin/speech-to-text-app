import { ProgressFill, ProgressTrack } from './progressParts';

export interface ProgressBarProps {
  // The completed fraction, 0..1.
  value: number;
  // Accessible name; required when the bar has no visible label.
  label?: string;
}

// The transcription progress: a token track with an accent fill.
export function ProgressBar({ value, label }: ProgressBarProps) {
  const percent = Math.round(value * 100);
  return (
    <ProgressTrack
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={percent}
    >
      <ProgressFill $value={value} />
    </ProgressTrack>
  );
}
