import { screen } from '@testing-library/react';
import { Archive } from 'lucide-react';
import { describe, expect, it } from 'vitest';
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

    const icon = required(block.querySelector('svg'));
    expect(getComputedStyle(icon).width).toBe(px(lightTheme.iconSizes.md));
    // The description icon is decorative, and its wrapper takes the muted tone.
    expect(required(icon.parentElement).getAttribute('aria-hidden')).toBe('true');
    expect(getComputedStyle(required(icon.parentElement)).color).toBe(
      asRgb(lightTheme.colors.textMuted),
    );
    expect(screen.queryByRole('img')).toBeNull();
  });
});
