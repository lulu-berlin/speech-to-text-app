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
    // One tint per speaker index (item 12's 0-based speakerId), taken modulo the size; the
    // speaker's name is shown in it (item 15). Chosen for contrast, AA in both themes.
    speakerTints: readonly string[];
  };
  // Lengths are rem so they follow the reader's font size (no raw px outside the tokens;
  // even here there is none — see src/conventions.test.ts).
  spacing: {
    xs: string;
    sm: string;
    md: string;
    lg: string;
    xl: string;
    xxl: string;
  };
  borderWidths: {
    thin: string;
  };
  sizes: {
    viewportMinHeight: string;
  };
  radii: {
    dialog: string;
    button: string;
  };
  fontSizes: {
    title: string;
  };
}

// Everything except colors is the same in both sets; only the color roles differ.
const baseTokens = {
  spacing: {
    xs: '0.25rem',
    sm: '0.5rem',
    md: '1rem',
    lg: '1.5rem',
    xl: '2rem',
    xxl: '3rem',
  },
  borderWidths: {
    thin: '0.0625rem',
  },
  sizes: {
    viewportMinHeight: '100vh',
  },
  radii: {
    dialog: '0.5rem',
    button: '0.25rem',
  },
  fontSizes: {
    title: '1.5rem',
  },
};

export const lightTheme: ThemeTokens = {
  ...baseTokens,
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
    speakerTints: [
      '#0d559c',
      '#df1212',
      '#0b820b',
      '#b912e3',
      '#6e4f09',
      '#952574',
      '#0a7d7d',
      '#6858f1',
      '#d51186',
      '#18603c',
      '#7411d6',
      '#7d710a',
      '#a51d0d',
      '#0f75b8',
      '#a85c2a',
      '#2135ed',
      '#085d67',
      '#4b5b07',
      '#9b48d1',
      '#cc3562',
    ],
  },
};

export const darkTheme: ThemeTokens = {
  ...baseTokens,
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
    speakerTints: [
      '#79b6f4',
      '#f25c5c',
      '#0da60d',
      '#d359f2',
      '#e6a613',
      '#e699cf',
      '#0d9f9f',
      '#8b7ff5',
      '#f14eaf',
      '#32ca7e',
      '#cb9ff7',
      '#9e900d',
      '#f6998e',
      '#1394ea',
      '#cf793f',
      '#a3acf8',
      '#11c3d7',
      '#9bbf0f',
      '#b374dc',
      '#da6b8c',
    ],
  },
};

// The token set for the OS preference.
export function themeFor(prefersDark: boolean): ThemeTokens {
  return prefersDark ? darkTheme : lightTheme;
}

// A speaker's tint: the palette entry for its 0-based speaker index (item 12's speakerId),
// wrapping when a task has more speakers than the palette has entries.
export function speakerTint(tints: readonly string[], index: number): string {
  const tint = tints[index % tints.length];
  if (tint === undefined) {
    throw new Error('The speaker palette is empty');
  }
  return tint;
}

declare module 'styled-components' {
  export interface DefaultTheme extends ThemeTokens {}
}
