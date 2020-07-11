import { globalHistory, RouteComponentProps } from '@reach/router';
import { BackTop, Layout } from 'antd';
import { graphql, useStaticQuery } from 'gatsby';
import React, { useEffect, useCallback } from 'react';
import CookieConsent from 'react-cookie-consent';
import { Helmet } from 'react-helmet';
// @ts-ignore
import configReveal from 'react-reveal/globals';

import Header from './header';
import Footer from './footer';

import styles from './base.module.less';
import flashAtHash from './flash-at-hash';
import jumpToHash from './jump-to-hash';

const { Content } = Layout;

configReveal({ ssrFadeout: true });

interface BaseLayoutProps extends RouteComponentProps {
  pageTitle?: string;
  contentClassName?: string;
  main?: boolean;
  extraSidebarContent?: React.ReactNode;
}

let firstRender = true;

const BaseLayout: React.FC<BaseLayoutProps> = (props) => {
  // Redirect to the new URL if at the old URL.
  const hrefMatches = props.location.href?.match(
    /:\/\/line\.github\.io\/armeria(.*)/,
  );
  let redirectUrl;
  if (hrefMatches && hrefMatches.length === 2) {
    redirectUrl = `https://armeria.dev${hrefMatches[1]}`;
    globalHistory.navigate(redirectUrl);
  }

  useEffect(() => {
    // Jump to hash or flash at hash only when rendering in a browser.
    if (typeof window !== 'undefined') {
      if (firstRender) {
        firstRender = false;
        jumpToHash(props.location.hash);
      } else {
        flashAtHash(props.location.hash);
      }
    }
  }, [props.location]);

  const { site } = useStaticQuery(graphql`
    query {
      site {
        siteMetadata {
          title
        }
      }
    }
  `);

  return (
    <>
      <Helmet title={props.pageTitle || site.siteMetadata.title}>
        {redirectUrl ? (
          <>
            <meta httpEquiv="refresh" content={`0; url=${redirectUrl}`} />
            <script>{`
              window.location = '${redirectUrl}';
            `}</script>
          </>
        ) : (
          ''
        )}
      </Helmet>
      <BackTop />
      <Layout className={styles.layout}>
        <Header
          location={props.location}
          extraSidebarContent={props.extraSidebarContent}
        />
        {props.main === false ? (
          <div className={`ant-layout-content ${props.contentClassName || ''}`}>
            {props.children}
          </div>
        ) : (
          <Content className={props.contentClassName || ''}>
            {props.children}
          </Content>
        )}
        <Footer />
      </Layout>
      <CookieConsent
        declineButtonText="Opt out"
        containerClasses={styles.cookieConsentContainer}
        contentClasses={styles.cookieConsentContent}
        buttonClasses={styles.cookieConsentAcceptButton}
        declineButtonClasses={styles.cookieConsentDeclineButton}
        sameSite="strict"
        enableDeclineButton
        disableButtonStyles
        acceptOnScroll
        onDecline={useCallback(() => {
          globalHistory.navigate('https://tools.google.com/dlpage/gaoptout/');
        }, [])}
      >
        This website uses anonymous cookies to ensure we provide you the best
        experience.
      </CookieConsent>
    </>
  );
};

export default BaseLayout;
