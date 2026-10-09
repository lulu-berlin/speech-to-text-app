import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { asRgb } from '../test/dom';
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
});
