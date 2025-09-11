import React, { type ReactNode } from 'react';
import Layout from '@theme-original/Layout';
import type LayoutType from '@theme/Layout';
import type { WrapperProps } from '@docusaurus/types';

import StarBegging from '@site/src/components/star-begging';
import CookieConsent from '@site/src/components/cookie-consent';

type Props = WrapperProps<typeof LayoutType>;

export default function LayoutWrapper(props: Props): ReactNode {
  return (
    <>
      <Layout {...props} />
      <StarBegging />
      <CookieConsent />
    </>
  );
}
