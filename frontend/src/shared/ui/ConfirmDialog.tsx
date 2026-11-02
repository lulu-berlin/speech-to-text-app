import * as Dialog from '@radix-ui/react-dialog';
import type { ReactNode } from 'react';
import styled from 'styled-components';
import { Button } from './Button';

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  confirmLabel: string;
  onConfirm: () => void;
  onCancel: () => void;
  // The confirm action itself writes (item 87): while the connection is not live it is
  // disabled and `confirmTitle` says why; Cancel still closes the dialog.
  confirmDisabled?: boolean;
  confirmTitle?: string;
  children: ReactNode;
}

const Overlay = styled(Dialog.Overlay)`
  position: fixed;
  inset: 0;
  background: ${(props) => props.theme.colors.overlay};
`;

const Panel = styled(Dialog.Content)`
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  padding: ${(props) => props.theme.spacing.lg};
  border: ${(props) => props.theme.borderWidths.thin} solid ${(props) => props.theme.colors.border};
  border-radius: ${(props) => props.theme.radii.dialog};
  background: ${(props) => props.theme.colors.background};
  color: ${(props) => props.theme.colors.text};
`;

const Title = styled(Dialog.Title)`
  margin: 0 0 ${(props) => props.theme.spacing.md};
  font-size: ${(props) => props.theme.fontSizes.title};
`;

const Body = styled(Dialog.Description)`
  margin: 0 0 ${(props) => props.theme.spacing.lg};
`;

const Actions = styled.div`
  display: flex;
  justify-content: flex-end;
  gap: ${(props) => props.theme.spacing.sm};
`;

// The confirmation dialog, on Radix's dialog so focus is trapped and restored (ADR 0033).
// Only the confirm button runs the action; Escape and Cancel call onCancel, and the parent
// closes the dialog through the controlled `open` prop (a confirm close is a plain button
// click, so it never routes through onOpenChange).
export function ConfirmDialog({
  open,
  title,
  confirmLabel,
  onConfirm,
  onCancel,
  confirmDisabled = false,
  confirmTitle,
  children,
}: ConfirmDialogProps) {
  return (
    <Dialog.Root
      open={open}
      onOpenChange={(nextOpen) => {
        if (!nextOpen) {
          onCancel();
        }
      }}
    >
      <Dialog.Portal>
        <Overlay />
        <Panel>
          <Title>{title}</Title>
          <Body>{children}</Body>
          <Actions>
            <Button variant="secondary" onClick={onCancel}>
              Cancel
            </Button>
            <Button disabled={confirmDisabled} title={confirmTitle} onClick={onConfirm}>
              {confirmLabel}
            </Button>
          </Actions>
        </Panel>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
