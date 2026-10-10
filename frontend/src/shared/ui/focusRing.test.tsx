import { fireEvent, screen } from '@testing-library/react';
import { MoreHorizontal } from 'lucide-react';
import { describe, expect, it } from 'vitest';
import { asRgb, styleRules } from '../../test/dom';
import { renderThemed } from '../../test/render';
import { lightTheme } from '../theme/theme';
import { Button } from './Button';
import { DropdownMenu } from './DropdownMenu';
import { IconButton } from './IconButton';
import { List } from './List';
import { ListRow } from './ListRow';
import { Slider } from './Slider';
import { Switch } from './Switch';

describe('the keyboard focus indicators', () => {
  it('show the focus ring token on every interactive primitive', async () => {
    renderThemed(
      <>
        <Button>Save</Button>
        <IconButton icon={MoreHorizontal} label="More" />
        <Switch checked={false} onCheckedChange={() => undefined} label="Pause the queue" />
        <Slider value={0} max={100} onChange={() => undefined} label="Seek" />
        <List>
          <ListRow>
            <button type="button">Row action</button>
          </ListRow>
        </List>
        <DropdownMenu
          trigger={<Button variant="secondary">Menu</Button>}
          items={[{ label: 'Pause' }]}
        />
      </>,
    );
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Menu' }), { button: 0 });
    await screen.findByRole('menu');

    const rules = styleRules().filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule);
    // Every :focus-visible rule in the sheet carries the focusRing token. Button,
    // IconButton, Switch and the slider thumb each contribute one.
    const focusVisible = rules.filter((rule) => rule.selectorText.includes(':focus-visible'));
    expect(focusVisible.length).toBeGreaterThanOrEqual(4);
    for (const rule of focusVisible) {
      expect(rule.style.boxShadow).toContain(lightTheme.colors.focusRing);
    }
    // The row shows the ring when its inner control is focused (focus-within).
    expect(
      rules.some(
        (rule) =>
          rule.selectorText.includes(':focus-within') &&
          rule.style.boxShadow.includes(lightTheme.colors.focusRing),
      ),
    ).toBe(true);
    // The menu item's keyboard indicator is its highlight fill.
    expect(
      rules.some(
        (rule) =>
          rule.selectorText.includes('[data-highlighted]') &&
          rule.style.backgroundColor === asRgb(lightTheme.colors.surface),
      ),
    ).toBe(true);
  });
});
