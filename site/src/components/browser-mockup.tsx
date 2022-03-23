import React from 'react';

import * as styles from './browser-mockup.module.less';

const BrowserMockup: React.FC = ({ children }) => (
  <div className={`${styles.browserMockup} ${styles.withUrl}`}>{children}</div>
);

export default BrowserMockup;
