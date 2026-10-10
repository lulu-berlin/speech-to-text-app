import type { ReactNode } from 'react';
import styled from 'styled-components';

const FieldBase = styled.div`
  display: flex;
  flex-direction: column;
  gap: ${(props) => props.theme.spacing.xs};
`;

const FieldLabel = styled.label`
  color: ${(props) => props.theme.colors.textMuted};
  font-size: ${(props) => props.theme.fontSizes.small};
`;

export interface FieldProps {
  label: string;
  htmlFor: string;
  children: ReactNode;
}

// A labelled form control: a small muted label above the control (the Queue's upload
// options, History's search and status filter).
export function Field({ label, htmlFor, children }: FieldProps) {
  return (
    <FieldBase>
      <FieldLabel htmlFor={htmlFor}>{label}</FieldLabel>
      {children}
    </FieldBase>
  );
}
