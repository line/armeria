import { RouteComponentProps } from '@reach/router';
import { Typography } from 'antd';
import React from 'react';

import recentNews from '../../gen-src/news-recent.json';
import recentReleases from '../../gen-src/release-notes-recent.json';
import MdxLayout from './mdx';
import getPagePath from './page-path';

const { Title } = Typography;

interface ReleaseNotesLayoutProps extends RouteComponentProps {
  pageContext: any;
  pageTitle: string;
  version?: string;
  noEdit?: boolean;
}

const ReleaseNotesLayout: React.FC<ReleaseNotesLayoutProps> = (props) => {
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
  let currentVersion =
    props.version || path.substring(path.lastIndexOf('/') + 1);

  if (!currentVersion.match(/^[0-9]/)) {
    currentVersion = undefined;
  }

  return (
    <MdxLayout
      {...props}
      candidateMdxNodes={[]}
      index={index}
      prefix="release-notes"
      pageTitle={
        currentVersion ? `${currentVersion} release notes` : props.pageTitle
      }
      pageTitleSuffix="Armeria release notes"
    >
      {currentVersion ? (
        <Title id="release-notes" level={1}>
          <a
            href="#release-notes"
            aria-label="release notes permalink"
            className="anchor before"
          >
            <svg
              aria-hidden="true"
              focusable="false"
              height="16"
              version="1.1"
              viewBox="0 0 16 16"
              width="16"
            >
              <path
                fillRule="evenodd"
                d="M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"
              />
            </svg>
          </a>
          {currentVersion} release notes
        </Title>
      ) : (
        ''
      )}
      {props.children}
    </MdxLayout>
  );
};

export default ReleaseNotesLayout;
