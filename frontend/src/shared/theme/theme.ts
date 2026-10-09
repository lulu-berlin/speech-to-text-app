import 'styled-components';

// The five status groups (ADR 0033): pending = QUEUED, PAUSED; active = RUNNING;
// done = COMPLETED; problem = FAILED; gone = CANCELED.
export type StatusGroup = 'pending' | 'active' | 'done' | 'problem' | 'gone';

// Colors are plain #rrggbb so their contrast can be measured (AA in both sets).
export interface ThemeTokens {
  colors: {
    background: string;
    surface: string;
    surfaceRaised: string;
    text: string;
    textMuted: string;
    border: string;
    accent: string;
    accentText: string;
    focusRing: string;
    danger: string;
    status: Record<StatusGroup, { background: string; text: string }>;
  };
  spacing: {
    unit: number;
  };
  radii: {
    dialog: string;
    button: string;
  };
  fontSizes: {
    title: string;
  };
}

export const lightTheme: ThemeTokens = {
  colors: {
    background: '#ffffff',
    surface: '#f6f7f9',
    surfaceRaised: '#ffffff',
    text: '#1c1c1c',
    textMuted: '#5a616c',
    border: '#d7d7d7',
    accent: '#0057a8',
    accentText: '#ffffff',
    focusRing: '#0057a8',
    danger: '#b42318',
    status: {
      pending: { background: '#eceef1', text: '#3d434c' },
      active: { background: '#e3eefa', text: '#0057a8' },
      done: { background: '#e3f4e8', text: '#1d6436' },
      problem: { background: '#fcebe9', text: '#b42318' },
      gone: { background: '#f3f3f3', text: '#5a616c' },
    },
  },
  spacing: {
    unit: 8,
  },
  radii: {
    dialog: '8px',
    button: '4px',
  },
  fontSizes: {
    title: '1.5rem',
  },
};

export const darkTheme: ThemeTokens = {
  colors: {
    background: '#14161a',
    surface: '#1b1e23',
    surfaceRaised: '#23272e',
    text: '#e8e8e8',
    textMuted: '#a4acb8',
    border: '#3a3f47',
    accent: '#7fb2e5',
    accentText: '#14161a',
    focusRing: '#7fb2e5',
    danger: '#f28b82',
    status: {
      pending: { background: '#2a2e35', text: '#cdd2da' },
      active: { background: '#1a2a3c', text: '#7fb2e5' },
      done: { background: '#18291f', text: '#7fcf9c' },
      problem: { background: '#3a1c1c', text: '#f28b82' },
      gone: { background: '#22252a', text: '#9aa2ad' },
    },
  },
  spacing: {
    unit: 8,
  },
  radii: {
    dialog: '8px',
    button: '4px',
  },
  fontSizes: {
    title: '1.5rem',
  },
};

// The token set for the OS preference.
export function themeFor(prefersDark: boolean): ThemeTokens {
  return prefersDark ? darkTheme : lightTheme;
}

declare module 'styled-components' {
  export interface DefaultTheme extends ThemeTokens {}
}
