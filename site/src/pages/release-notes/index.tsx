import { withPrefix } from 'gatsby';
import React from 'react';

import Redirect from '../../layouts/redirect.tsx';
import recentReleases from '../../../gen-src/release-notes-recent.json';

export default (props: any) => <Redirect href={withPrefix(Object.keys(recentReleases)[0])} />;
