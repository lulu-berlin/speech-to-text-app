import { keyframes, styled } from 'styled-components';
import type { IconSizeKey } from './Icon';

const spin = keyframes`
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
`;

const SpinnerBase = styled.span<{ $size: IconSizeKey }>`
  display: inline-block;
  width: ${(props) => props.theme.iconSizes[props.$size]};
  height: ${(props) => props.theme.iconSizes[props.$size]};
  border: ${(props) => props.theme.borderWidths.thin} solid ${(props) => props.theme.colors.border};
  border-top-color: ${(props) => props.theme.colors.accent};
  border-radius: 50%;
  animation: ${spin} ${(props) => props.theme.motion.spinDuration}
    ${(props) => props.theme.motion.spinEasing} infinite;

  @media (prefers-reduced-motion: reduce) {
    animation: none;
  }
`;

export interface SpinnerProps {
  size?: IconSizeKey;
}

// A rotating ring; decorative, so it is hidden from assistive technology.
export function Spinner({ size = 'md' }: SpinnerProps) {
  return <SpinnerBase $size={size} aria-hidden="true" />;
}
