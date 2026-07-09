import React, { useCallback } from 'react';
import ReactCookieConsent from 'react-cookie-consent';

import styles from './cookie-consent.module.css';

export const CookieConsent: React.FC = () => (
  <ReactCookieConsent
    declineButtonText="Opt out"
    containerClasses={styles.cookieConsentContainer}
    contentClasses={styles.cookieConsentContent}
    buttonClasses={`${styles.cookieConsentAcceptButton} button button--primary`}
    declineButtonClasses={`${styles.cookieConsentDeclineButton} button button--secondary`}
    sameSite="strict"
    enableDeclineButton
    disableButtonStyles
    acceptOnScroll
    onDecline={useCallback(() => {
      window.open('https://tools.google.com/dlpage/gaoptout/');
    }, [])}
  >
    This website uses anonymous cookies to ensure we provide you the best
    experience.
  </ReactCookieConsent>
);

export default CookieConsent;
