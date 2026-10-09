import { describe, expect, it } from 'vitest';
import { renderThemed } from '../../test/render';
import { lightTheme } from '../theme/theme';
import { Page, Row, Stack } from './layout';

// jsdom normalises lengths to px at the default root font size (1rem = 16px) but keeps
// `gap` exactly as written, so both forms are checked against the token they came from.
function px(length: string): string {
  return `${Number.parseFloat(length) * 16}px`;
}

function required(element: Element | null | undefined): HTMLElement {
  if (!(element instanceof HTMLElement)) {
    throw new Error('The layout primitive did not render');
  }
  return element;
}

describe('the layout primitives', () => {
  it('applies the token spacing and the page max width', () => {
    const { container } = renderThemed(
      <Page>
        <Stack gap="md">
          <Row gap="sm">
            <span>first</span>
          </Row>
          <Row>
            <span>second</span>
          </Row>
        </Stack>
      </Page>,
    );

    const page = required(container.firstElementChild);
    const pageStyle = getComputedStyle(page);
    expect(pageStyle.maxWidth).toBe(px(lightTheme.sizes.pageMaxWidth));
    expect(pageStyle.marginLeft).toBe('auto');
    expect(pageStyle.marginRight).toBe('auto');
    expect(pageStyle.paddingLeft).toBe(px(lightTheme.spacing.lg));

    const stack = required(page.firstElementChild);
    const stackStyle = getComputedStyle(stack);
    expect(stackStyle.display).toBe('flex');
    expect(stackStyle.flexDirection).toBe('column');
    expect(stackStyle.gap).toBe(lightTheme.spacing.md);

    const rows = [...stack.children].map(required);
    expect(rows).toHaveLength(2);
    const rowStyle = getComputedStyle(required(rows[0]));
    expect(rowStyle.display).toBe('flex');
    expect(rowStyle.flexDirection).toBe('row');
    expect(rowStyle.alignItems).toBe('center');
    expect(rowStyle.gap).toBe(lightTheme.spacing.sm);
    // A row without a gap takes the default.
    expect(getComputedStyle(required(rows[1])).gap).toBe(lightTheme.spacing.sm);
  });
});
