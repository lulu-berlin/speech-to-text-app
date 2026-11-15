import { useEffect, type ReactNode } from 'react';
import { IntlProvider } from 'react-intl';
import { SettingsContext } from './SettingsContext';
import { CATALOGS } from '../shared/i18n/catalogs';
import { UiDirectionContext } from '../shared/i18n/uiDirection';
import {
  effectiveUiLocale,
  formatLocaleFor,
  systemLanguageTags,
  uiDirection,
} from '../shared/i18n/uiLocales';

/**
 * The app's message catalog (roadmap item 88): the ICU messages of `uiLocale`, over the English
 * catalog so a key the locale lacks still reads. A test passes `messages` to prove a screen
 * reads the catalog.
 */
export function LocaleProvider({
  children,
  messages,
  uiLocale = 'en',
  locale = 'en',
}: {
  children: ReactNode;
  /** Test overrides: the named entries replace the catalog's, the rest stay. */
  messages?: Record<string, string>;
  /** The UI locale whose catalog the screens read. */
  uiLocale?: string;
  /** The full BCP 47 tag Intl formats with: dates, numbers and plural categories follow it. */
  locale?: string;
}) {
  return (
    <IntlProvider
      locale={locale}
      defaultLocale="en"
      messages={{ ...CATALOGS['en'], ...CATALOGS[uiLocale], ...messages }}
    >
      {children}
    </IntlProvider>
  );
}

/**
 * The app's language from the stored settings (item 88): the App language when it ships a
 * catalog, the system's languages otherwise; a stored change switches the whole UI in place.
 * Rendered inside the settings provider.
 */
export function SettingsLocaleProvider({ children }: { children: ReactNode }) {
  const appLanguage = SettingsContext.useSelector(
    (state) => state.context.settings?.appLanguage ?? null,
  );
  const tags = systemLanguageTags();
  const uiLocale = effectiveUiLocale(appLanguage, tags);
  const direction = uiDirection(uiLocale);
  // The document's direction and language follow the UI locale (item 88's RTL item): every
  // screen inherits the direction, and the CSS logical properties and icons mirror with it.
  useEffect(() => {
    document.documentElement.lang = uiLocale;
    document.documentElement.dir = direction;
  }, [uiLocale, direction]);
  return (
    <UiDirectionContext.Provider value={direction}>
      <LocaleProvider uiLocale={uiLocale} locale={formatLocaleFor(uiLocale, tags)}>
        {children}
      </LocaleProvider>
    </UiDirectionContext.Provider>
  );
}
