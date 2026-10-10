import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { asRgb, px, required } from '../../test/dom';
import { renderThemed } from '../../test/render';
import { lightTheme } from '../theme/theme';
import { Button } from './Button';
import { Tooltip } from './Tooltip';

describe('the tooltip', () => {
  it('labels its trigger on hover and on keyboard focus, styled from the tokens', async () => {
    const renderTooltip = () =>
      renderThemed(
        <Tooltip label="Dark mode" delayDuration={0}>
          <Button variant="secondary">Toggle</Button>
        </Tooltip>,
      );

    // Hover opens it (Radix opens tooltips on pointer move).
    const byHover = renderTooltip();
    const trigger = screen.getByRole('button', { name: 'Toggle' });
    fireEvent.pointerMove(trigger);
    const tooltip = await screen.findByRole('tooltip');
    expect(tooltip).toHaveTextContent('Dark mode');
    const style = getComputedStyle(required(tooltip));
    expect(style.backgroundColor).toBe(asRgb(lightTheme.colors.surfaceRaised));
    expect(style.color).toBe(asRgb(lightTheme.colors.text));
    expect(style.borderTopColor).toBe(asRgb(lightTheme.colors.border));
    expect(style.borderRadius).toBe(lightTheme.radii.card);
    expect(style.boxShadow).toBe(lightTheme.shadows.card);
    expect(style.paddingTop).toBe(px(lightTheme.spacing.xs));
    expect(style.paddingLeft).toBe(px(lightTheme.spacing.sm));
    byHover.unmount();

    // Keyboard focus opens it too.
    const byFocus = renderTooltip();
    fireEvent.focus(screen.getByRole('button', { name: 'Toggle' }));
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Dark mode');
    byFocus.unmount();
  });
});
