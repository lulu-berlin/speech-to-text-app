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

// The row a bar's track sits in: the track grows, the value (a percentage or a stage
// label) hugs the right edge, in the small muted text style (item 25).
export const ProgressRow = styled.div`
  display: flex;
  align-items: center;
  gap: ${(props) => props.theme.spacing.sm};

  ${ProgressTrack} {
    flex-grow: 1;
  }
`;

export const ProgressValue = styled.span`
  color: ${(props) => props.theme.colors.textMuted};
  font-size: ${(props) => props.theme.fontSizes.small};
  white-space: nowrap;
`;
