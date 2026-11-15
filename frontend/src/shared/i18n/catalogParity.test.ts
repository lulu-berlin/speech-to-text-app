import { describe, expect, it } from 'vitest';
import { shippedCatalogs, UI_LOCALE_CODES } from '../../test/catalogs';
import { checkCatalog, checkShippedCatalogs } from './catalogParity';

// The reference: the English catalog's shapes. The broken locale below departs from it in
// each way the check must catch.
const reference: Record<string, string> = {
  greeting: 'Hello {name}',
  files: '{count, plural, one {# file} other {# files}}',
  rich: 'Read <link>more</link>',
  simple: 'Settings',
};

const broken: Record<string, string> = {
  greeting: 'Hallo {user}',
  files: '{count, plural, one {# Datei}}',
  rich: 'Lies <link>mehr',
  extra: 'Alt',
};

// A clean non-reference locale: different words, same placeholders and plural categories.
const german: Record<string, string> = {
  greeting: 'Hallo {name}',
  files: '{count, plural, one {# Datei} other {# Dateien}}',
  rich: 'Lies <link>mehr</link>',
  simple: 'Einstellungen',
};

describe('the catalog parity check', () => {
  it('catches a missing key, a stale key, a changed placeholder, invalid ICU and a missing plural category', () => {
    const violations = checkCatalog('en', reference, broken);
    expect(violations).toHaveLength(5);
    expect(violations).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ locale: 'en', key: 'simple', rule: 'missing-key' }),
        expect.objectContaining({ locale: 'en', key: 'extra', rule: 'stale-key' }),
        expect.objectContaining({ locale: 'en', key: 'greeting', rule: 'placeholder' }),
        expect.objectContaining({ locale: 'en', key: 'rich', rule: 'invalid-icu' }),
        expect.objectContaining({
          locale: 'en',
          key: 'files',
          rule: 'missing-plural-category',
          detail: 'other',
        }),
      ]),
    );

    // A locale equal to the reference and a clean translated locale both pass.
    expect(checkCatalog('en', reference, reference)).toEqual([]);
    expect(checkCatalog('de', reference, german)).toEqual([]);
  });

  it('reports a catalog for a locale the app does not ship, and checks every shipped one', () => {
    // Item 88, discovered 2026-11-12: the catalogs are read from disk and named after the UI
    // locales of docs/api/ui-locales.json, so a new language is checked without this file
    // changing, and a stray file is reported.
    expect(checkShippedCatalogs({ en: reference, de: german, xx: german }, ['en', 'de'])).toEqual([
      { locale: 'xx', key: 'xx.json', rule: 'unknown-locale' },
    ]);
    expect(checkShippedCatalogs({ en: reference, de: broken }, ['en', 'de'])).toHaveLength(5);

    expect(checkShippedCatalogs(shippedCatalogs(), UI_LOCALE_CODES)).toEqual([]);
  });
});
