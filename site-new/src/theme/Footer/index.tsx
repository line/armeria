import React from 'react';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import BrowserOnly from '@docusaurus/BrowserOnly';
import Link from '@docusaurus/Link';
import Logo from '@site/src/components/logo';
import Mailchimp from '@site/src/components/mailchimp';

import styles from './index.module.css';

dayjs.extend(relativeTime);

export default () => {
  const { buildDate } = useDocusaurusContext().siteConfig.customFields;
  const buildDateObj = dayjs(buildDate as string);

  return (
    <footer className={`${styles.footer} footer footer--dark`}>
      <div className="container container--fluid">
        <div className="row">
          <div className={`${styles.newsletterForm} col`}>
            <p>Sign up for our newsletters:</p>
            <Mailchimp />
          </div>
          <div className={`${styles.copyright} col`}>
            <div>
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
              <br />
              <Link href="https://terms.line.me/line_rules?lang=en">
                Privacy policy
              </Link>
            </div>
            <div>
              <Logo
                notext
                width="4rem"
                primaryColor="rgba(255, 255, 255, 1.0)"
                secondaryColor="rgba(255, 255, 255, 0.55)"
                tertiaryColor="transparent"
              />
            </div>
          </div>
        </div>
      </div>
    </footer>
  );
};
