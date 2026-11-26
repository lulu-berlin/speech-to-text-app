import { FormattedMessage } from 'react-intl';
import { IsolatedText } from './IsolatedText';

// A catalog sentence that names a file (item 76): the filename is an isolated element in its own
// direction, so it cannot reorder the translated sentence around it.
export function FileMessage({ id, filename }: { id: string; filename: string }) {
  return <FormattedMessage id={id} values={{ filename: <IsolatedText text={filename} /> }} />;
}
