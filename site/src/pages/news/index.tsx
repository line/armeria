import loadable from '@loadable/component';
import React from 'react';
import recentNews from '../../../gen-src/news-recent.json';

const latestNewsPath = Object.keys(recentNews)[0];
const latestNewsPageName = latestNewsPath.substring(
  latestNewsPath.lastIndexOf('/') + 1,
);
const LatestNewsPage = loadable(() => import(`./${latestNewsPageName}.mdx`));

export default (props: any) => <LatestNewsPage {...props} />;
