import { useIntl } from 'react-intl';
import { ApiError, errorMessage, isTransportError } from '../api/types';

/**
 * A failed action's line: the transport message already says the request did not land, so
 * the row's own prefix would say it twice; a server answer keeps the prefix.
 */
export function failureLine(
  prefix: string,
  error: unknown,
  text: (error: unknown) => string,
): string {
  return isTransportError(error) ? text(error) : `${prefix}: ${text(error)}`;
}

/**
 * A refusal's text by its code (roadmap item 88): the catalog's `error.<code>` when it has
 * one, the server's English message otherwise.
 */
export function useErrorText() {
  const intl = useIntl();
  return (error: unknown): string => {
    if (!(error instanceof ApiError)) {
      return errorMessage(error);
    }
    const id = `error.${error.code}`;
    return intl.messages[id] === undefined ? error.message : intl.formatMessage({ id });
  };
}
