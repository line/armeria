import React, { type ReactNode } from 'react';
import Content from '@theme-original/DocItem/Content';
import type ContentType from '@theme/DocItem/Content';
import type { WrapperProps } from '@docusaurus/types';
/* Swizzling starts */
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import Link from '@docusaurus/Link';
import { useLocation } from '@docusaurus/router';
import Admonition from '@theme/Admonition';
/* Swizzling ends */

type Props = WrapperProps<typeof ContentType>;

/* Swizzling starts */
const OldVersion = ({ to }: any): ReactNode => {
  return (
    <div>
      <Admonition type="info">
        <p>
          You&apos;re seeing the release note of an old version. Check out{' '}
          <Link to={String(to)}>the latest release note.</Link>
        </p>
      </Admonition>
    </div>
  );
};
/* Swizzling ends */

// eslint-disable-next-line react/function-component-definition
export default function ContentWrapper(props: Props): ReactNode {
  /* Swizzling starts */
  const { latestReleaseNotePath } =
    useDocusaurusContext().siteConfig.customFields;
  const location = useLocation();
  const releaseNotePathRegex = /^\/release-notes\/[0-9.]+$/;
  const isOldVersion =
    releaseNotePathRegex.test(location.pathname) &&
    location.pathname !== latestReleaseNotePath;
  /* Swizzling ends */

  return (
    <>
      {/* Swizzling starts */}
      {isOldVersion && <OldVersion to={latestReleaseNotePath} />}
      {/* Swizzling ends */}
      <Content {...props} />
    </>
  );
}
