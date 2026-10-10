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

export const RowName = styled.span`
  color: ${(props) => props.theme.colors.text};
`;
