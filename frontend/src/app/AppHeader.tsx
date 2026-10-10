import { Monitor, Moon, Sun } from 'lucide-react';
import { NavLink } from 'react-router';
import styled from 'styled-components';
import type { ThemeMode } from '../shared/theme/themeMode';
import { IconButton } from '../shared/ui/IconButton';
import { Tooltip } from '../shared/ui/Tooltip';
import { ConnectionContext } from './ConnectionContext';

// The product name, navigation between the views, the live connection indicator and the
// theme control. Item 17 adds the stenographer animation here.
const Header = styled.header`
  display: flex;
  align-items: baseline;
  gap: ${(props) => props.theme.spacing.lg};
  padding: ${(props) => props.theme.spacing.md} ${(props) => props.theme.spacing.lg};
  border-bottom: ${(props) => props.theme.borderWidths.thin} solid
    ${(props) => props.theme.colors.border};
`;

const Title = styled.h1`
  margin: 0;
  color: ${(props) => props.theme.colors.text};
  font-size: ${(props) => props.theme.fontSizes.title};
`;

const Nav = styled.nav`
  display: flex;
  gap: ${(props) => props.theme.spacing.sm};
`;

const NavItem = styled(NavLink)`
  color: ${(props) => props.theme.colors.accent};
  text-decoration: none;

  &.active {
    text-decoration: underline;
  }
`;

const Indicator = styled.span`
  margin-left: auto;
  color: ${(props) => props.theme.colors.text};
`;

// The mode icon and the control's accessible name (also the tooltip text).
const THEME_ICON = {
  auto: Monitor,
  light: Sun,
  dark: Moon,
} as const;

const THEME_LABEL = {
  auto: 'Theme: system',
  light: 'Theme: light',
  dark: 'Theme: dark',
} as const;

const CONNECTION_LABEL = {
  connecting: 'Connecting',
  live: 'Live',
  reconnecting: 'Reconnecting',
} as const;

export interface AppHeaderProps {
  themeMode: ThemeMode;
  onCycleThemeMode: () => void;
}

export function AppHeader({ themeMode, onCycleThemeMode }: AppHeaderProps) {
  const connection = ConnectionContext.useSelector((state) => state.value);
  return (
    <Header>
      <Title>Speech-to-Text</Title>
      <Nav>
        <NavItem to="/queue">Queue</NavItem>
        <NavItem to="/history">History</NavItem>
      </Nav>
      <Indicator>{CONNECTION_LABEL[connection]}</Indicator>
      <Tooltip label={THEME_LABEL[themeMode]}>
        <IconButton
          icon={THEME_ICON[themeMode]}
          label={THEME_LABEL[themeMode]}
          onClick={onCycleThemeMode}
        />
      </Tooltip>
    </Header>
  );
}
