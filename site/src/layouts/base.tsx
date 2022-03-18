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

import * as styles from './base.module.less';
import flashAtHash from './flash-at-hash';
import jumpToHash from './jump-to-hash';

const { Content } = Layout;

configReveal({ ssrFadeout: true });

interface BaseLayoutProps extends RouteComponentProps {
  pageTitle?: string;
  pageDescription?: string;
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
  // Do not index redirect pages.
  const robots = `${redirectUrl ? 'noindex' : 'index'},follow`;

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
          siteUrl
        }
      }
    }
  `);

  const siteUrl = site.siteMetadata.siteUrl.replace(/\/+$/, '');
  const canonicalUrl = `${siteUrl}${props.location.pathname}`;
  const siteTitle = site.siteMetadata.title;
  const pageTitle = props.pageTitle || siteTitle;

  return (
    <>
      <Helmet title={pageTitle}>
        {redirectUrl ? (
          <>
            <meta httpEquiv="refresh" content={`0; url=${redirectUrl}`} />
            <script>{`
              window.location = '${redirectUrl}';
            `}</script>
          </>
        ) : null}

        <link rel="canonical" href={canonicalUrl} />
        <meta name="googlebot" content={robots} />
        <meta name="robots" content={robots} />
        <meta name="twitter:card" content="summary_large_image" />
        <meta name="twitter:creator" content="@armeria_project" />
        <meta name="twitter:site" content="@armeria_project" />
        <meta property="og:site_name" content={siteTitle} />
        <meta property="og:type" content="website" />
        <meta property="og:url" content={canonicalUrl} />
        <meta property="og:title" content={pageTitle} />
        {props.pageDescription ? (
          <meta property="og:description" content={props.pageDescription} />
        ) : null}
        <meta property="og:locale" content="en_US" />
        <meta property="og:image" content={`${siteUrl}/og-image.png`} />
        <meta property="og:image:width" content="1280" />
        <meta property="og:image:height" content="640" />
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
