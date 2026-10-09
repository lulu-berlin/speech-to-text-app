import { useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import styled from 'styled-components';

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  confirmLabel: string;
  onConfirm: () => void;
  onCancel: () => void;
  children: ReactNode;
}

const Dialog = styled.dialog`
  padding: ${(props) => props.theme.spacing.unit * 3}px;
  border: 1px solid ${(props) => props.theme.colors.border};
  border-radius: ${(props) => props.theme.radii.dialog};
  background: ${(props) => props.theme.colors.background};
  color: ${(props) => props.theme.colors.text};
`;

const Title = styled.h2`
  margin: 0 0 ${(props) => props.theme.spacing.unit * 2}px;
  font-size: ${(props) => props.theme.fontSizes.title};
`;

const Body = styled.p`
  margin: 0 0 ${(props) => props.theme.spacing.unit * 3}px;
`;

const Actions = styled.div`
  display: flex;
  justify-content: flex-end;
  gap: ${(props) => props.theme.spacing.unit}px;
`;

const Button = styled.button`
  padding: ${(props) => props.theme.spacing.unit}px ${(props) => props.theme.spacing.unit * 2}px;
  border: 1px solid ${(props) => props.theme.colors.border};
  border-radius: ${(props) => props.theme.radii.button};
  background: ${(props) => props.theme.colors.background};
  color: ${(props) => props.theme.colors.text};
  cursor: pointer;
`;

const ConfirmButton = styled(Button)`
  border-color: ${(props) => props.theme.colors.accent};
  color: ${(props) => props.theme.colors.accent};
`;

// A native <dialog>: confirm runs the action, cancel and Escape do not. Escape closes the
// dialog with an empty returnValue, so only "confirm" runs the action.
export function ConfirmDialog({
  open,
  title,
  confirmLabel,
  onConfirm,
  onCancel,
  children,
}: ConfirmDialogProps) {
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (dialog === null) {
      return;
    }
    if (open && !dialog.open) {
      dialog.showModal();
    }
    if (!open && dialog.open) {
      dialog.close();
    }
  }, [open]);

  return (
    <Dialog
      ref={ref}
      onClose={(event) =>
        event.currentTarget.returnValue === 'confirm' ? onConfirm() : onCancel()
      }
    >
      <Title>{title}</Title>
      <Body>{children}</Body>
      <Actions>
        <Button type="button" onClick={() => ref.current?.close('cancel')}>
          Cancel
        </Button>
        <ConfirmButton type="button" onClick={() => ref.current?.close('confirm')}>
          {confirmLabel}
        </ConfirmButton>
      </Actions>
    </Dialog>
  );
}
