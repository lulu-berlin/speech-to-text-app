import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { asRgb } from '../../test/dom';
import { renderThemed } from '../../test/render';
import { lightTheme } from '../theme/theme';
import { Button } from './Button';
import { DropdownMenu } from './DropdownMenu';

describe('the dropdown menu', () => {
  it('opens on the trigger, selects an item, cancels on Escape, and styles from the tokens', async () => {
    const onPause = vi.fn();
    const renderMenu = () =>
      renderThemed(
        <DropdownMenu
          trigger={<Button variant="secondary">Actions</Button>}
          items={[
            { label: 'Pause', onSelect: onPause },
            { label: 'Cancel', danger: true },
            { label: 'Unavailable', disabled: true },
          ]}
        />,
      );

    // The trigger opens the menu with a menuitem per item (Radix opens on pointer down).
    const opened = renderMenu();
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Actions' }), { button: 0 });
    await screen.findByRole('menu');
    expect(screen.getByRole('menuitem', { name: 'Pause' })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'Cancel' })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'Unavailable' })).toBeInTheDocument();
    opened.unmount();

    // Selecting an item runs its action and closes the menu.
    const withSelection = renderMenu();
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Actions' }), { button: 0 });
    await screen.findByRole('menu');
    fireEvent.click(screen.getByRole('menuitem', { name: 'Pause' }));
    expect(onPause).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('menu')).toBeNull();
    withSelection.unmount();

    // Escape cancels without selecting.
    const withEscape = renderMenu();
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Actions' }), { button: 0 });
    const openedMenu = await screen.findByRole('menu');
    fireEvent.keyDown(openedMenu, { key: 'Escape' });
    expect(onPause).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('menu')).toBeNull();
    withEscape.unmount();

    // The menu is the floating panel; a disabled item is muted, a danger item is danger.
    const styled = renderMenu();
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Actions' }), { button: 0 });
    const panel = await screen.findByRole('menu');
    const panelStyle = getComputedStyle(panel);
    expect(panelStyle.backgroundColor).toBe(asRgb(lightTheme.colors.surfaceRaised));
    expect(panelStyle.borderRadius).toBe(lightTheme.radii.card);
    expect(panelStyle.boxShadow).toBe(lightTheme.shadows.card);
    expect(getComputedStyle(screen.getByRole('menuitem', { name: 'Unavailable' })).color).toBe(
      asRgb(lightTheme.colors.textMuted),
    );
    expect(getComputedStyle(screen.getByRole('menuitem', { name: 'Cancel' })).color).toBe(
      asRgb(lightTheme.colors.danger),
    );
    styled.unmount();
  });
});
