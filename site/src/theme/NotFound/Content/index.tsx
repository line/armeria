import React, { type ReactNode } from 'react';
import clsx from 'clsx';
import type { Props } from '@theme/NotFound/Content';
import Logo from '@site/src/components/logo';

import styles from './index.module.css';

const NotFoundContent = ({ className }: Props): ReactNode => {
  return (
    <main
      className={clsx(
        'container',
        'margin-vert--xl',
        styles.wrapper,
        className,
      )}
    >
      <div className={styles.main}>
        4
        <Logo
          className={styles.zero}
          width="auto"
          notext
          primaryColor="var(--ifm-font-color-base)"
          secondaryColor="var(--ifm-color-emphasis-500)"
          tertiaryColor="transparent"
          label="0"
        />
        4
      </div>
    </main>
  );
};

export default NotFoundContent;
