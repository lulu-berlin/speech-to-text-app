import { Monitor, Moon, Sun } from 'lucide-react';
import { NavLink } from 'react-router';
import styled from 'styled-components';
import { StenographerAnimation } from '../shared/brand/StenographerAnimation';
import type { ThemeMode } from '../shared/theme/themeMode';
import { IconButton } from '../shared/ui/IconButton';
import { focusRing } from '../shared/ui/focusRing';
import { Tooltip } from '../shared/ui/Tooltip';
import { ConnectionContext } from './ConnectionContext';

// The brand mascot, product name, navigation between the views, the live connection
// indicator and the theme control. The mascot types while a task runs (item 17); it is
// the header's dominant element and fills the whole band — no top/bottom/left padding,
// so it touches the page edge all around; only the right side keeps a small margin so
// the last control does not sit on the page edge. The controls centre against it.
const Header = styled.header`
  display: flex;
  align-items: center;
  gap: ${(props) => props.theme.spacing.lg};
  padding: 0 ${(props) => props.theme.spacing.lg} 0 0;
  border-bottom: ${(props) => props.theme.borderWidths.thin} solid
    ${(props) => props.theme.colors.border};

  // On a phone the header wraps: the mascot and title on the first line, the controls
  // group on its own line below (item 25).
  @media (max-width: ${(props) => props.theme.breakpoints.sm}) {
    flex-wrap: wrap;
    row-gap: 0;
  }
`;

// The navigation, the connection indicator and the theme control: the rest of the first
// line on desktop, a line of its own on a phone (indented to the page padding there,
// since the mascot that touches the page edge is above it).
const HeaderControls = styled.div`
  display: flex;
  flex: 1;
  align-items: center;
  gap: ${(props) => props.theme.spacing.lg};

  @media (max-width: ${(props) => props.theme.breakpoints.sm}) {
    flex-basis: 100%;
    padding: 0 0 ${(props) => props.theme.spacing.sm} ${(props) => props.theme.spacing.lg};
  }
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

  ${focusRing}
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
  const running = ConnectionContext.useSelector((state) =>
    state.context.tasks.some((task) => task.status === 'RUNNING'),
  );
  return (
    <Header>
      <StenographerAnimation typing={running} />
      <Title>Speech-to-Text</Title>
      <HeaderControls>
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
      </HeaderControls>
    </Header>
  );
}
