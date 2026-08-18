import React, { ReactNode } from 'react';

import styles from './nowrap.module.css';

interface NoWrapProps {
  children?: ReactNode;
}

const NoWrap: React.FC<NoWrapProps> = ({ children }) => (
  <span className={styles.nowrap}>{children}</span>
);

export default NoWrap;
