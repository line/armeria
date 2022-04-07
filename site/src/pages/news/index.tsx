import { withPrefix } from 'gatsby';
import React from 'react';

import Redirect from '../../layouts/redirect';
import recentNews from '../../../gen-src/news-recent.json';

export default () => {
  return <Redirect href={withPrefix(Object.keys(recentNews)[0])} />;
};
