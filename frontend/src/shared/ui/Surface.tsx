import type { ReactNode } from 'react';
import styled from 'styled-components';

// A group sitting directly on the page background.

const SurfaceBase = styled.div`
  background: ${(props) => props.theme.colors.surface};
  color: ${(props) => props.theme.colors.text};
  border-radius: ${(props) => props.theme.radii.card};
`;

export interface SurfaceProps {
  children: ReactNode;
}

export function Surface({ children }: SurfaceProps) {
  return <SurfaceBase>{children}</SurfaceBase>;
}
