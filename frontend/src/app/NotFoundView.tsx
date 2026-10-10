import styled from 'styled-components';
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
  return (
    <Page>
      <Stack gap="md">
        <Title>Not found</Title>
        <ErrorCallout
          title="This page doesn't exist"
          detail="Check the address, or go to the queue."
          action={<TextLink to="/queue">Go to the queue</TextLink>}
        />
      </Stack>
    </Page>
  );
}
