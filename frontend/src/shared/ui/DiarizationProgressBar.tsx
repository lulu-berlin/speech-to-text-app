import styled from 'styled-components';
import type { DiarizationStage } from '../api/types';
import { useMessages } from '../i18n/useMessages';
import { ProgressFill, ProgressRow, ProgressTrack, ProgressValue } from './progressParts';

const STAGE_LABEL_ID: Record<'EMBEDDING' | 'CLUSTERING', string> = {
  EMBEDDING: 'transcript.diarization.embedding',
  CLUSTERING: 'transcript.diarization.clustering',
};

// The midpoint of the two-stage track: the first stage at the inline start, the second at
// the inline end.
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

// The two-stage diarization bar (item 11's contract): the first stage fills the track's
// first half — the inline start — and the second the other. The value puts the stage
// progress on that half. Next to the track the active stage label with its within-stage
// percentage (item 25); the aria value text carries the same within-stage wording, since
// the merged percentage restarts per stage and would mislead.
export function DiarizationProgressBar({ stage, progress }: DiarizationProgressBarProps) {
  const t = useMessages();
  const stageIndex = stage === 'CLUSTERING' ? 1 : 0;
  const value = (stageIndex + progress) / 2;
  const percent = Math.round(value * 100);
  const stageLabel = stage === null ? null : t(STAGE_LABEL_ID[stage]);
  const stagePercent = Math.round(progress * 100);
  return (
    <ProgressRow>
      <ProgressTrack
        role="progressbar"
        aria-label={t('transcript.diarization.label')}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percent}
        aria-valuetext={
          stageLabel === null
            ? undefined
            : t('transcript.diarization.value', { stage: stageLabel, percent: stagePercent })
        }
      >
        <ProgressFill $value={value} />
        <ProgressDivider />
      </ProgressTrack>
      {stageLabel !== null ? (
        <ProgressValue>
          {t('transcript.diarization.value', { stage: stageLabel, percent: stagePercent })}
        </ProgressValue>
      ) : null}
    </ProgressRow>
  );
}
