import React, { ReactNode } from 'react';

import styles from './browser-mockup.module.css';

interface BrowserMockupProps {
  children?: ReactNode;
}

const BrowserMockup: React.FC<BrowserMockupProps> = ({ children }) => (
  <div className={`${styles.browserMockup} ${styles.withUrl}`}>{children}</div>
);

export default BrowserMockup;
