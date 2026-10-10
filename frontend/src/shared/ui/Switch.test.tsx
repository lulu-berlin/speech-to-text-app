import { fireEvent, screen } from '@testing-library/react';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { asRgb, px, required } from '../../test/dom';
import { renderThemed } from '../../test/render';
import { lightTheme } from '../theme/theme';
import { Switch } from './Switch';

// The controlled value: the parent flips the switch via onCheckedChange.
function Toggle() {
  const [checked, setChecked] = useState(false);
  return <Switch checked={checked} onCheckedChange={setChecked} label="Pause the queue" />;
}

describe('the switch', () => {
  it('toggles like a switch and styles its track and thumb from the tokens', () => {
    const onCheckedChange = vi.fn();
    const off = renderThemed(
      <Switch checked={false} onCheckedChange={onCheckedChange} label="Pause the queue" />,
    );
    const root = screen.getByRole('switch', { name: 'Pause the queue' });
    expect(root).toHaveAttribute('aria-checked', 'false');
    const rootStyle = getComputedStyle(root);
    expect(rootStyle.width).toBe(px(lightTheme.sizes.switchWidth));
    expect(rootStyle.height).toBe(px(lightTheme.sizes.switchHeight));
    expect(rootStyle.backgroundColor).toBe(asRgb(lightTheme.colors.border));
    expect(rootStyle.justifyContent).toBe('flex-start');
    const thumb = required(root.firstElementChild);
    expect(getComputedStyle(thumb).width).toBe(px(lightTheme.sizes.switchThumb));
    expect(getComputedStyle(thumb).backgroundColor).toBe(asRgb(lightTheme.colors.surfaceRaised));
    off.unmount();

    // A click flips it, and the checked state takes the accent fill.
    renderThemed(<Toggle />);
    const toggle = screen.getByRole('switch', { name: 'Pause the queue' });
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-checked', 'true');
    const checkedStyle = getComputedStyle(toggle);
    expect(checkedStyle.backgroundColor).toBe(asRgb(lightTheme.colors.accent));
    expect(checkedStyle.justifyContent).toBe('flex-end');

    // Disabled switches stay put (and get the muted interactive-state colour).
    renderThemed(<Switch checked label="Off" onCheckedChange={() => undefined} disabled />);
    const stale = screen.getByRole('switch', { name: 'Off' });
    expect(stale).toBeDisabled();
    expect(getComputedStyle(stale).color).toBe(asRgb(lightTheme.colors.textMuted));
  });
});
