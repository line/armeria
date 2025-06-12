import { themes as prismThemes } from 'prism-react-renderer';
import remarkGithub from 'remark-github';
import type { Config } from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';
import remarkApiLink from './src/remark/remark-api-link';

// This runs in Node.js - Don't use client-side code here (browser APIs, JSX...)

const config: Config = {
  title: 'Armeria',
  tagline: 'Build a reactive microservice at your pace, not theirs.',
  favicon: 'img/favicon.svg',

  // Set the production url of your site here
  url: 'https://your-docusaurus-site.example.com',
  // Set the /<baseUrl>/ pathname under which your site is served
  // For GitHub pages deployment, it is often '/<projectName>/'
  baseUrl: '/',

  // GitHub pages deployment config.
  // If you aren't using GitHub pages, you don't need these.
  organizationName: 'line', // Usually your GitHub org/user name.
  projectName: 'armeria', // Usually your repo name.

  onBrokenLinks: 'throw',
  onBrokenMarkdownLinks: 'warn',

  // Even if you don't use internationalization, you can use this field to set
  // useful metadata like html lang. For example, if your site is Chinese, you
  // may want to replace "en" with "zh-Hans".
  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      {
        docs: {
          sidebarPath: './sidebars.ts',
          editUrl: 'https://github.com/line/armeria/edit/main/site/',
          remarkPlugins: [remarkApiLink, remarkGithub],
        },
        blog: {
          routeBasePath: '/news',
          path: 'news',
          blogTitle: 'Armeria Newsletter',
          blogDescription:
            'The Armeria Newsletter provides the latest insights, updates, and best practices to help you maximize the potential of Armeria.',
          blogSidebarTitle: ' ',
          blogSidebarCount: 'ALL',
          showReadingTime: false,
          feedOptions: {
            type: ['rss', 'atom'],
            xslt: true,
          },
          editUrl: 'https://github.com/line/armeria/edit/main/site/',
          // Useful options to enforce blogging best practices
          onInlineTags: 'warn',
          onInlineAuthors: 'warn',
          onUntruncatedBlogPosts: 'warn',
          remarkPlugins: [remarkApiLink, remarkGithub],
        },
        theme: {
          customCss: [
            './src/css/custom.css',
            './src/css/antd.css',
            './src/css/react-tweet.css',
          ],
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    image: 'img/og-image.jpg',
    navbar: {
      title: 'Armeria',
      logo: {
        alt: 'Armeria Logo',
        src: 'img/logo.svg',
      },
      items: [
        {
          type: 'dropdown',
          label: 'News',
          position: 'left',
          items: [
            {
              label: 'Newsletter',
              to: '/news',
            },
            {
              label: 'Release notes',
              to: '/release-notes',
            },
          ],
        },
        {
          type: 'docSidebar',
          sidebarId: 'docsSidebar',
          position: 'left',
          label: 'Documentation',
        },
        {
          to: '/community',
          label: 'Community',
          position: 'left',
        },
        {
          href: 'https://github.com/facebook/docusaurus',
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Docs',
          items: [
            {
              label: 'Tutorial',
              to: '/docs/intro',
            },
          ],
        },
        {
          title: 'Community',
          items: [
            {
              label: 'Stack Overflow',
              href: 'https://stackoverflow.com/questions/tagged/docusaurus',
            },
            {
              label: 'Discord',
              href: 'https://discordapp.com/invite/docusaurus',
            },
            {
              label: 'X',
              href: 'https://x.com/docusaurus',
            },
          ],
        },
        {
          title: 'More',
          items: [
            {
              label: 'Blog',
              to: '/blog',
            },
            {
              label: 'GitHub',
              href: 'https://github.com/facebook/docusaurus',
            },
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} My Project, Inc. Built with Docusaurus.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: [
        // Base set of supported languages: https://github.com/FormidableLabs/prism-react-renderer/blob/master/packages/generate-prism-languages/index.ts#L9-L26
        'bash',
        'groovy',
        'http',
        'java',
        'javascript',
        'protobuf',
        'scala',
        'shell-session',
      ],
    },
    docs: {
      sidebar: {
        hideable: true,
        autoCollapseCategories: true,
      },
    },
  } satisfies Preset.ThemeConfig,

  plugins: [
    [
      '@docusaurus/plugin-content-docs',
      {
        id: 'community',
        path: 'community',
        routeBasePath: 'community',
        sidebarPath: './sidebarsCommunity.ts',
      },
    ],
    './src/plugins/tutorial.ts',
    [
      '@docusaurus/plugin-client-redirects',
      {
        redirects: [
          {
            from: '/s/discord',
            to: 'https://discord.gg/7FH8c6npmg',
          },
        ],
      },
    ],
  ],
};

export default config;
