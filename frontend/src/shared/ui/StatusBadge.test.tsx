import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { taskStatuses } from '../api/types';
import type { TaskStatus } from '../api/types';
import { asRgb, px } from '../../test/dom';
import { renderThemed } from '../../test/render';
import { lightTheme } from '../theme/theme';
import type { StatusGroup } from '../theme/theme';
import { StatusBadge, statusGroup } from './StatusBadge';

// The status→group mapping from the item 24 spec (ADR 0033): five groups, six statuses.
const EXPECTED_GROUP: Record<TaskStatus, StatusGroup> = {
  QUEUED: 'pending',
  PAUSED: 'pending',
  RUNNING: 'active',
  COMPLETED: 'done',
  FAILED: 'problem',
  CANCELED: 'gone',
};

describe('the status badge', () => {
  it('renders each status with its group tokens and the exact status text', () => {
    for (const status of taskStatuses) {
      expect(statusGroup(status)).toBe(EXPECTED_GROUP[status]);
    }

    renderThemed(
      <>
        {taskStatuses.map((status) => (
          <StatusBadge key={status} status={status} />
        ))}
      </>,
    );

    for (const status of taskStatuses) {
      const badge = screen.getByText(status);
      const group = lightTheme.colors.status[EXPECTED_GROUP[status]];
      const style = getComputedStyle(badge);
      expect(style.backgroundColor).toBe(asRgb(group.background));
      expect(style.color).toBe(asRgb(group.text));
      expect(style.borderRadius).toBe(lightTheme.radii.pill);
      expect(style.paddingTop).toBe(px(lightTheme.spacing.xs));
      expect(style.paddingLeft).toBe(px(lightTheme.spacing.sm));
    }
  });
});
