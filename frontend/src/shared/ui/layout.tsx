import type { ReactNode } from 'react';
import styled from 'styled-components';
import type { SpacingKey } from '../theme/theme';

// Layout primitives: views compose their spacing from these instead of writing padding by
// hand (frontend conventions). Gaps are named spacing tokens, so a caller cannot invent a
// value; the transient prop keeps them off the DOM.

// The page widths: the general page, and the transcript's ~70-character reading column.
export type PageMeasure = 'page' | 'reading';

const PAGE_WIDTH_TOKEN = { page: 'pageMaxWidth', reading: 'readingMeasure' } as const;

const PageBase = styled.div<{ $measure: PageMeasure }>`
  max-width: ${(props) => props.theme.sizes[PAGE_WIDTH_TOKEN[props.$measure]]};
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
  measure?: PageMeasure;
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

// The page: a centred column at the page width, or at the reading measure.
export function Page({ measure = 'page', children }: PageProps) {
  return <PageBase $measure={measure}>{children}</PageBase>;
}

// A vertical rhythm.
export function Stack({ gap = 'md', children }: StackProps) {
  return <StackBase $gap={gap}>{children}</StackBase>;
}

// A horizontal group.
export function Row({ gap = 'sm', children }: RowProps) {
  return <RowBase $gap={gap}>{children}</RowBase>;
}
