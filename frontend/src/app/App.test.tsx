import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { eventStreamResponse } from '../shared/api/handlers';
import {
  errorDuplicateAudio,
  snapshot as snapshotFixture,
  taskCompleted,
  taskRunning,
} from '../shared/api/fixtures';
import { asRgb, mediaRulesFor, px, required, stateRulesFor, styleRules } from '../test/dom';
import { renderThemed } from '../test/render';
import { Skeleton } from '../shared/ui/Skeleton';
import { Spinner } from '../shared/ui/Spinner';
import { darkTheme, lightTheme } from '../shared/theme/theme';
import { controllableEventStream } from '../test/eventStream';
import { server } from '../test/server';
import { App } from './App';

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

// The header's mascot (item 31): the artwork is the header svg; it swaps between the
// animated mascot and the small static logo depending on the shell's placeholder count.
function headerMascot(): SVGElement {
  return required(required(screen.getByRole('banner')).querySelector('svg')) as SVGElement;
}

function headerMascotSize(): string {
  return getComputedStyle(headerMascot()).width;
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

  it('shows the stenographer animation next to the product name at the header size', async () => {
    renderAt('/queue');
    // The header shows its animated mascot (at the header size) once no placeholder is
    // on screen; until the snapshot lands, the shell's loading region is that
    // placeholder and the header shows the small static logo instead (item 31).
    await screen.findByText('Live');

    const title = screen.getByRole('heading', { name: 'Speech-to-Text' });
    const header = required(title.closest('header'));
    const steno = required(header.querySelector('svg')) as SVGElement;

    // The mascot, inside its home link (item 31), sits directly before the product name.
    const home = required(header.querySelector('a'));
    expect(home).toContainElement(steno);
    expect(home.nextElementSibling).toBe(title);

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

describe('the not-found pages', () => {
  it('keep their heading and explain with the error callout and a way back, for an unknown page and an unknown task', async () => {
    // An unknown address: the heading, then the callout with the way to the queue.
    const page = renderAt('/no-such-page');
    expect(screen.getByRole('heading', { name: 'Not found' })).toBeInTheDocument();
    const pageCallout = screen.getByRole('alert');
    expect(pageCallout).toHaveTextContent("This page doesn't exist");
    expect(pageCallout).toHaveTextContent('Check the address, or go to the queue.');
    expect(within(pageCallout).getByRole('link', { name: 'Go to the queue' })).toHaveAttribute(
      'href',
      '/queue',
    );
    expect(getComputedStyle(pageCallout).borderLeftColor).toBe(asRgb(lightTheme.colors.danger));
    page.unmount();

    // An unknown task (no id 42 in snapshot.json), once live: the same callout form with
    // the way back to History.
    renderAt('/history/42');
    expect(await screen.findByRole('heading', { name: 'Task not found' })).toBeInTheDocument();
    const taskCallout = screen.getByRole('alert');
    expect(taskCallout).toHaveTextContent("This task doesn't exist");
    expect(taskCallout).toHaveTextContent('It was deleted, or the link is wrong.');
    expect(within(taskCallout).getByRole('link', { name: 'Back to History' })).toHaveAttribute(
      'href',
      '/history',
    );
    expect(getComputedStyle(taskCallout).borderLeftColor).toBe(asRgb(lightTheme.colors.danger));
  });
});

// The focusable elements of the document that no `:focus-visible` rule gives the focus
// ring token. Hidden elements (the file input) are skipped.
function elementsWithoutFocusRing(): string[] {
  const focusable = document.querySelectorAll<HTMLElement>(
    'a[href], button, input, select, textarea, [tabindex]:not([tabindex="-1"])',
  );
  return [...focusable]
    .filter((element) => element.closest('[hidden]') === null)
    .filter(
      (element) =>
        !stateRulesFor(element, ':focus-visible').some((rule) =>
          rule.style.boxShadow.includes(lightTheme.colors.focusRing),
        ),
    )
    .map(
      (element) =>
        `${element.tagName.toLowerCase()} "${element.getAttribute('aria-label') ?? element.textContent?.trim() ?? ''}"`,
    );
}

describe('keyboard focus', () => {
  it('is visible on every focusable element of every view: each carries the focus ring token', async () => {
    const missing = new Set<string>();
    const collect = (view: string) => {
      for (const element of elementsWithoutFocusRing()) {
        missing.add(`${view}: ${element}`);
      }
    };

    // Queue, with every control on show: both number inputs, a finish notice and a
    // duplicate upload's link.
    const events = controllableEventStream();
    server.use(
      http.post('/api/tasks', () => HttpResponse.json(errorDuplicateAudio, { status: 409 })),
    );
    const queue = renderAt('/queue');
    await screen.findByText('Live');
    fireEvent.change(screen.getByRole('combobox', { name: 'Speakers' }), {
      target: { value: 'fixed' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Advanced' }));
    events.send('task-updated', { ...taskCompleted, id: 5, filename: 'lecture.m4a' });
    await screen.findByText(/lecture\.m4a transcribed/);
    const files = [new File(['x'], 'again.mp3', { type: 'audio/mpeg' })];
    fireEvent.drop(screen.getByRole('button', { name: /drop/i }), { dataTransfer: { files } });
    await screen.findByText(/Already uploaded/);
    collect('queue');
    queue.unmount();

    // History, its controls and cards.
    const history = renderAt('/history');
    await screen.findByText('Live');
    await screen.findByRole('searchbox', { name: /search/i });
    collect('history');
    history.unmount();

    // A completed transcript (task 3) with its turns.
    const transcript = renderAt('/history/3');
    await screen.findByText('Good morning.');
    collect('transcript');
    transcript.unmount();

    // A failed task's status page (task 4), a pending task's page (task 7), and an
    // unknown address.
    const failed = renderAt('/history/4');
    await screen.findByRole('heading', { name: 'call.ogg' });
    collect('failed task');
    failed.unmount();

    const pending = renderAt('/history/7');
    await screen.findByRole('heading', { name: 'memo.wav' });
    collect('pending task');
    pending.unmount();

    renderAt('/no-such-page');
    collect('not found');

    expect([...missing]).toEqual([]);
    // The walk renders six views in turn, so it gets an explicit budget beyond vitest's
    // 5 s default for a loaded host (item 25, user's call).
  }, 15_000);
});

describe('the phone layout', () => {
  it('keeps every view within a phone width: the header wraps onto two lines, row controls wrap, and long filenames break', async () => {
    const phone = `(max-width: ${lightTheme.breakpoints.sm})`;

    // The header: below the small breakpoint it wraps, and the navigation, connection
    // indicator and theme control take a line of their own under the mascot and title.
    // jsdom cannot evaluate media queries, so the emitted rules are asserted.
    const queue = renderAt('/queue');
    await screen.findByText('Live');
    const header = required(screen.getByRole('banner')) as HTMLElement;
    expect(mediaRulesFor(header, phone).map((rule) => rule.style.flexWrap)).toContain('wrap');
    const controls = required(screen.getByRole('navigation').parentElement) as HTMLElement;
    expect(controls).not.toBe(header);
    expect(controls).toContainElement(screen.getByText('Live'));
    expect(controls).toContainElement(screen.getByRole('button', { name: /^Theme:/ }));
    expect(mediaRulesFor(controls, phone).map((rule) => rule.style.flexBasis)).toContain('100%');

    // A queue row: its controls line wraps, and the filename can shrink and break.
    const badge = screen.getByText('RUNNING');
    expect(getComputedStyle(required(badge.parentElement)).flexWrap).toBe('wrap');
    const rowName = screen.getByText('lecture.m4a');
    expect(getComputedStyle(rowName).overflowWrap).toBe('anywhere');
    expect(getComputedStyle(rowName).minWidth).toBe('0px');
    queue.unmount();

    // A History card's title link breaks and can shrink beside the badge.
    const history = renderAt('/history');
    const cardLink = await screen.findByRole('link', { name: 'interview.mp3' });
    expect(getComputedStyle(cardLink).overflowWrap).toBe('anywhere');
    expect(getComputedStyle(cardLink).minWidth).toBe('0px');
    history.unmount();

    // The transcript's heading (the filename) breaks too.
    renderAt('/history/3');
    const heading = await screen.findByRole('heading', { level: 2, name: 'interview.mp3' });
    expect(getComputedStyle(heading).overflowWrap).toBe('anywhere');
  });
});

describe('the sticky header', () => {
  it('stays at the top while the page scrolls: sticky above the content, on the token background and border, on Queue, History and the transcript', async () => {
    const assertSticky = () => {
      const header = required(document.querySelector('header')) as HTMLElement;
      const style = getComputedStyle(header);
      // The header does not scroll away, and it is raised above the page content, so
      // what scrolls under it paints beneath; the token background hides it there.
      expect(style.position).toBe('sticky');
      expect(style.top).toBe('0px');
      expect(style.zIndex).toBe('1');
      expect(style.backgroundColor).toBe(asRgb(lightTheme.colors.background));
      expect(style.borderBottomColor).toBe(asRgb(lightTheme.colors.border));
    };

    const queue = renderAt('/queue');
    await screen.findByText('Live');
    assertSticky();
    queue.unmount();

    const history = renderAt('/history');
    await screen.findByRole('searchbox', { name: /search/i });
    assertSticky();
    history.unmount();

    renderAt('/history/3');
    await screen.findByText('Good morning.');
    assertSticky();
  });
});

describe('the shrinking header', () => {
  it('shows the full-size mascot at the top, shrinks to the compact size on scroll with a gated token transition, grows back at the top, and keeps typing while compact', async () => {
    // The default snapshot has a running task, so the header's mascot types.
    renderAt('/queue');
    await screen.findByText('Live');

    // At the top of the page the mascot is full-size and typing.
    expect(headerMascotSize()).toBe(px(lightTheme.sizes.stenoHeader));
    expect(headerMascot().getAttribute('class')).toContain('is-typing');

    // The size change is animated: a transition on the mascot's width and height with
    // the motion tokens, declared only inside the no-preference block (instant under
    // reduced motion, like every animation).
    const shrink = mediaRulesFor(headerMascot(), '(prefers-reduced-motion: no-preference)');
    expect(shrink.flatMap((rule) => rule.style.transitionProperty.split(','))).toEqual(
      expect.arrayContaining(['width', 'height']),
    );
    expect(shrink.map((rule) => rule.style.transitionDuration)).toContain(
      lightTheme.motion.shrinkDuration,
    );
    expect(shrink.map((rule) => rule.style.transitionTimingFunction)).toContain(
      lightTheme.motion.shrinkEasing,
    );
    const ungated = styleRules().filter(
      (rule): rule is CSSStyleRule =>
        rule instanceof CSSStyleRule &&
        headerMascot().matches(rule.selectorText) &&
        rule.style.transition !== '',
    );
    expect(ungated).toEqual([]);

    // Once the page scrolls the mascot shrinks to the compact size, still typing.
    Object.defineProperty(window, 'scrollY', { value: 200, configurable: true });
    fireEvent.scroll(window);
    expect(headerMascotSize()).toBe(px(lightTheme.sizes.stenoCompact));
    expect(headerMascot().getAttribute('class')).toContain('is-typing');

    // Back at the top it grows to the full size again.
    Object.defineProperty(window, 'scrollY', { value: 0, configurable: true });
    fireEvent.scroll(window);
    expect(headerMascotSize()).toBe(px(lightTheme.sizes.stenoHeader));

    // jsdom's scrollY is a readonly prototype accessor; the own property shadows it and
    // is removed again, so later tests read the default.
    const windowWithScrollShadow = window as { scrollY?: number };
    delete windowWithScrollShadow.scrollY;
  });
});

describe('the navigation pills', () => {
  it('shows the active link as a filled pill and a soft pill on hover, with a gated token transition and the focus ring kept', async () => {
    renderAt('/queue');
    await screen.findByText('Live');
    const queueLink = screen.getByRole('link', { name: 'Queue' });
    const historyLink = screen.getByRole('link', { name: 'History' });

    // The active link is a filled pill: the accent fill with the accent text on it, the
    // pill radius and spacing padding, all tokens.
    const active = getComputedStyle(queueLink);
    expect(active.backgroundColor).toBe(asRgb(lightTheme.colors.accent));
    expect(active.color).toBe(asRgb(lightTheme.colors.accentText));
    expect(active.borderRadius).toBe(lightTheme.radii.pill);
    expect(active.paddingTop).toBe(px(lightTheme.spacing.xs));
    expect(active.paddingLeft).toBe(px(lightTheme.spacing.sm));

    // Hover (jsdom cannot apply :hover, so the emitted rule is checked): a soft surface
    // pill behind the link, same radius.
    const hover = stateRulesFor(historyLink, ':hover');
    expect(hover.map((rule) => rule.style.backgroundColor)).toContain(
      asRgb(lightTheme.colors.surface),
    );

    // The pill transitions with the motion tokens, declared only inside the
    // no-preference block (instant under reduced motion).
    const pill = mediaRulesFor(historyLink, '(prefers-reduced-motion: no-preference)');
    expect(pill.flatMap((rule) => rule.style.transitionProperty.split(','))).toEqual(
      expect.arrayContaining(['background-color', 'color']),
    );
    expect(pill.map((rule) => rule.style.transitionDuration)).toContain(
      lightTheme.motion.navDuration,
    );
    expect(pill.map((rule) => rule.style.transitionTimingFunction)).toContain(
      lightTheme.motion.navEasing,
    );
    const ungated = styleRules().filter(
      (rule): rule is CSSStyleRule =>
        rule instanceof CSSStyleRule &&
        historyLink.matches(rule.selectorText) &&
        rule.style.transition !== '',
    );
    expect(ungated).toEqual([]);

    // The keyboard focus ring stays on the pill (the walk covers both links too).
    const focus = stateRulesFor(historyLink, ':focus-visible');
    expect(focus.some((rule) => rule.style.boxShadow.includes(lightTheme.colors.focusRing))).toBe(
      true,
    );
  });
});

describe('the mascot home link', () => {
  it('links the header mascot to / with an accessible name, and clicking it lands on the queue', async () => {
    renderAt('/history');
    await screen.findByText('Live');

    const home = screen.getByRole('link', { name: 'Speech-to-Text home' });
    expect(home).toHaveAttribute('href', '/');

    // The mascot stays decorative inside it: the link carries the name.
    const mascot = required(home.querySelector('svg')) as SVGElement;
    expect(mascot.getAttribute('aria-hidden')).toBe('true');
    expect(home).toContainElement(mascot);

    // The keyboard focus ring stays on the link (the walk covers it too).
    const focus = stateRulesFor(home, ':focus-visible');
    expect(focus.some((rule) => rule.style.boxShadow.includes(lightTheme.colors.focusRing))).toBe(
      true,
    );

    // A click on the mascot lands on the queue. Pressing Enter on the focused link is
    // the anchor's native activation (jsdom synthesizes no click for it, so the real
    // keyboard path is exercised in the e2e suite, item 7).
    fireEvent.click(home);
    expect(screen.getByRole('heading', { name: 'Queue' })).toBeInTheDocument();
  });
});

describe('the one-mascot rule', () => {
  it('shows the small static logo in the header while a placeholder shows the mascot, and the animated mascot otherwise — never two on screen', async () => {
    // While connecting, the shell's loading region shows the placeholder mascot, so the
    // header shows the small static logo: compact, resting, still the home link.
    const queue = renderAt('/queue');
    expect(headerMascotSize()).toBe(px(lightTheme.sizes.stenoCompact));
    expect(headerMascot().getAttribute('class')).not.toContain('is-typing');
    expect(headerMascot().closest('a')).toHaveAttribute('aria-label', 'Speech-to-Text home');

    // Once live with tasks there is no placeholder: the animated mascot returns at the
    // full size, typing while the running task runs.
    await screen.findByText('Live');
    expect(headerMascotSize()).toBe(px(lightTheme.sizes.stenoHeader));
    expect(headerMascot().getAttribute('class')).toContain('is-typing');
    queue.unmount();

    // History with nothing finished while a task runs: the empty state shows the
    // placeholder mascot, so the header's static logo returns even though a task runs —
    // the rule follows the placeholder, not the running task.
    server.use(
      http.get('/api/events', () =>
        eventStreamResponse([
          ['snapshot', { tasks: [taskRunning], queue: { paused: false, taskIds: [5] } }],
        ]),
      ),
    );
    const history = renderAt('/history');
    await screen.findByText('No transcripts yet');
    expect(headerMascotSize()).toBe(px(lightTheme.sizes.stenoCompact));
    expect(headerMascot().getAttribute('class')).not.toContain('is-typing');
    // The placeholder itself shows the mascot at the empty-state size.
    const placeholder = required(
      required(screen.getByText('No transcripts yet').parentElement).querySelector('svg'),
    );
    expect(getComputedStyle(placeholder).width).toBe(px(lightTheme.sizes.stenoEmpty));
    history.unmount();
  });
});

describe('reduced motion', () => {
  it('runs no animation or transition outside a prefers-reduced-motion gate', async () => {
    // A running task makes the header's mascot type, so its animation rules land in the
    // sheet; the loading primitives' pulses are rendered alongside.
    renderAt('/queue');
    await screen.findByText('Live');
    renderThemed(
      <>
        <Spinner />
        <Skeleton />
      </>,
    );

    // Every rule that declares an animation or transition, with the media block it sits in
    // (jsdom cannot evaluate media queries, so the gating is asserted structurally).
    const entries: { rule: CSSStyleRule; media: string | null }[] = [];
    for (const top of styleRules()) {
      if (top instanceof CSSMediaRule) {
        for (const inner of [...top.cssRules]) {
          if (inner instanceof CSSStyleRule) {
            entries.push({ rule: inner, media: top.conditionText });
          }
        }
      } else if (top instanceof CSSStyleRule) {
        entries.push({ rule: top, media: null });
      }
    }
    const motion = entries.filter(
      ({ rule }) => rule.style.animation !== '' || rule.style.transition !== '',
    );

    // The reduce block's neutralizations (the Spinner and Skeleton pulses stop by name).
    const reduced = new Set(
      entries
        .filter(
          ({ rule, media }) =>
            media === '(prefers-reduced-motion: reduce)' && rule.style.animation === 'none',
        )
        .map(({ rule }) => rule.selectorText),
    );

    for (const { rule, media } of motion) {
      if (media === '(prefers-reduced-motion: no-preference)') {
        continue;
      }
      // An ungated transition would run for everyone: none may exist.
      expect(rule.style.transition).toBe('');
      // An ungated animation must be one the reduce block stops by the same selector.
      expect(reduced.has(rule.selectorText)).toBe(true);
    }

    // Not vacuous: some rule is gated or neutralized (the mascot's animations at least).
    const gated = motion.filter(
      ({ rule, media }) =>
        media === '(prefers-reduced-motion: no-preference)' || reduced.has(rule.selectorText),
    );
    expect(gated.length).toBeGreaterThan(0);
  });
});

describe('the shell and the views on the tokens', () => {
  it('renders every view on the token background and text, inside the layout primitives, with the header on the token border', async () => {
    // Each view falls back to the page token background and text; the page and its stack
    // are the layout primitives; the header's rule is the border token.
    const assertView = () => {
      const main = screen.getByRole('main');
      expect(getComputedStyle(main).backgroundColor).toBe(asRgb(lightTheme.colors.background));
      expect(getComputedStyle(main).color).toBe(asRgb(lightTheme.colors.text));
      const page = required(main.firstElementChild) as HTMLElement;
      expect(getComputedStyle(page).marginLeft).toBe('auto');
      expect(getComputedStyle(page).marginRight).toBe('auto');
      const stack = required(page.firstElementChild) as HTMLElement;
      expect(getComputedStyle(stack).display).toBe('flex');
      expect(getComputedStyle(stack).flexDirection).toBe('column');
      const header = required(document.querySelector('header')) as HTMLElement;
      expect(getComputedStyle(header).borderBottomColor).toBe(asRgb(lightTheme.colors.border));
    };

    const queue = renderAt('/queue');
    await screen.findByText('lecture.m4a');
    assertView();
    queue.unmount();

    const history = renderAt('/history');
    await screen.findByRole('searchbox', { name: /search/i });
    assertView();
    history.unmount();

    const transcript = renderAt('/history/3');
    await screen.findByText('Good morning.');
    assertView();
    transcript.unmount();

    const failed = renderAt('/history/4');
    await screen.findByRole('heading', { name: 'call.ogg' });
    assertView();
    failed.unmount();

    const pending = renderAt('/history/7');
    await screen.findByRole('heading', { name: 'memo.wav' });
    assertView();
    pending.unmount();

    renderAt('/no-such-page');
    assertView();
  });
});
