import { Link } from 'react-router';
import styled from 'styled-components';
import { ErrorCallout } from '../shared/ui/ErrorCallout';
import { focusRing } from '../shared/ui/focusRing';
import { Page, Stack } from '../shared/ui/layout';
import { PageHeading } from '../shared/ui/typography';

const Title = styled(PageHeading)`
  margin: 0;
`;

const QueueLink = styled(Link)`
  color: ${(props) => props.theme.colors.accent};

  ${focusRing}
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
          action={<QueueLink to="/queue">Go to the queue</QueueLink>}
        />
      </Stack>
    </Page>
  );
}
