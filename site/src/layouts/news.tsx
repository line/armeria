import { RouteComponentProps } from '@reach/router';
import { Link } from 'gatsby';
import React from 'react';

import recentNews from '../../gen-src/news-recent.json';
import recentReleases from '../../gen-src/release-notes-recent.json';
import { Tip } from '../components/alert';
import MdxLayout from './mdx';
import getPagePath from './page-path';

const latestNewsHref = Object.keys(recentNews)[0];
const latestVersionHref = Object.keys(recentReleases)[0];

interface NewsLayoutProps extends RouteComponentProps {
  pageContext: any;
  pageTitle: string;
  version?: string;
  noEdit?: boolean;
}

const NewsLayout: React.FC<NewsLayoutProps> = (props) => {
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

  const path = getPagePath(props.location);

  return (
    <MdxLayout
      {...props}
      candidateMdxNodes={[]}
      index={index}
      prefix="news"
      pageTitle={props.pageTitle}
      pageTitleSuffix="Armeria news"
    >
      <Tip>
        {path !== latestNewsHref ? (
          <>
            You&apos;re seeing an old newsletter. Check out{' '}
            <Link to={latestNewsHref}>the latest news</Link> from our community!
          </>
        ) : (
          <>
            Check out the new features and fixes in{' '}
            <Link to={latestVersionHref}>the release notes</Link>!
          </>
        )}
      </Tip>

      {props.children}
    </MdxLayout>
  );
};

export default NewsLayout;
