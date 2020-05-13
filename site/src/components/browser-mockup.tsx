import React from 'react';

import styles from './browser-mockup.module.less';

const BrowserMockup: React.FC = ({ children }) => (
  <div className={`${styles.browserMockup} ${styles.withUrl}`}>{children}</div>
);

export default BrowserMockup;
