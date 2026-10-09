import { keyframes, styled } from 'styled-components';
import type { SpacingKey } from '../theme/theme';

const pulse = keyframes`
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.5;
  }
`;

const SkeletonBase = styled.div<{ $height: SpacingKey }>`
  height: ${(props) => props.theme.spacing[props.$height]};
  background: ${(props) => props.theme.colors.border};
  border-radius: ${(props) => props.theme.radii.button};
  animation: ${pulse} ${(props) => props.theme.motion.pulseDuration}
    ${(props) => props.theme.motion.pulseEasing} infinite;

  @media (prefers-reduced-motion: reduce) {
    animation: none;
  }
`;

export interface SkeletonProps {
  height?: SpacingKey;
}

// A placeholder line that pulses until the content arrives; decorative, hence aria-hidden.
// A block element needs no width declaration to span its container.
export function Skeleton({ height = 'md' }: SkeletonProps) {
  return <SkeletonBase $height={height} aria-hidden="true" />;
}
