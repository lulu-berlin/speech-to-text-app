import type { LucideIcon } from 'lucide-react';
import styled from 'styled-components';
import type { ThemeTokens } from '../theme/theme';

// Token keys, so a caller cannot invent a size or a colour role.
export type IconSizeKey = keyof ThemeTokens['iconSizes'];
export type IconTone = 'inherit' | 'text' | 'textMuted' | 'accent' | 'danger';

const IconBase = styled.span<{ $size: IconSizeKey; $tone: IconTone }>`
  display: inline-flex;
  color: ${(props) => (props.$tone === 'inherit' ? 'inherit' : props.theme.colors[props.$tone])};

  > svg {
    width: ${(props) => props.theme.iconSizes[props.$size]};
    height: ${(props) => props.theme.iconSizes[props.$size]};
  }
`;

export interface IconProps {
  icon: LucideIcon;
  size?: IconSizeKey;
  tone?: IconTone;
  label?: string;
}

// Wraps a lucide-react icon and takes its size and colour from the tokens. The icon is
// decorative unless it is labelled, in which case it is exposed by role and name.
export function Icon({ icon: Glyph, size = 'md', tone = 'inherit', label }: IconProps) {
  return (
    <IconBase
      $size={size}
      $tone={tone}
      aria-hidden={label === undefined ? true : undefined}
      role={label === undefined ? undefined : 'img'}
      aria-label={label}
    >
      <Glyph />
    </IconBase>
  );
}
