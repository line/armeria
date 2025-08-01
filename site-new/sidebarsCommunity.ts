import type { SidebarsConfig } from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  communitySidebar: [
    'index',
    'developer-guide',
    'code-of-conduct',
    'design-resources',
    // TODO: Add blog and community articles
    {
      type: 'link',
      label: 'LY Corporation Tech Blog',
      href: 'https://techblog.lycorp.co.jp/en',
    },
  ],
};

export default sidebars;
