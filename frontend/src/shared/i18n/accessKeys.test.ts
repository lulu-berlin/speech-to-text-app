import { describe, expect, it } from 'vitest';
import { shippedCatalogs } from '../../test/catalogs';
import { checkAccessKeys } from './accessKeys';

const SAVE = 'transcript.editor.save';
const DISCARD = 'transcript.editor.discard';

// Item 88, discovered 2026-11-12: the editor's shortcuts are the letters each locale
// underlines, so a translation must not make two buttons share one, underline anything but one
// letter, or pick a letter the field or the browser already owns with Ctrl/Cmd.
describe('the access key check', () => {
  it('reports a shared letter, an underline that is not one letter and a reserved letter', () => {
    expect(checkAccessKeys('xx', { [SAVE]: '<u>S</u>ave', [DISCARD]: '<u>S</u>top' })).toEqual([
      { locale: 'xx', key: DISCARD, rule: 'collision', detail: 'S' },
    ]);
    expect(checkAccessKeys('xx', { [SAVE]: 'Save', [DISCARD]: '<u>Di</u>scard' })).toEqual([
      { locale: 'xx', key: SAVE, rule: 'not-one-letter' },
      { locale: 'xx', key: DISCARD, rule: 'not-one-letter' },
    ]);
    // Ctrl+V pastes in the field; Ctrl+W closes the tab before the page sees it.
    expect(checkAccessKeys('xx', { [SAVE]: '<u>W</u>rite', [DISCARD]: '<u>V</u>oid' })).toEqual([
      { locale: 'xx', key: SAVE, rule: 'reserved', detail: 'W' },
      { locale: 'xx', key: DISCARD, rule: 'reserved', detail: 'V' },
    ]);
  });

  it('passes every shipped catalog', () => {
    const violations = Object.entries(shippedCatalogs()).flatMap(([locale, messages]) =>
      checkAccessKeys(locale, messages),
    );
    expect(violations).toEqual([]);
  });
});
