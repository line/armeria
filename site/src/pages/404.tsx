import { RouteComponentProps } from '@reach/router';
import React from 'react';

import BaseLayout from '../layouts/base';

import * as styles from './404.module.less';
import Logo from '../components/logo';

const NotFound: React.FC<RouteComponentProps> = (props) => (
  <BaseLayout
    location={props.location}
    pageTitle="Not found"
    contentClassName={styles.wrapper}
  >
    <div className={styles.main}>
      4
      <Logo
        className={styles.zero}
        width="auto"
        notext
        primaryColor="rgba(255, 255, 255, 1.0)"
        secondaryColor="rgba(255, 255, 255, 0.55)"
        tertiaryColor="transparent"
        label="0"
      />
      4
    </div>
  </BaseLayout>
);

export default NotFound;
