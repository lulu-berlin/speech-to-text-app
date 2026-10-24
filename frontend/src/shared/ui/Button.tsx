import { forwardRef } from 'react';
import type { ButtonHTMLAttributes, ReactNode } from 'react';
import styled from 'styled-components';
import type { ThemeTokens } from '../theme/theme';
import { interactiveStates } from './buttonStates';
import { Spinner } from './Spinner';

export type ButtonVariant = 'primary' | 'secondary' | 'danger';
// `md` is the app's action button; `sm` fits inside a line of text (item 52's editor).
export type ButtonSize = 'md' | 'sm';

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

const ButtonBase = styled.button<{ $variant: ButtonVariant; $size: ButtonSize }>`
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: ${(props) => props.theme.spacing.sm};
  padding: ${(props) =>
    props.$size === 'sm'
      ? `${props.theme.spacing.xs} ${props.theme.spacing.sm}`
      : `${props.theme.spacing.sm} ${props.theme.spacing.md}`};
  border: ${(props) => props.theme.borderWidths.thin} solid transparent;
  border-radius: ${(props) => props.theme.radii.button};
  // Typography inherited from around the button, spelled as longhands: a jsdom CSSOM rule
  // ignores a font-size that follows the font shorthand in one declaration block.
  font-family: inherit;
  font-weight: inherit;
  font-style: inherit;
  line-height: inherit;
  font-size: ${(props) => (props.$size === 'sm' ? props.theme.fontSizes.small : 'inherit')};
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
  size?: ButtonSize;
  loading?: boolean;
  children: ReactNode;
}

// A token-styled action button. `loading` disables the control and flags it busy; the
// spinner glyph itself arrives with the Spinner primitive.
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'primary', size = 'md', loading = false, disabled, children, ...rest },
  ref,
) {
  return (
    <ButtonBase
      ref={ref}
      type="button"
      $variant={variant}
      $size={size}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading ? <Spinner size="sm" /> : null}
      {children}
    </ButtonBase>
  );
});
