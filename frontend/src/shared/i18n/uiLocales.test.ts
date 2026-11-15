import { describe, expect, it } from 'vitest';
import { CATALOGS } from './catalogs';
import { effectiveUiLocale, formatLocaleFor, SHIPPED_UI_LOCALES } from './uiLocales';
import { shippedCatalogs } from '../../test/catalogs';

// Cycle 19: formatting always has a region. The UI language is a language identification; the
// format locale uses the system's full tag when it names the same UI locale, and the locale's
// canonical region otherwise.
describe('formatLocaleFor', () => {
  it('derives a full format locale from the UI locale and the system tags', () => {
    expect(formatLocaleFor('en', ['en-GB'])).toBe('en-GB');
    expect(formatLocaleFor('de', ['en-US'])).toBe('de-DE');
    expect(formatLocaleFor('pt-BR', ['pt-PT'])).toBe('pt-BR');
    expect(formatLocaleFor('en', [])).toBe('en-US');
    expect(formatLocaleFor('en', ['sv-SE'])).toBe('en-US');
  });
});

// Discovered 2026-11-13: the picker reads CATALOGS, which the bundler fills from an
// `import.meta.glob`; while Vitest's transform cache holds the old file list, a catalog added
// afterwards stayed invisible there (`he` and `pt-BR` were hidden) although the disk-driven
// shipped-catalog check read it. The two sets must agree, so a stale cache fails loudly instead
// of silently withholding a language.
describe('the App language picker', () => {
  it('offers exactly the catalogs on disk', () => {
    const onDisk = Object.keys(shippedCatalogs()).sort();
    expect(onDisk.length).toBeGreaterThan(1);
    expect(Object.keys(CATALOGS).sort()).toEqual(onDisk);
    expect(SHIPPED_UI_LOCALES.map((locale) => locale.code).sort()).toEqual(onDisk);
  });
});

// The backend stores any string; a value that names no shipped catalog (including a prototype
// key, which `in` accepts) must fall back to the system language, never crash the render.
describe('a stored App language that ships no catalog', () => {
  it('falls back to the system language, including for prototype keys', () => {
    expect(effectiveUiLocale('constructor', ['de-DE'])).toBe('de');
    expect(effectiveUiLocale('toString', ['de-DE'])).toBe('de');
    expect(effectiveUiLocale('xx', ['de-DE'])).toBe('de');
  });
});
