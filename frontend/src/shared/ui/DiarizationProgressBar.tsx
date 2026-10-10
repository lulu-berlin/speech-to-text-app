import styled from 'styled-components';
import type { DiarizationStage } from '../api/types';
import { ProgressFill, ProgressRow, ProgressTrack, ProgressValue } from './progressParts';

const STAGE_LABELS: Record<'EMBEDDING' | 'CLUSTERING', string> = {
  EMBEDDING: 'Embedding',
  CLUSTERING: 'Clustering',
};

// The midpoint of the two-stage track: embedding on the left, clustering on the right.
const ProgressDivider = styled.div`
  position: absolute;
  top: 0;
  bottom: 0;
  left: 50%;
  width: ${(props) => props.theme.borderWidths.thin};
  background: ${(props) => props.theme.colors.border};
`;

export interface DiarizationProgressBarProps {
  // The active stage; null outside the DIARIZING phase.
  stage: DiarizationStage;
  // Progress within the active stage, 0..1.
  progress: number;
}

// The two-stage diarization bar (item 11's contract): embedding fills the left half of the
// track, clustering the right half. The value puts the stage progress on that half. Next
// to the track the active stage label with its within-stage percentage (item 25; the
// percentage restarts per stage, so the aria value text keeps the merged view for the bar
// role while the visible text counts within the stage).
export function DiarizationProgressBar({ stage, progress }: DiarizationProgressBarProps) {
  const stageIndex = stage === 'CLUSTERING' ? 1 : 0;
  const value = (stageIndex + progress) / 2;
  const percent = Math.round(value * 100);
  const stagePercent = Math.round(progress * 100);
  return (
    <ProgressRow>
      <ProgressTrack
        role="progressbar"
        aria-label="Speaker diarization"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percent}
        aria-valuetext={stage === null ? undefined : `${STAGE_LABELS[stage]} ${percent}%`}
      >
        <ProgressFill $value={value} />
        <ProgressDivider />
      </ProgressTrack>
      {stage !== null ? (
        <ProgressValue>
          {STAGE_LABELS[stage]} {stagePercent}%
        </ProgressValue>
      ) : null}
    </ProgressRow>
  );
}
