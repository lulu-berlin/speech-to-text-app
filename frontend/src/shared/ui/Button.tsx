import type { ButtonHTMLAttributes, ReactNode } from 'react';
import styled from 'styled-components';
import type { ThemeTokens } from '../theme/theme';
import { interactiveStates } from './buttonStates';
import { Spinner } from './Spinner';

export type ButtonVariant = 'primary' | 'secondary' | 'danger';

// The colours of each variant and its states, all from tokens.
const variantColors = (theme: ThemeTokens) => ({
  background: {
    primary: theme.colors.accent,
    secondary: theme.colors.surface,
    danger: theme.colors.danger,
  },
  text: {
    primary: theme.colors.accentText,
    secondary: theme.colors.text,
    danger: theme.colors.accentText,
  },
  hoverBackground: {
    primary: theme.colors.buttonPrimaryHover,
    secondary: theme.colors.surfaceRaised,
    danger: theme.colors.buttonDangerHover,
  },
  activeBackground: {
    primary: theme.colors.buttonPrimaryActive,
    secondary: theme.colors.buttonSecondaryActive,
    danger: theme.colors.buttonDangerActive,
  },
});

const ButtonBase = styled.button<{ $variant: ButtonVariant }>`
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: ${(props) => props.theme.spacing.sm};
  padding: ${(props) => props.theme.spacing.sm} ${(props) => props.theme.spacing.md};
  border: ${(props) => props.theme.borderWidths.thin} solid transparent;
  border-radius: ${(props) => props.theme.radii.button};
  font: inherit;
  cursor: pointer;
  background: ${(props) => variantColors(props.theme).background[props.$variant]};
  color: ${(props) => variantColors(props.theme).text[props.$variant]};
  border-color: ${(props) => (props.$variant === 'secondary' ? props.theme.colors.border : 'transparent')};

  &:hover:not(:disabled) {
    background: ${(props) => variantColors(props.theme).hoverBackground[props.$variant]};
  }

  &:active:not(:disabled) {
    background: ${(props) => variantColors(props.theme).activeBackground[props.$variant]};
  }

  ${interactiveStates}

  &:disabled {
    border-color: ${(props) => props.theme.colors.border};
  }
`;

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  loading?: boolean;
  children: ReactNode;
}

// A token-styled action button. `loading` disables the control and flags it busy; the
// spinner glyph itself arrives with the Spinner primitive.
export function Button({
  variant = 'primary',
  loading = false,
  disabled,
  children,
  ...rest
}: ButtonProps) {
  return (
    <ButtonBase
      type="button"
      $variant={variant}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading ? <Spinner size="sm" /> : null}
      {children}
    </ButtonBase>
  );
}
