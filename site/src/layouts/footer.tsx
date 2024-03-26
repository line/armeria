import { Layout, Typography } from 'antd';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { graphql, useStaticQuery } from 'gatsby';
import { OutboundLink } from 'gatsby-plugin-google-analytics';
import React from 'react';

import Logo from '../components/logo';
import Mailchimp from '../components/mailchimp';
import * as styles from './footer.module.less';

const { Paragraph } = Typography;
const { Footer } = Layout;

dayjs.extend(relativeTime);

export default () => {
  const {
    currentBuildDate: { currentDate },
  } = useStaticQuery(graphql`
    query {
      currentBuildDate {
        currentDate
      }
    }
  `);

  const buildDate = dayjs(currentDate);

  return (
    <div className={styles.footerWrapper}>
      <Footer className={styles.footer}>
        <div className={styles.newsletterForm}>
          <Paragraph>Sign up for our newsletters:</Paragraph>
          <Mailchimp />
        </div>
        <div className={styles.copyright}>
          <div>
            &copy; 2015-{buildDate.year()}, LY Corporation
            <br />
            {typeof window !== 'undefined' ? (
              <>
                Page built{' '}
                <span title={buildDate.toString()}>{buildDate.fromNow()}</span>
              </>
            ) : (
              ''
            )}
            <br />
            <OutboundLink href="https://terms.line.me/line_rules?lang=en">
              Privacy policy
            </OutboundLink>
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
      </Footer>
    </div>
  );
};
