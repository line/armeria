import { Typography } from 'antd';
import React, { useCallback } from 'react';

import { OutboundLink } from 'gatsby-plugin-google-analytics';
import ListAllLayout from '../../layouts/list-all';
import ReleaseNotesLayout from '../../layouts/release-notes';

import allReleases from '../../../gen-src/release-notes-all.json';

const { Title, Paragraph } = Typography;

export default (props: any) => {
  const allPaths = Object.keys(allReleases);
  function pagePathToVersion(pagePath: string) {
    const lastSlashIndex = pagePath.lastIndexOf('/');
    if (lastSlashIndex >= 0) {
      return pagePath.substring(lastSlashIndex + 1);
    }
    return pagePath;
  }

  return (
    <ListAllLayout
      {...props}
      pageTitle="Release notes for all past versions"
      allItems={allReleases}
      layout={ReleaseNotesLayout}
      grouper={useCallback((pagePath) => {
        const version = pagePathToVersion(pagePath);
        const firstDotIndex = version.indexOf('.');
        const majorVersion =
          firstDotIndex >= 0 ? version.substring(0, firstDotIndex) : version;
        return `Version ${majorVersion}`;
      }, [])}
    >
      <Title id="even-older-versions" level={2}>
        Even older versions
      </Title>
      <Paragraph>
        See{' '}
        <OutboundLink
          href={`https://github.com/line/armeria/releases?after=armeria-${pagePathToVersion(
            allPaths[allPaths.length - 1],
          )}`}
        >
          Github releases page
        </OutboundLink>
        .
      </Paragraph>
    </ListAllLayout>
  );
};
