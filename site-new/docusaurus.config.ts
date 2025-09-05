import fs from 'fs/promises';
import path from 'path';
import { themes as prismThemes } from 'prism-react-renderer';
import remarkGithub from 'remark-github';
import type { Config } from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';
import remarkApiLink from './src/remark/remark-api-link';
import remarkReleaseDate from './src/remark/remark-release-date';
import {
  compareVersions,
  sortReleaseNoteSidebarItems,
} from './releaseNotesSidebarUtils';

// This runs in Node.js - Don't use client-side code here (browser APIs, JSX...)

async function getLatestFilePath(
  directory: string,
  sortFunc: (a: string, b: string) => number,
): Promise<string> {
  const extension = '.mdx';
  const directoryPath = path.join(__dirname, directory);
  const files = await fs.readdir(directoryPath);
  const filteredFiles = files.filter((file) => file.endsWith(extension));
  const identifiers = filteredFiles.map((file) =>
    path.basename(file, extension),
  );

  if (identifiers.length === 0) {
    throw new Error(
      `No files found in ${directory} with extension ${extension}.`,
    );
  }

  const latestIdentifier = identifiers.sort(sortFunc)[0];
  return `/${directory}/${latestIdentifier}`;
}

async function getLatestNewsletterPath() {
  return getLatestFilePath('news', (a, b) => b.localeCompare(a));
}

async function getLatestReleaseNotePath() {
  return getLatestFilePath('release-notes', compareVersions);
}

export default async function createConfigAsync() {
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
              require.resolve('react-tweet/theme.css'),
            ],
          },
        } satisfies Preset.Options,
      ],
    ],

    themeConfig: {
      image: 'img/og-image.jpg',
      navbar: {
        style: 'dark',
        logo: {
          alt: 'Armeria Logo',
          src: 'img/armeria_logo_horiz.svg',
        },
        items: [
          {
            type: 'dropdown',
            label: 'News',
            position: 'left',
            items: [
              {
                label: 'Newsletter',
                to: await getLatestNewsletterPath(),
              },
              {
                label: 'Release notes',
                to: await getLatestReleaseNotePath(),
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
            type: 'docSidebar',
            sidebarId: 'communitySidebar',
            docsPluginId: 'community',
            position: 'left',
            label: 'Community',
          },
          {
            label: 'Blog',
            position: 'left',
            to: '/blog',
          },
          {
            href: 'https://github.com/line/armeria',
            position: 'right',
            className: 'header-github-link',
            'aria-label': 'GitHub repository',
          },
          {
            to: '/s/discord',
            position: 'right',
            className: 'header-discord-link',
            'aria-label': 'Discord',
          },
          {
            href: 'https://x.com/armeria_project',
            position: 'right',
            className: 'header-x-link',
            'aria-label': 'X',
          },
        ],
      },
      footer: {
        style: 'dark',
        links: [
          {
            label: 'LY Corporation Tech Blog',
            href: 'https://techblog.lycorp.co.jp/en',
          },
          {
            label: 'Privacy policy',
            href: 'https://terms.line.me/line_rules?lang=en',
          },
        ],
        copyright: `© ${new Date().getFullYear()} LY Corporation`, // See src/theme/Footer/Copyright/index.tsx
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
        '@docusaurus/plugin-content-docs',
        {
          id: 'release-notes',
          path: 'release-notes',
          routeBasePath: 'release-notes',
          async sidebarItemsGenerator({
            defaultSidebarItemsGenerator,
            ...args
          }) {
            const sidebarItems = await defaultSidebarItemsGenerator(args);
            return sortReleaseNoteSidebarItems(sidebarItems);
          },
          remarkPlugins: [remarkApiLink, remarkGithub, remarkReleaseDate],
        },
      ],
      [
        '@docusaurus/plugin-content-blog',
        {
          id: 'blog',
          path: 'blog/en',
          routeBasePath: 'blog',
          postsPerPage: 12,
          showReadingTime: true,
          blogSidebarCount: 'ALL',
          blogSidebarTitle: ' ',
          // authorsMapPath: '../blog/authors.yaml',
          editUrl: 'https://github.com/line/armeria/edit/main/site/',
          // TODO
        },
      ],
      [
        '@docusaurus/plugin-content-blog',
        {
          id: 'blog-ja',
          path: 'blog/ja',
          routeBasePath: 'blog/ja',
          postsPerPage: 12,
          showReadingTime: true,
          blogSidebarCount: 10,
          blogSidebarTitle: ' ',
          // authorsMapPath: '../blog/authors.yaml',
          editUrl: 'https://github.com/line/armeria/edit/main/site/',
          // TODO
        },
      ],
      [
        '@docusaurus/plugin-content-blog',
        {
          id: 'blog-ko',
          path: 'blog/ko',
          routeBasePath: 'blog/ko',
          postsPerPage: 12,
          showReadingTime: true,
          blogSidebarCount: 10,
          blogSidebarTitle: ' ',
          // authorsMapPath: '../blog/authors.yaml',
          editUrl: 'https://github.com/line/armeria/edit/main/site/',
          // TODO
        },
      ],
      [
        '@docusaurus/plugin-client-redirects',
        {
          redirects: [
            {
              from: '/s/discord',
              to: 'https://discord.gg/7FH8c6npmg',
            },
            {
              from: '/news',
              to: await getLatestNewsletterPath(),
            },
            {
              from: '/release-notes',
              to: await getLatestReleaseNotePath(),
            },
          ],
        },
      ],
    ],

    future: {
      v4: true,
    },

    customFields: {
      latestReleaseNotePath: await getLatestReleaseNotePath(),
      buildDate: new Date().toISOString(),
    },
  };
  return config;
}
