import type { SidebarsConfig } from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  releasenotesSidebar: [
    // Release note categories are added here
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
  const newSideBars = [...(sidebars.releasenotesSidebar as any[])];

  const docItems = items.filter(
    (item) => item.type === 'doc' && !item.id.startsWith('index-version-'),
  );
  const sortedDocItems = docItems.sort((a, b) => compareVersions(a.id, b.id));

  const releasesByMajorVersion: { [key: string]: any[] } = {};

  sortedDocItems.forEach((release) => {
    const majorVersion = release.id.split('.')[0];
    if (!releasesByMajorVersion[majorVersion]) {
      releasesByMajorVersion[majorVersion] = [];
    }
    releasesByMajorVersion[majorVersion].push({
      type: 'doc',
      id: release.id,
    });
  });

  Object.entries(releasesByMajorVersion).forEach(
    ([majorVersion, versionItems]) => {
      newSideBars.splice(0, 0, {
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

  return newSideBars;
}

export { compareVersions, sortReleaseNoteSidebarItems };
