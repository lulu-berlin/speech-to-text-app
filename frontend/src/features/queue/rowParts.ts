import { styled } from 'styled-components';

// The parts the upload rows and the task rows share: a line with the filename on the left
// and the row's state on the right.

export const RowContent = styled.div`
  display: flex;
  justify-content: space-between;
  gap: ${(props) => props.theme.spacing.sm};
`;

export const RowName = styled.span`
  color: ${(props) => props.theme.colors.text};
`;
