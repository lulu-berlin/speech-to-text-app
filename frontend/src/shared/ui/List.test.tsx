import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { asRgb, px, required, styleRules } from '../../test/dom';
import { renderThemed } from '../../test/render';
import { lightTheme } from '../theme/theme';
import { List } from './List';
import { ListRow } from './ListRow';

describe('the list', () => {
  it('renders rows with the row or card styling and their hover and focus states', () => {
    renderThemed(
      <List>
        <ListRow>One</ListRow>
        <ListRow variant="card">Two</ListRow>
      </List>,
    );

    const list = screen.getByRole('list');
    const listStyle = getComputedStyle(list);
    expect(listStyle.display).toBe('flex');
    expect(listStyle.flexDirection).toBe('column');
    expect(listStyle.gap).toBe(lightTheme.spacing.sm);
    expect(listStyle.paddingLeft).toBe('0px');
    // The list-style shorthand is kept verbatim by jsdom (it does not decompose it).
    expect(listStyle.listStyle).toBe('none');

    const rows = screen.getAllByRole('listitem');
    expect(rows).toHaveLength(2);
    const plain = getComputedStyle(required(rows[0]));
    expect(plain.backgroundColor).toBe('rgba(0, 0, 0, 0)');
    expect(plain.paddingLeft).toBe(px(lightTheme.spacing.lg));
    expect(plain.paddingTop).toBe(px(lightTheme.spacing.sm));

    const card = getComputedStyle(required(rows[1]));
    expect(card.backgroundColor).toBe(asRgb(lightTheme.colors.surfaceRaised));
    expect(card.borderTopColor).toBe(asRgb(lightTheme.colors.border));
    expect(card.borderRadius).toBe(lightTheme.radii.card);
    expect(card.paddingLeft).toBe(px(lightTheme.spacing.lg));
    expect(card.paddingTop).toBe(px(lightTheme.spacing.md));

    // The state declarations carry the tokens (asserted as emitted rules: jsdom cannot
    // apply pseudo-classes).
    const rules = styleRules().filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule);
    expect(
      rules.some(
        (rule) =>
          rule.selectorText.includes(':hover') &&
          rule.style.backgroundColor === asRgb(lightTheme.colors.surface),
      ),
    ).toBe(true);
    expect(
      rules.some(
        (rule) =>
          rule.selectorText.includes(':focus-within') &&
          rule.style.boxShadow.includes(lightTheme.colors.focusRing),
      ),
    ).toBe(true);
  });
});
