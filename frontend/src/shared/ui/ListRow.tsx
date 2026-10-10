import { forwardRef } from 'react';
import type { LiHTMLAttributes, ReactNode } from 'react';
import styled from 'styled-components';
import type { ThemeTokens } from '../theme/theme';
import { focusWithinRing } from './focusRing';

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
  ${focusWithinRing}
`;

export interface ListRowProps extends LiHTMLAttributes<HTMLLIElement> {
  variant?: ListRowVariant;
  children: ReactNode;
}

// The row the Queue and History lists sit in; forwards its ref so a parent can mark it
// (the queue rows pass it to dnd-kit as the sortable node).
export const ListRow = forwardRef<HTMLLIElement, ListRowProps>(function ListRow(
  { variant = 'row', children, ...rest },
  ref,
) {
  return (
    <ListRowBase ref={ref} $variant={variant} {...rest}>
      {children}
    </ListRowBase>
  );
});
