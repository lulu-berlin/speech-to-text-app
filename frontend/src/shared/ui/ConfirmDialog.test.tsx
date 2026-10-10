import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { styleRules } from '../../test/dom';
import { renderThemed } from '../../test/render';
import { lightTheme } from '../theme/theme';
import { ConfirmDialog } from './ConfirmDialog';

describe('the confirmation dialog', () => {
  it('traps focus in the dialog, runs only the confirm action, and cancels on Escape', () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    const renderDialog = () =>
      renderThemed(
        <ConfirmDialog
          open
          title="Delete task"
          confirmLabel="Delete"
          onConfirm={onConfirm}
          onCancel={onCancel}
        >
          Delete task 7? This cannot be undone.
        </ConfirmDialog>,
      );

    // On open, Radix moves focus into the modal dialog.
    const opened = renderDialog();
    const dialog = screen.getByRole('dialog');
    expect(dialog.contains(document.activeElement)).toBe(true);

    // Escape cancels; the action does not run.
    fireEvent.keyDown(dialog, { key: 'Escape' });
    expect(onConfirm).not.toHaveBeenCalled();
    expect(onCancel).toHaveBeenCalledTimes(1);
    opened.unmount();

    // Confirm runs the action and nothing else.
    onCancel.mockClear();
    const confirmed = renderDialog();
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onCancel).not.toHaveBeenCalled();
    confirmed.unmount();

    // Cancel runs the cancel callback only.
    onConfirm.mockClear();
    const cancelled = renderDialog();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onConfirm).not.toHaveBeenCalled();
    expect(onCancel).toHaveBeenCalledTimes(1);
    cancelled.unmount();

    // The backdrop is the overlay token (jsdom renders it as a portal style rule).
    expect(styleRules().some((rule) => rule.cssText.includes(lightTheme.colors.overlay))).toBe(
      true,
    );
  });
});
