import { Monitor, Moon, Sun } from 'lucide-react';
import { useEffect } from 'react';
import { useMachine } from '@xstate/react';
import { Link, NavLink } from 'react-router';
import styled from 'styled-components';
import { StenographerAnimation } from '../shared/brand/StenographerAnimation';
import type { ThemeMode } from '../shared/theme/themeMode';
import { IconButton } from '../shared/ui/IconButton';
import { focusRing } from '../shared/ui/focusRing';
import { Tooltip } from '../shared/ui/Tooltip';
import { ConnectionContext } from './ConnectionContext';
import { headerScrollMachine } from './headerScrollMachine';

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
  // The header stays at the top of the viewport while the content scrolls under it
  // (item 31): sticky, raised above the page, on the page's token background so what
  // passes beneath stays hidden.
  position: sticky;
  top: 0;
  z-index: 1;
  background: ${(props) => props.theme.colors.background};
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

// The mascot's home link (item 31): clicking the mascot goes to / (which redirects to
// the queue). Block, so the artwork fills it edge to edge like before; the link carries
// the accessible name — the mascot itself stays decorative (aria-hidden).
const HomeLink = styled(Link)`
  display: block;
  ${focusRing}
`;

const Nav = styled.nav`
  display: flex;
  gap: ${(props) => props.theme.spacing.sm};
`;

const NavItem = styled(NavLink)`
  padding: ${(props) => props.theme.spacing.xs} ${(props) => props.theme.spacing.sm};
  border-radius: ${(props) => props.theme.radii.pill};
  color: ${(props) => props.theme.colors.accent};
  text-decoration: none;

  // The background pill (item 31): a soft surface pill behind a link on hover, filled
  // for the active link. The transition is gated behind no-preference, so reduced
  // motion switches states instantly; the keyboard focus ring stays as everywhere.
  @media (prefers-reduced-motion: no-preference) {
    transition-property: background-color, color;
    transition-duration: ${(props) => props.theme.motion.navDuration};
    transition-timing-function: ${(props) => props.theme.motion.navEasing};
  }

  &:hover {
    background: ${(props) => props.theme.colors.surface};
  }

  &.active {
    background: ${(props) => props.theme.colors.accent};
    color: ${(props) => props.theme.colors.accentText};
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
  // The mascot is full-size at the top of the page and compact once the page scrolls
  // (item 31); the window scroll drives the machine, one SCROLLED/AT_TOP per scroll.
  const [scroll, sendScroll] = useMachine(headerScrollMachine);
  const compact = scroll.matches('compact');
  useEffect(() => {
    const onScroll = () => sendScroll({ type: window.scrollY > 0 ? 'SCROLLED' : 'AT_TOP' });
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, [sendScroll]);
  return (
    <Header>
      <HomeLink to="/" aria-label="Speech-to-Text home">
        <StenographerAnimation typing={running} size={compact ? 'compact' : 'header'} />
      </HomeLink>
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
