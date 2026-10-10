import { forwardRef } from 'react';
import type { ButtonHTMLAttributes } from 'react';
import type { LucideIcon } from 'lucide-react';
import styled from 'styled-components';
import { Icon } from './Icon';
import { interactiveStates } from './buttonStates';

const IconButtonBase = styled.button`
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: ${(props) => props.theme.spacing.sm};
  border: ${(props) => props.theme.borderWidths.thin} solid transparent;
  border-radius: ${(props) => props.theme.radii.button};
  font: inherit;
  cursor: pointer;
  background: ${(props) => props.theme.colors.surface};
  color: ${(props) => props.theme.colors.text};

  &:hover:not(:disabled) {
    background: ${(props) => props.theme.colors.surfaceRaised};
  }

  &:active:not(:disabled) {
    background: ${(props) => props.theme.colors.buttonSecondaryActive};
  }

  ${interactiveStates}
`;

export interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  icon: LucideIcon;
  // The accessible name of the control; the header's tooltip is only a hint.
  label: string;
}

// An icon-only control, square and token-styled. The same token states as the secondary
// Button (surface → surfaceRaised hover → buttonSecondaryActive active, muted when disabled).
export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  { icon: Glyph, label, disabled, ...rest },
  ref,
) {
  return (
    <IconButtonBase ref={ref} type="button" aria-label={label} disabled={disabled} {...rest}>
      <Icon icon={Glyph} />
    </IconButtonBase>
  );
});
