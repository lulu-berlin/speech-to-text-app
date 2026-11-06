// The shared UI text (ADR 0066): the same `ui-text/en.json` the Rust clients compile in, so the
// browser and the terminal show one set of words.

import en from '../../../../ui-text/en.json';

/** The locale every frontend ships today; roadmap item 88 adds the others and their selection. */
export type Locale = 'en';

/** Every key the catalog defines; a typo in `t()` fails the type check. */
export type TextKey = keyof typeof en;

const catalogs: Record<Locale, Record<string, string>> = { en };

/**
 * The text for `key`: the locale's template, else the default locale's, else the key itself - so a
 * missing entry shows what is missing instead of vanishing. `{name}` placeholders take `params`.
 */
export function t(
  key: TextKey,
  params: Record<string, string | number> = {},
  locale: Locale = 'en',
): string {
  const template = catalogs[locale][key] ?? en[key];
  return String(template).replace(/\{(\w+)\}/g, (placeholder, name: string) => {
    const value = params[name];
    return value === undefined ? placeholder : String(value);
  });
}
