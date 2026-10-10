import type { ReactNode } from 'react';
import styled from 'styled-components';

// The list the Queue and History rows sit in: a reset ul with a row gap.

const ListBase = styled.ul`
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: ${(props) => props.theme.spacing.sm};
`;

export interface ListProps {
  children: ReactNode;
}

export function List({ children }: ListProps) {
  return <ListBase>{children}</ListBase>;
}
