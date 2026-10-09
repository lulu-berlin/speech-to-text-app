import type { ReactNode } from 'react';
import styled from 'styled-components';

// A raised panel with a border, padding and a soft elevation shadow.

const CardBase = styled.div`
  background: ${(props) => props.theme.colors.surfaceRaised};
  color: ${(props) => props.theme.colors.text};
  border: ${(props) => props.theme.borderWidths.thin} solid ${(props) => props.theme.colors.border};
  border-radius: ${(props) => props.theme.radii.card};
  padding: ${(props) => props.theme.spacing.lg};
  box-shadow: ${(props) => props.theme.shadows.card};
`;

export interface CardProps {
  children: ReactNode;
}

export function Card({ children }: CardProps) {
  return <CardBase>{children}</CardBase>;
}
