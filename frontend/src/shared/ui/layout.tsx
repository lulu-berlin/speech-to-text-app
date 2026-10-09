import type { ReactNode } from 'react';
import styled from 'styled-components';
import type { ThemeTokens } from '../theme/theme';

// Layout primitives: views compose their spacing from these instead of writing padding by
// hand (frontend conventions). Gaps are named spacing tokens, so a caller cannot invent a
// value; the transient prop keeps them off the DOM.
type SpacingKey = keyof ThemeTokens['spacing'];

const PageBase = styled.div`
  max-width: ${(props) => props.theme.sizes.pageMaxWidth};
  margin: 0 auto;
  padding: ${(props) => props.theme.spacing.lg};
`;

const StackBase = styled.div<{ $gap: SpacingKey }>`
  display: flex;
  flex-direction: column;
  gap: ${(props) => props.theme.spacing[props.$gap]};
`;

const RowBase = styled.div<{ $gap: SpacingKey }>`
  display: flex;
  align-items: center;
  gap: ${(props) => props.theme.spacing[props.$gap]};
`;

export interface PageProps {
  children: ReactNode;
}

export interface StackProps {
  gap?: SpacingKey;
  children: ReactNode;
}

export interface RowProps {
  gap?: SpacingKey;
  children: ReactNode;
}

// The page: a centred column at the reading width.
export function Page({ children }: PageProps) {
  return <PageBase>{children}</PageBase>;
}

// A vertical rhythm.
export function Stack({ gap = 'md', children }: StackProps) {
  return <StackBase $gap={gap}>{children}</StackBase>;
}

// A horizontal group.
export function Row({ gap = 'sm', children }: RowProps) {
  return <RowBase $gap={gap}>{children}</RowBase>;
}
