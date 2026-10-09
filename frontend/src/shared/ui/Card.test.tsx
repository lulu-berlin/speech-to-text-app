import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { asRgb, px } from '../../test/dom';
import { renderThemed } from '../../test/render';
import { lightTheme } from '../theme/theme';
import { Card, Surface } from './Card';

describe('the surface containers', () => {
  it('renders the surface and the card from the tokens', () => {
    renderThemed(
      <>
        <Surface>group</Surface>
        <Card>panel</Card>
      </>,
    );

    const surface = screen.getByText('group');
    expect(getComputedStyle(surface).backgroundColor).toBe(asRgb(lightTheme.colors.surface));
    expect(getComputedStyle(surface).color).toBe(asRgb(lightTheme.colors.text));
    expect(getComputedStyle(surface).borderRadius).toBe(lightTheme.radii.card);

    const card = screen.getByText('panel');
    const style = getComputedStyle(card);
    expect(style.backgroundColor).toBe(asRgb(lightTheme.colors.surfaceRaised));
    expect(style.color).toBe(asRgb(lightTheme.colors.text));
    expect(style.borderTopWidth).toBe(px(lightTheme.borderWidths.thin));
    expect(style.borderTopStyle).toBe('solid');
    expect(style.borderTopColor).toBe(asRgb(lightTheme.colors.border));
    expect(style.borderRadius).toBe(lightTheme.radii.card);
    expect(style.padding).toBe(px(lightTheme.spacing.lg));
    // jsdom keeps box-shadow verbatim, token string included.
    expect(style.boxShadow).toBe(lightTheme.shadows.card);
  });
});
