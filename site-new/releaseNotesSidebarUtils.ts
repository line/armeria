import type { SidebarsConfig } from '@docusaurus/plugin-content-docs';

const LATEST_DISPLAY_COUNT = 10;

const sidebars: SidebarsConfig = {
  releasenotesSidebar: [
    {
      type: 'html',
      value: 'Recent releases',
      defaultStyle: true,
      className: 'sidebar-title',
    },
    // Recent release notes are added here
    // '1.31.3',
    // '1.31.2',
    // ...
    {
      type: 'html',
      value: '<hr>',
      defaultStyle: true,
      className: 'sidebar-divider',
    },
    {
      type: 'html',
      value: 'Past releases',
      defaultStyle: true,
      className: 'sidebar-title',
    },
    // Past release note categories are added here
    // {
    //   type: 'category',
    //   label: 'Version 1',
    //   collapsed: true,
    //   link: {
    //     type: 'generated-index',
    //     title: 'Version 1',
    //   },
    //   items: ['1.28.4', '1.28.3'],
    // },
    {
      type: 'link',
      label: 'Even older versions',
      href: 'https://github.com/line/armeria/releases?after=armeria-0.80.0',
    },
  ],
};

function compareVersions(a: string, b: string): number {
  const aParts = a.split('.').map(Number);
  const bParts = b.split('.').map(Number);

  for (let i = 0; i < Math.max(aParts.length, bParts.length); i += 1) {
    const aPart = aParts[i] || 0;
    const bPart = bParts[i] || 0;

    if (aPart > bPart) return -1;
    if (aPart < bPart) return 1;
  }
  return 0;
}

function sortReleaseNoteSidebarItems(items: any[]): any[] {
  const updatedSidebar = [...(sidebars.releasenotesSidebar as any[])];

  const docItems = items.filter(
    (item) => item.type === 'doc' && !item.id.startsWith('index-version-'),
  );
  const sortedDocItems = docItems.sort((a, b) => compareVersions(a.id, b.id));

  const recentReleasesIndex = updatedSidebar.findIndex(
    (item) => item.type === 'html' && item.value === 'Recent releases',
  );

  if (recentReleasesIndex !== -1) {
    const recentReleases = sortedDocItems.slice(0, LATEST_DISPLAY_COUNT);
    const pastReleases = sortedDocItems.slice(LATEST_DISPLAY_COUNT);

    updatedSidebar.splice(recentReleasesIndex + 1, 0, ...recentReleases);

    const pastReleasesByMajorVersion: { [key: string]: any[] } = {};

    pastReleases.forEach((release) => {
      const majorVersion = release.id.split('.')[0];
      if (!pastReleasesByMajorVersion[majorVersion]) {
        pastReleasesByMajorVersion[majorVersion] = [];
      }
      pastReleasesByMajorVersion[majorVersion].push({
        type: 'doc',
        id: release.id,
      });
    });

    const pastReleasesIndex = updatedSidebar.findIndex(
      (item) => item.type === 'html' && item.value === 'Past releases',
    );

    if (pastReleasesIndex !== -1) {
      Object.entries(pastReleasesByMajorVersion).forEach(
        ([majorVersion, versionItems]) => {
          updatedSidebar.splice(pastReleasesIndex + 1, 0, {
            type: 'category',
            label: `Version ${majorVersion}`,
            collapsed: true,
            link: {
              type: 'doc',
              id: `index-version-${majorVersion}`,
            },
            items: versionItems,
          });
        },
      );
    }
  }

  return updatedSidebar;
}

export { compareVersions, sortReleaseNoteSidebarItems, LATEST_DISPLAY_COUNT };
