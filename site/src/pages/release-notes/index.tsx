import { withPrefix } from 'gatsby';
import React from 'react';

import Redirect from '../../layouts/redirect';
import recentReleases from '../../../gen-src/release-notes-recent.json';

export default () => {
  return <Redirect href={withPrefix(Object.keys(recentReleases)[0])} />;
};
