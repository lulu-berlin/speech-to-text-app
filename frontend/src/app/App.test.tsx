import { act, fireEvent, render, screen } from '@testing-library/react';
import { http } from 'msw';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { eventStreamResponse } from '../shared/api/handlers';
import { snapshot as snapshotFixture } from '../shared/api/fixtures';
import { asRgb, px, required } from '../test/dom';
import { darkTheme, lightTheme } from '../shared/theme/theme';
import { server } from '../test/server';
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

  it('labels the loading region by state: Connecting… until the snapshot, Reconnecting… on a drop', async () => {
    // The stream sends the snapshot then ends; the eventsource pauses its auto-retry
    // for the SSE retry field, so the machine stays reconnecting through the assertions.
    server.use(
      http.get('/api/events', () =>
        eventStreamResponse([['snapshot', snapshotFixture]], { end: true, retryMs: 60000 }),
      ),
    );
    renderAt('/queue');

    // Until the first snapshot arrives the region says Connecting…
    expect(screen.getByText('Connecting…')).toBeInTheDocument();
    await screen.findByText('Live');

    // The connection drops: the region returns, labelled for the reconnect, with the mascot.
    expect(await screen.findByText('Reconnecting…')).toBeInTheDocument();
    expect(screen.queryByText('Connecting…')).toBeNull();
    const region = required(screen.getByText('Reconnecting…').parentElement);
    expect(required(region.querySelector('svg')).getAttribute('aria-hidden')).toBe('true');
  });

  it('shows the stenographer animation next to the product name at the header size', () => {
    renderAt('/queue');

    const title = screen.getByRole('heading', { name: 'Speech-to-Text' });
    const header = required(title.closest('header'));
    const steno = required(header.querySelector('svg'));

    // The mascot sits directly before the product name.
    expect(steno.nextElementSibling).toBe(title);

    // And it renders at the header size (the cropped artwork fills the box).
    const style = getComputedStyle(steno);
    expect(style.width).toBe(px(lightTheme.sizes.stenoHeader));
    expect(style.height).toBe(px(lightTheme.sizes.stenoHeader));
  });

  it('redirects / to /queue, shows the history and transcript views, and a not-found view otherwise', async () => {
    const queue = renderAt('/');
    expect(screen.getByRole('heading', { name: 'Queue' })).toBeInTheDocument();
    queue.unmount();

    const history = renderAt('/history');
    expect(screen.getByRole('heading', { name: 'History' })).toBeInTheDocument();
    history.unmount();

    // The transcript view renders nothing of its own before the snapshot (item 25), so the
    // route is identified by its page for task 7 (memo.wav, still queued in snapshot.json).
    const transcript = renderAt('/history/7');
    expect(await screen.findByRole('heading', { name: 'memo.wav' })).toBeInTheDocument();
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
