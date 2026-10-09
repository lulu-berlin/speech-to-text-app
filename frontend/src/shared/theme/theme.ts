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

export const theme: ThemeTokens = {
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

declare module 'styled-components' {
  export interface DefaultTheme extends ThemeTokens {}
}
