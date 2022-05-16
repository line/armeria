import React from 'react';

import * as styles from './nowrap.module.less';

const NoWrap: React.FC = ({ children }) => (
  <span className={styles.nowrap}>{children}</span>
);

export default NoWrap;
