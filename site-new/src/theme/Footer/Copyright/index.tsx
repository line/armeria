import React, { type ReactNode } from 'react';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import type { Props } from '@theme/Footer/Copyright';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import BrowserOnly from '@docusaurus/BrowserOnly';

dayjs.extend(relativeTime);

export default function FooterCopyright({ copyright }: Props): ReactNode {
  const { buildDate } = useDocusaurusContext().siteConfig.customFields;
  const buildDateObj = dayjs(buildDate as string);
  return (
    <div className="footer__copyright">
      © 2015-{buildDateObj.year()}, LY Corporation
      <br />
      <BrowserOnly>
        {() => (
          <>
            Page built{' '}
            <span title={buildDateObj.toString()}>
              {buildDateObj.fromNow()}
            </span>
          </>
        )}
      </BrowserOnly>
    </div>
  );
}
