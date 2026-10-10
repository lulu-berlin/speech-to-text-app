import * as TooltipPrimitive from '@radix-ui/react-tooltip';
import type { ReactNode } from 'react';
import styled from 'styled-components';

const Content = styled(TooltipPrimitive.Content)`
  padding: ${(props) => props.theme.spacing.xs} ${(props) => props.theme.spacing.sm};
  border: ${(props) => props.theme.borderWidths.thin} solid ${(props) => props.theme.colors.border};
  border-radius: ${(props) => props.theme.radii.card};
  background: ${(props) => props.theme.colors.surfaceRaised};
  color: ${(props) => props.theme.colors.text};
  box-shadow: ${(props) => props.theme.shadows.card};
`;

export interface TooltipProps {
  label: string;
  // How long hover must hold before the label shows (0 in tests).
  delayDuration?: number;
  children: ReactNode;
}

// A token-styled label on hover or keyboard focus, on Radix's tooltip so the trigger keeps
// its accessible name from its own label or aria-label.
export function Tooltip({ label, delayDuration = 700, children }: TooltipProps) {
  return (
    <TooltipPrimitive.Provider delayDuration={delayDuration}>
      <TooltipPrimitive.Root>
        <TooltipPrimitive.Trigger asChild>{children}</TooltipPrimitive.Trigger>
        <TooltipPrimitive.Portal>
          <Content>{label}</Content>
        </TooltipPrimitive.Portal>
      </TooltipPrimitive.Root>
    </TooltipPrimitive.Provider>
  );
}
