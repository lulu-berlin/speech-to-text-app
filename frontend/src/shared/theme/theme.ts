import 'styled-components';

export interface ThemeTokens {
  colors: {
    background: string;
    text: string;
    accent: string;
    border: string;
  };
  spacing: {
    unit: number;
  };
  fontSizes: {
    title: string;
  };
}

export const lightTheme: ThemeTokens = {
  colors: {
    background: '#ffffff',
    text: '#1c1c1c',
    accent: '#0057a8',
    border: '#d7d7d7',
  },
  spacing: {
    unit: 8,
  },
  fontSizes: {
    title: '1.5rem',
  },
};

export const darkTheme: ThemeTokens = {
  colors: {
    background: '#14161a',
    text: '#e8e8e8',
    accent: '#7fb2e5',
    border: '#3a3f47',
  },
  spacing: {
    unit: 8,
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
