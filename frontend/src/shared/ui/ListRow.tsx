import type { ReactNode } from 'react';
import styled from 'styled-components';
import type { ThemeTokens } from '../theme/theme';

export type ListRowVariant = 'row' | 'card';

// The two list looks: a plain row on the page background, or a raised card.
const rowVariants = (theme: ThemeTokens) => ({
  row: {
    background: 'transparent',
    padding: `${theme.spacing.sm} ${theme.spacing.lg}`,
  },
  card: {
    background: theme.colors.surfaceRaised,
    padding: `${theme.spacing.md} ${theme.spacing.lg}`,
  },
});

const ListRowBase = styled.li<{ $variant: ListRowVariant }>`
  padding: ${(props) => rowVariants(props.theme)[props.$variant].padding};
  background: ${(props) => rowVariants(props.theme)[props.$variant].background};
  border: ${(props) => props.theme.borderWidths.thin} solid
    ${(props) => (props.$variant === 'card' ? props.theme.colors.border : 'transparent')};
  border-radius: ${(props) => props.theme.radii.card};

  &:hover {
    background: ${(props) => props.theme.colors.surface};
  }

  // The row itself is not focusable; the ring appears when the control inside it is
  // focused by keyboard.
  &:focus-within {
    box-shadow: 0 0 0 ${(props) => props.theme.borderWidths.ring}
      ${(props) => props.theme.colors.focusRing};
  }
`;

export interface ListRowProps {
  variant?: ListRowVariant;
  children: ReactNode;
}

export function ListRow({ variant = 'row', children }: ListRowProps) {
  return <ListRowBase $variant={variant}>{children}</ListRowBase>;
}
