import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { asRgb, px, required } from '../../test/dom';
import { renderThemed } from '../../test/render';
import { lightTheme } from '../theme/theme';
import { Slider } from './Slider';

describe('the slider', () => {
  it('is a keyboard-operable seek bar styled from the tokens', () => {
    const onChange = vi.fn();
    const { unmount } = renderThemed(
      <Slider value={40} max={100} onChange={onChange} label="Seek" />,
    );

    const root = screen.getByRole('slider', { name: 'Seek' });
    expect(root).toHaveAttribute('aria-valuemin', '0');
    expect(root).toHaveAttribute('aria-valuemax', '100');
    expect(root).toHaveAttribute('aria-valuenow', '40');

    // The track and its fill come from the tokens. The role slider is the inner thumb
    // span; Radix wraps it in a positioned span, and the component's own root span is
    // that wrapper's parent (the track is its first child).
    const rootSpan = required(required(required(root.parentElement).parentElement));
    const track = required(rootSpan.firstElementChild);
    expect(getComputedStyle(track).height).toBe(px(lightTheme.spacing.xs));
    expect(getComputedStyle(track).backgroundColor).toBe(asRgb(lightTheme.colors.control));
    const range = required(track.firstElementChild);
    expect(getComputedStyle(range).backgroundColor).toBe(asRgb(lightTheme.colors.accent));

    // The thumb is a token-sized circle.
    const thumbStyle = getComputedStyle(root);
    expect(thumbStyle.width).toBe(px(lightTheme.sizes.sliderThumb));
    expect(thumbStyle.height).toBe(px(lightTheme.sizes.sliderThumb));
    expect(thumbStyle.backgroundColor).toBe(asRgb(lightTheme.colors.surfaceRaised));
    expect(thumbStyle.borderTopColor).toBe(asRgb(lightTheme.colors.control));

    // The arrow keys step the value (Radix's arrow-key handling).
    fireEvent.keyDown(root, { key: 'ArrowRight' });
    expect(onChange).toHaveBeenCalledWith(41);
    unmount();

    renderThemed(<Slider value={0} max={100} onChange={() => undefined} label="Off" disabled />);
    const disabled = screen.getByRole('slider', { name: 'Off' });
    expect(disabled).toHaveAttribute('aria-disabled', 'true');
  });

  it("is its thumb's positioning context, so the thumb travels within the slider's own track", () => {
    renderThemed(<Slider value={40} max={100} onChange={() => undefined} label="Seek" />);

    // Radix places the thumb's wrapper with `position: absolute` and `left: <value>%`, measured
    // against the nearest positioned ancestor. The slider's root must be that ancestor;
    // otherwise the percentage resolves against something wider up the page (item 29: the
    // thumb spanned the page width while the fill stayed in the bar). jsdom does no layout,
    // so the rule is checked on the computed styles.
    const wrapper = required(screen.getByRole('slider', { name: 'Seek' }).parentElement);
    expect(getComputedStyle(wrapper).position).toBe('absolute');
    const root = required(wrapper.parentElement);
    expect(getComputedStyle(root).position).toBe('relative');
  });
});
