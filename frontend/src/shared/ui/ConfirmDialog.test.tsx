import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { renderThemed } from '../../test/render';
import { ConfirmDialog } from './ConfirmDialog';

describe('the confirmation dialog', () => {
  it('runs the action on confirm and not on cancel or Escape', () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    const show = () =>
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

    const confirmed = show();
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    confirmed.unmount();

    onConfirm.mockClear();
    const cancelled = show();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onConfirm).not.toHaveBeenCalled();
    expect(onCancel).toHaveBeenCalledTimes(1);
    cancelled.unmount();

    onConfirm.mockClear();
    onCancel.mockClear();
    const escaped = show();
    // Escape closes the dialog with an empty returnValue, then fires `close`.
    const dialog = screen.getByRole('dialog');
    (dialog as HTMLDialogElement).returnValue = '';
    fireEvent(dialog, new Event('close'));
    expect(onConfirm).not.toHaveBeenCalled();
    expect(onCancel).toHaveBeenCalledTimes(1);
    escaped.unmount();
  });
});
