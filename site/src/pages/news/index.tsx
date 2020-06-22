import { withPrefix } from 'gatsby';
import React from 'react';

import Redirect from '../../layouts/redirect.tsx';
import recentNews from '../../../gen-src/news-recent.json';

export default (props: any) => <Redirect href={withPrefix(Object.keys(recentNews)[0])} />;
