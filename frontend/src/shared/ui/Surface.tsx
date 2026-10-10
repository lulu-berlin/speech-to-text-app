import type { HTMLAttributes, ReactNode } from 'react';
import styled from 'styled-components';
import type { SpacingKey } from '../theme/theme';

// A group sitting directly on the page background; an optional padding token (none by
// default) and the usual element attributes (a role and a label for a grouped control,
// as the transcript's player has).

const SurfaceBase = styled.div<{ $padding?: SpacingKey }>`
  background: ${(props) => props.theme.colors.surface};
  color: ${(props) => props.theme.colors.text};
  border-radius: ${(props) => props.theme.radii.card};
  ${(props) => (props.$padding !== undefined ? `padding: ${props.theme.spacing[props.$padding]};` : '')}
`;

export interface SurfaceProps extends HTMLAttributes<HTMLDivElement> {
  padding?: SpacingKey;
  children: ReactNode;
}

export function Surface({ padding, children, ...rest }: SurfaceProps) {
  return (
    <SurfaceBase $padding={padding} {...rest}>
      {children}
    </SurfaceBase>
  );
}
