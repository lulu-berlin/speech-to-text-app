import type { ReactNode } from 'react';
import styled from 'styled-components';

// The two token surfaces: a flat group container and the raised panel of a card.

const SurfaceBase = styled.div`
  background: ${(props) => props.theme.colors.surface};
  color: ${(props) => props.theme.colors.text};
  border-radius: ${(props) => props.theme.radii.card};
`;

const CardBase = styled.div`
  background: ${(props) => props.theme.colors.surfaceRaised};
  color: ${(props) => props.theme.colors.text};
  border: ${(props) => props.theme.borderWidths.thin} solid ${(props) => props.theme.colors.border};
  border-radius: ${(props) => props.theme.radii.card};
  padding: ${(props) => props.theme.spacing.lg};
  box-shadow: ${(props) => props.theme.shadows.card};
`;

export interface SurfaceProps {
  children: ReactNode;
}

export interface CardProps {
  children: ReactNode;
}

// A group sitting directly on the page background.
export function Surface({ children }: SurfaceProps) {
  return <SurfaceBase>{children}</SurfaceBase>;
}

// A raised panel with a border, padding and a soft elevation shadow.
export function Card({ children }: CardProps) {
  return <CardBase>{children}</CardBase>;
}
