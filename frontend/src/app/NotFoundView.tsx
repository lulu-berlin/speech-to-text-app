import styled from 'styled-components';
import { useMessages } from '../shared/i18n/useMessages';
import { ErrorCallout } from '../shared/ui/ErrorCallout';
import { Page, Stack } from '../shared/ui/layout';
import { TextLink } from '../shared/ui/TextLink';
import { PageHeading } from '../shared/ui/typography';

const Title = styled(PageHeading)`
  margin: 0;
`;

// An unknown address: the page heading, then the error callout with the way to the queue
// (item 25).
export function NotFoundView() {
  const t = useMessages();
  return (
    <Page>
      <Stack gap="md">
        <Title>{t('app.notFound.title')}</Title>
        <ErrorCallout
          title={t('app.notFound.errorTitle')}
          detail={t('app.notFound.detail')}
          action={<TextLink to="/queue">{t('app.notFound.action')}</TextLink>}
        />
      </Stack>
    </Page>
  );
}
