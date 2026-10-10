import * as Menu from '@radix-ui/react-dropdown-menu';
import type { ReactNode } from 'react';
import styled from 'styled-components';
import { floatingPanel } from './floatingPanel';
import { focusRing } from './focusRing';

export interface DropdownMenuItem {
  label: string;
  onSelect?: () => void;
  disabled?: boolean;
  danger?: boolean;
}

export interface DropdownMenuProps {
  trigger: ReactNode;
  items: DropdownMenuItem[];
  align?: 'start' | 'end';
}

const Content = styled(Menu.Content)`
  padding: ${(props) => props.theme.spacing.xs};
  ${floatingPanel}
`;

const Item = styled(Menu.Item)<{ $danger: boolean }>`
  padding: ${(props) => props.theme.spacing.sm} ${(props) => props.theme.spacing.lg};
  border-radius: ${(props) => props.theme.radii.button};
  color: ${(props) => (props.$danger ? props.theme.colors.danger : props.theme.colors.text)};
  cursor: pointer;
  user-select: none;
  outline: none;

  ${focusRing}

  &[data-highlighted] {
    background: ${(props) => props.theme.colors.surface};
  }

  &[data-disabled] {
    color: ${(props) => props.theme.colors.textMuted};
    cursor: default;
    background: transparent;
  }
`;

// The per-task actions menu (queue rows): a floating panel of items on Radix's menu, so the
// arrow keys, type-ahead and Enter selection come with the correct menuitem roles.
export function DropdownMenu({ trigger, items, align = 'start' }: DropdownMenuProps) {
  return (
    <Menu.Root>
      <Menu.Trigger asChild>{trigger}</Menu.Trigger>
      <Menu.Portal>
        <Content align={align}>
          {items.map((item) => (
            <Item
              key={item.label}
              $danger={item.danger === true}
              disabled={item.disabled}
              onSelect={item.onSelect}
            >
              {item.label}
            </Item>
          ))}
        </Content>
      </Menu.Portal>
    </Menu.Root>
  );
}
