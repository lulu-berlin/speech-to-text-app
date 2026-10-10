import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { Archive } from 'lucide-react';
import { describe, expect, it } from 'vitest';
import { App } from '../../app/App';
import { asRgb, px, required } from '../../test/dom';
import { renderThemed } from '../../test/render';
import { lightTheme } from '../theme/theme';
import { EmptyState } from './EmptyState';

describe('the empty state', () => {
  it('renders a centred, token-coloured block with a decorative icon', () => {
    const { container } = renderThemed(
      <EmptyState icon={Archive} title="No tasks yet">
        Upload an audio file to get started.
      </EmptyState>,
    );

    const block = required(container.firstElementChild);
    const style = getComputedStyle(block);
    expect(style.textAlign).toBe('center');
    for (const side of ['Top', 'Right', 'Bottom', 'Left'] as const) {
      expect(style[`padding${side}`]).toBe(px(lightTheme.spacing.xl));
    }

    const title = screen.getByText('No tasks yet');
    expect(getComputedStyle(title).color).toBe(asRgb(lightTheme.colors.text));
    const body = screen.getByText('Upload an audio file to get started.');
    expect(getComputedStyle(body).color).toBe(asRgb(lightTheme.colors.textMuted));

    // The mascot leads the block (item 17); the icon still renders at its token size.
    const icon = required(block.querySelectorAll('svg')[1]);
    expect(getComputedStyle(icon).width).toBe(px(lightTheme.iconSizes.md));
    // The description icon is decorative, and its wrapper takes the muted tone.
    expect(required(icon.parentElement).getAttribute('aria-hidden')).toBe('true');
    expect(getComputedStyle(required(icon.parentElement)).color).toBe(
      asRgb(lightTheme.colors.textMuted),
    );
    expect(screen.queryByRole('img')).toBeNull();
  });

  it('shows the larger instance on an empty state and, while connecting, in a labelled loading region', async () => {
    const { container } = renderThemed(
      <EmptyState title="No tasks yet">Upload an audio file to get started.</EmptyState>,
    );
    // The empty state leads with the mascot at the empty-state size, decorative.
    const steno = required(container.querySelector('svg'));
    expect(getComputedStyle(steno).width).toBe(px(lightTheme.sizes.stenoEmpty));
    expect(steno.getAttribute('aria-hidden')).toBe('true');

    // The app shell's loading region: while the connection is not live yet, a waiting
    // region shows the stenographer with a short label — in place of a spinner/skeleton.
    render(
      <MemoryRouter initialEntries={['/queue']}>
        <App />
      </MemoryRouter>,
    );
    const label = screen.getByText('Connecting…');
    const region = required(label.parentElement);
    const waiting = required(region.querySelector('svg'));
    expect(getComputedStyle(waiting).width).toBe(px(lightTheme.sizes.stenoEmpty));

    // Once the snapshot lands the region is gone.
    await screen.findByText('Live');
    expect(screen.queryByText('Connecting…')).toBeNull();
  });
});
