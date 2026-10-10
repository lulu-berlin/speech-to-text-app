import { styled } from 'styled-components';

// The parts the upload rows and the task rows share: a line with the filename on the left
// and the row's state on the right; below the small breakpoint the line stacks into a
// column, the filename above the state, left-aligned (item 25).

export const RowContent = styled.div`
  display: flex;
  justify-content: space-between;
  gap: ${(props) => props.theme.spacing.sm};

  @media (max-width: ${(props) => props.theme.breakpoints.sm}) {
    flex-direction: column;
    align-items: flex-start;
  }
`;

// The filename: it can shrink beside the row's state and breaks anywhere, so a long
// name never pushes the row wider than a phone (item 25).
export const RowName = styled.span`
  min-width: 0;
  overflow-wrap: anywhere;
  color: ${(props) => props.theme.colors.text};
`;

// The row's state line (phase, status, controls): it wraps rather than overflowing.
export const RowControls = styled.div`
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: ${(props) => props.theme.spacing.sm};
`;
