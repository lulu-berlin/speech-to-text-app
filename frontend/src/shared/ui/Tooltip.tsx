import * as TooltipPrimitive from '@radix-ui/react-tooltip';
import type { ReactNode } from 'react';
import styled from 'styled-components';
import { floatingPanel } from './floatingPanel';

// The tooltip panel; exported so a composed trigger (e.g. the actions menu) can render the
// same styling with the tooltip and menu triggers on one element (item 59).
export const TooltipContent = styled(TooltipPrimitive.Content)`
  padding: ${(props) => props.theme.spacing.xs} ${(props) => props.theme.spacing.sm};
  ${floatingPanel}
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
          <TooltipContent>{label}</TooltipContent>
        </TooltipPrimitive.Portal>
      </TooltipPrimitive.Root>
    </TooltipPrimitive.Provider>
  );
}
