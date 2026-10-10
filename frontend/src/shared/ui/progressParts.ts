import styled from 'styled-components';

// The shared track and fill of the progress bars. The fill's width is a styled-components
// interpolation of the value prop, so no inline style or literal reaches the component.

export const ProgressTrack = styled.div`
  position: relative;
  height: ${(props) => props.theme.spacing.sm};
  border: ${(props) => props.theme.borderWidths.thin} solid ${(props) => props.theme.colors.border};
  border-radius: ${(props) => props.theme.radii.pill};
  background: ${(props) => props.theme.colors.surface};
  overflow: hidden;
`;

export const ProgressFill = styled.div<{ $value: number }>`
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: ${(props) => props.$value * 100}%;
  background: ${(props) => props.theme.colors.accent};
`;
