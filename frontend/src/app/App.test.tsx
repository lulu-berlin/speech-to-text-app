import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { asRgb, px } from '../test/dom';
import { darkTheme, lightTheme } from '../shared/theme/theme';
import { App } from './App';

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

describe('the app root', () => {
  it('renders the page title', () => {
    renderAt('/queue');
    expect(screen.getByRole('heading', { name: 'Speech-to-Text' })).toBeInTheDocument();
  });

  it('redirects / to /queue, shows the history and transcript views, and a not-found view otherwise', () => {
    const queue = renderAt('/');
    expect(screen.getByRole('heading', { name: 'Queue' })).toBeInTheDocument();
    queue.unmount();

    const history = renderAt('/history');
    expect(screen.getByRole('heading', { name: 'History' })).toBeInTheDocument();
    history.unmount();

    const transcript = renderAt('/history/7');
    expect(screen.getByRole('heading', { name: 'Transcript' })).toBeInTheDocument();
    expect(screen.getByText(/task 7/i)).toBeInTheDocument();
    transcript.unmount();

    renderAt('/no-such-page');
    expect(screen.getByRole('heading', { name: 'Not found' })).toBeInTheDocument();
  });

  it('switches between Queue and History from the header and shows whether the connection is live', async () => {
    renderAt('/queue');

    const historyLink = screen.getByRole('link', { name: 'History' });
    expect(screen.getByRole('link', { name: 'Queue' })).toBeInTheDocument();
    // The mocked stream sends the snapshot, so the indicator turns live.
    expect(await screen.findByText('Live')).toBeInTheDocument();

    fireEvent.click(historyLink);
    expect(screen.getByRole('heading', { name: 'History' })).toBeInTheDocument();
  });

  it('renders the components with the theme applied, following the OS preference', () => {
    const renderWith = (prefersDark: boolean) => {
      vi.stubGlobal(
        'matchMedia',
        vi.fn(() => ({
          matches: prefersDark,
          media: '(prefers-color-scheme: dark)',
          addEventListener: vi.fn(),
          removeEventListener: vi.fn(),
        })),
      );
      return renderAt('/queue');
    };

    const light = renderWith(false);
    const lightSurface = screen.getByRole('main');
    expect(getComputedStyle(lightSurface).backgroundColor).toBe(
      asRgb(lightTheme.colors.background),
    );
    expect(getComputedStyle(lightSurface).color).toBe(asRgb(lightTheme.colors.text));
    light.unmount();

    const dark = renderWith(true);
    const darkSurface = screen.getByRole('main');
    expect(getComputedStyle(darkSurface).backgroundColor).toBe(asRgb(darkTheme.colors.background));
    expect(getComputedStyle(darkSurface).color).toBe(asRgb(darkTheme.colors.text));
    dark.unmount();

    vi.unstubAllGlobals();
  });

  it('renders its text in IBM Plex Sans with the system stack as fallback', () => {
    renderAt('/queue');

    // jsdom does not resolve what a global style sheet puts on `body` (item 13 hit the same
    // wall), so the assertion reads the app root element, where the font also lands.
    const shell = screen.getByRole('main').parentElement as HTMLElement;
    expect(getComputedStyle(shell).fontFamily).toBe(lightTheme.fontFamilies.sans);

    // The self-hosted font comes first; the system stack is the fallback.
    expect(lightTheme.fontFamilies.sans).toMatch(/^"IBM Plex Sans", system-ui, /);
    expect(lightTheme.fontFamilies.sans.endsWith('sans-serif')).toBe(true);
  });

  it('sets the body text from a type scale that ascends and meets WCAG line spacing', () => {
    const { fontSizes, fontWeights, lineHeights, breakpoints } = lightTheme;
    const rem = (length: string) => {
      expect(length).toMatch(/^\d*\.?\d+rem$/);
      return Number.parseFloat(length);
    };

    // Sizes and breakpoints ascend (rem only).
    expect(rem(fontSizes.small)).toBeLessThan(rem(fontSizes.body));
    expect(rem(fontSizes.body)).toBeLessThan(rem(fontSizes.title));
    expect(rem(breakpoints.sm)).toBeLessThan(rem(breakpoints.md));
    expect(rem(breakpoints.md)).toBeLessThan(rem(breakpoints.lg));
    // The weights are the font's 400/500/600, inside its 100–700 axis (fonts/README.md).
    expect([fontWeights.regular, fontWeights.medium, fontWeights.semibold]).toEqual([
      400, 500, 600,
    ]);
    // WCAG 1.4.12: line height at least 1.5; the transcript reads more loosely.
    expect(lineHeights.body).toBeGreaterThanOrEqual(1.5);
    expect(lineHeights.reading).toBeGreaterThan(lineHeights.body);
    // Both themes share the scale.
    expect(darkTheme.fontSizes).toEqual(fontSizes);
    expect(darkTheme.lineHeights).toEqual(lineHeights);

    // The app root sets the body size and line height (read there for the same jsdom
    // reason as the font family above).
    renderAt('/queue');
    const shell = screen.getByRole('main').parentElement as HTMLElement;
    expect(getComputedStyle(shell).fontSize).toBe(px(fontSizes.body));
    expect(getComputedStyle(shell).lineHeight).toBe(String(lineHeights.body));
  });

  it('cycles the theme control through auto, light and dark, storing the choice', () => {
    localStorage.clear();
    const listeners: (() => void)[] = [];
    const media = {
      matches: false,
      media: '(prefers-color-scheme: dark)',
      addEventListener: vi.fn((_event: string, listener: () => void) => listeners.push(listener)),
      removeEventListener: vi.fn(),
    };
    vi.stubGlobal(
      'matchMedia',
      vi.fn(() => media),
    );
    const { unmount } = renderAt('/queue');
    const background = () => getComputedStyle(screen.getByRole('main')).backgroundColor;

    // No stored choice: the OS decides (light).
    expect(background()).toBe(asRgb(lightTheme.colors.background));

    // auto → light: stored, and it overrides the OS.
    fireEvent.click(screen.getByRole('button', { name: 'Theme: system' }));
    expect(localStorage.getItem('stt-theme-mode')).toBe('light');
    act(() => {
      media.matches = true;
      listeners.forEach((listener) => listener());
    });
    expect(background()).toBe(asRgb(lightTheme.colors.background));

    // light → dark: stored and applied.
    fireEvent.click(screen.getByRole('button', { name: 'Theme: light' }));
    expect(localStorage.getItem('stt-theme-mode')).toBe('dark');
    expect(background()).toBe(asRgb(darkTheme.colors.background));

    // dark → auto: the stored choice is cleared and the OS rules again.
    fireEvent.click(screen.getByRole('button', { name: 'Theme: dark' }));
    expect(localStorage.getItem('stt-theme-mode')).toBeNull();
    expect(background()).toBe(asRgb(darkTheme.colors.background));
    act(() => {
      media.matches = false;
      listeners.forEach((listener) => listener());
    });
    expect(background()).toBe(asRgb(lightTheme.colors.background));

    unmount();
    vi.unstubAllGlobals();
  });
});
