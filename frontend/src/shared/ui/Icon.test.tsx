import { screen } from '@testing-library/react';
import { X } from 'lucide-react';
import { describe, expect, it } from 'vitest';
import { asRgb, px, required } from '../../test/dom';
import { renderThemed } from '../../test/render';
import { lightTheme } from '../theme/theme';
import { Icon } from './Icon';

describe('the icon wrapper', () => {
  it('sizes and colours an icon from the tokens, and hides it when decorative', () => {
    const sized = renderThemed(<Icon icon={X} size="lg" tone="accent" />);
    const wrapper = required(sized.container.firstElementChild);
    expect(getComputedStyle(required(wrapper.querySelector('svg'))).width).toBe(
      px(lightTheme.iconSizes.lg),
    );
    expect(getComputedStyle(required(wrapper.querySelector('svg'))).height).toBe(
      px(lightTheme.iconSizes.lg),
    );
    expect(getComputedStyle(wrapper).color).toBe(asRgb(lightTheme.colors.accent));
    // Decorative by default: aria-hidden, and no role in the accessibility tree.
    expect(wrapper.getAttribute('aria-hidden')).toBe('true');
    expect(screen.queryByRole('img')).toBeNull();
    sized.unmount();

    // Without a size the medium token applies.
    const plain = renderThemed(<Icon icon={X} />);
    const plainSvg = required(required(plain.container.firstElementChild).querySelector('svg'));
    expect(getComputedStyle(plainSvg).width).toBe(px(lightTheme.iconSizes.md));
    plain.unmount();

    // A label makes it meaningful: it is reachable by role and name.
    const labelled = renderThemed(<Icon icon={X} label="Close" />);
    expect(screen.getByRole('img', { name: 'Close' })).toBeInTheDocument();
    labelled.unmount();
  });
});
