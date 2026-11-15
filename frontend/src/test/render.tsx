import { render } from '@testing-library/react';
import type { ReactElement, ReactNode } from 'react';
import { ThemeProvider } from 'styled-components';
import { LocaleProvider } from '../app/LocaleProvider';
import { lightTheme } from '../shared/theme/theme';

// Renders a component with the providers it needs. Components that use the theme or the
// message catalog must be rendered through this (or App, which brings its own providers).
export function ThemedProviders({ children }: { children: ReactNode }) {
  return (
    <ThemeProvider theme={lightTheme}>
      <LocaleProvider>{children}</LocaleProvider>
    </ThemeProvider>
  );
}

export function renderThemed(ui: ReactElement) {
  return render(<ThemedProviders>{ui}</ThemedProviders>);
}
