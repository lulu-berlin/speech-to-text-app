import { useIntl } from 'react-intl';

/**
 * The message helper the views share (roadmap item 88): `t(id)` reads the catalog, and
 * `t(id, values)` fills its ICU placeholders.
 */
export function useMessages() {
  const intl = useIntl();
  return (id: string, values?: Record<string, string | number>) =>
    intl.formatMessage({ id }, values);
}
