import loadable from '@loadable/component';
import React from 'react';
import recentReleases from '../../../gen-src/release-notes-recent.json';

const latestReleasePath = Object.keys(recentReleases)[0];
const latestReleasePageName = latestReleasePath.substring(
  latestReleasePath.lastIndexOf('/') + 1,
);
const LatestReleaseNotesPage = loadable(() =>
  import(`./${latestReleasePageName}.mdx`),
);

export default (props: any) => {
  return (
    <LatestReleaseNotesPage
      {...props}
      uri={latestReleasePath}
      version={latestReleasePageName}
    />
  );
};
