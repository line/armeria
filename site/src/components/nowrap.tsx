import React from 'react';

import styles from './nowrap.module.less';

const NoWrap: React.FC = ({ children }) => (
  <span className={styles.nowrap}>{children}</span>
);

export default NoWrap;
