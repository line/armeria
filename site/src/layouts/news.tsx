import { WindowLocation } from '@reach/router';
import React from 'react';

import recentNews from '../../gen-src/news-recent.json';
import recentReleases from '../../gen-src/release-notes-recent.json';
import MdxLayout from './mdx';

interface NewsLayoutProps {
  location: WindowLocation;
  pageContext: any;
  pageTitle: string;
  version?: string;
  noEdit?: boolean;
}

const NewsLayout: React.FC<NewsLayoutProps> = props => {
  const newsLinks: { [version: string]: string } = {};
  const releaseLinks: { [version: string]: string } = {};
  const index = {
    root: {
      'Latest news items': '/news',
      'Latest release notes': '/release-notes',
      'Past news items': '/news/list',
      'Past release notes': '/release-notes/list',
    },
    'Recent news items': newsLinks,
    'Recent releases': releaseLinks,
  };
  Object.entries(recentNews).forEach(([pagePath, pageTitle]) => {
    newsLinks[pageTitle] = pagePath;
  });
  Object.entries(recentReleases).forEach(([pagePath, pageTitle]) => {
    releaseLinks[pageTitle] = pagePath;
  });

  return (
    <MdxLayout
      {...props}
      candidateMdxNodes={[]}
      index={index}
      prefix="news"
      pageTitle={props.pageTitle}
      pageTitleSuffix="Armeria news"
    >
      {props.children}
    </MdxLayout>
  );
};

export default NewsLayout;
