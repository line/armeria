import React from 'react';

import styles from './shadow.module.less';

const Shadow: React.FC = ({ children }) => (
  <div className={styles.shadowWrapper}>
    <div className={styles.shadow}>{children}</div>
  </div>
);

export default Shadow;
