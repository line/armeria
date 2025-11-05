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
  const fullPath = path.join(__dirname, 'src/content', directory);
  const files = await fs.readdir(fullPath);
  const filteredFiles = files.filter((file) => file.endsWith(extension));
  const identifiers = filteredFiles.map((file) =>
    path.basename(file, extension),
  );

  if (identifiers.length === 0) {
    throw new Error(
      `No files found in ${fullPath} with extension ${extension}.`,
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
    url: 'https://armeria.dev',
    // Set the /<baseUrl>/ pathname under which your site is served
    // For GitHub pages deployment, it is often '/<projectName>/'
    baseUrl: '/',

    // GitHub pages deployment config.
    // If you aren't using GitHub pages, you don't need these.
    organizationName: 'line', // Usually your GitHub org/user name.
    projectName: 'armeria', // Usually your repo name.

    onBrokenLinks: 'warn',

    markdown: {
        hooks: {
          onBrokenMarkdownImages: 'warn',
          onBrokenMarkdownLinks: 'warn',
        },
    },

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
            path: 'src/content/docs',
            editUrl: 'https://github.com/line/armeria/edit/main/site/',
            remarkPlugins: [remarkApiLink, remarkGithub],
          },
          blog: {
            path: 'src/content/news',
            routeBasePath: '/news',
            blogTitle: 'Armeria Newsletter',
            blogDescription:
              'The Armeria Newsletter provides the latest insights, updates, and best practices to help you maximize the potential of Armeria.',
            blogSidebarTitle: ' ',
            blogSidebarCount: 'ALL',
            showReadingTime: false,
            blogListComponent: '@site/src/components/news-redirect', // Redirect to the latest newsletter when accessing /news
            feedOptions: {
              type: ['rss', 'atom'],
              xslt: true,
            },
            editUrl: 'https://github.com/line/armeria/edit/main/site/',
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
          gtag: {
            trackingID: 'G-CY9YHLJ0K6',
            anonymizeIP: true,
          },
        } satisfies Preset.Options,
      ],
    ],

    themeConfig: {
      image: 'img/og-image.jpg',
      metadata: [
        {
          name: 'og:site_name',
          content: 'Armeria - Your go-to microservice framework',
        },
        { name: 'og:type', content: 'website' },
        { name: 'og:image:width', content: '1280' },
        { name: 'og:image:height', content: '640' },
        { name: 'twitter:site', content: '@armeria_project' },
        { name: 'twitter:creator', content: '@armeria_project' },
      ],
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
        // See src/theme/Footer/index.tsx for the footer implementation
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
          path: 'src/content/community',
          routeBasePath: 'community',
          sidebarPath: './sidebarsCommunity.ts',
          editUrl: 'https://github.com/line/armeria/edit/main/site/',
          remarkPlugins: [remarkApiLink, remarkGithub],
        },
      ],
      [
        '@docusaurus/plugin-content-docs',
        {
          id: 'release-notes',
          path: 'src/content/release-notes',
          routeBasePath: 'release-notes',
          async sidebarItemsGenerator({
            defaultSidebarItemsGenerator,
            ...args
          }) {
            const sidebarItems = await defaultSidebarItemsGenerator(args);
            return sortReleaseNoteSidebarItems(sidebarItems);
          },
          editUrl: 'https://github.com/line/armeria/edit/main/site/',
          remarkPlugins: [remarkApiLink, remarkGithub, remarkReleaseDate],
        },
      ],
      [
        '@docusaurus/plugin-content-blog',
        {
          id: 'blog',
          path: 'src/content/blog/en',
          routeBasePath: 'blog',
          blogTitle: 'Armeria Blog',
          blogDescription:
            'Discover stories, insights, and experiences in building modern microservices with Armeria — from gRPC to Spring Boot, with metrics, tracing, and more.',
          blogSidebarTitle: ' ',
          blogSidebarCount: 'ALL',
          showReadingTime: true,
          feedOptions: {
            // TODO
            type: ['rss', 'atom'],
            xslt: true,
          },
          editUrl: 'https://github.com/line/armeria/edit/main/site/',
          onInlineTags: 'warn',
          onInlineAuthors: 'warn',
          onUntruncatedBlogPosts: 'throw',
        },
      ],
      [
        '@docusaurus/plugin-content-blog',
        {
          id: 'blog-ja',
          path: 'src/content/blog/ja',
          routeBasePath: 'blog/ja',
          blogTitle: 'Armeria Blog',
          blogDescription:
            'Discover stories, insights, and experiences in building modern microservices with Armeria — from gRPC to Spring Boot, with metrics, tracing, and more.',
          blogSidebarTitle: ' ',
          blogSidebarCount: 'ALL',
          showReadingTime: true,
          feedOptions: {
            // TODO
            type: ['rss', 'atom'],
            xslt: true,
          },
          editUrl: 'https://github.com/line/armeria/edit/main/site/',
          onInlineTags: 'warn',
          onInlineAuthors: 'warn',
          onUntruncatedBlogPosts: 'throw',
        },
      ],
      [
        '@docusaurus/plugin-content-blog',
        {
          id: 'blog-ko',
          path: 'src/content/blog/ko',
          routeBasePath: 'blog/ko',
          blogTitle: 'Armeria Blog',
          blogDescription:
            'Discover stories, insights, and experiences in building modern microservices with Armeria — from gRPC to Spring Boot, with metrics, tracing, and more.',
          blogSidebarTitle: ' ',
          blogSidebarCount: 'ALL',
          showReadingTime: true,
          feedOptions: {
            // TODO
            type: ['rss', 'atom'],
            xslt: true,
          },
          editUrl: 'https://github.com/line/armeria/edit/main/site/',
          onInlineTags: 'warn',
          onInlineAuthors: 'warn',
          onUntruncatedBlogPosts: 'throw',
        },
      ],
      [
        '@docusaurus/plugin-client-redirects',
        {
          redirects: [
            {
              from: '/release-notes',
              to: await getLatestReleaseNotePath(),
            },
          ],
        },
      ],
      [
        '@docusaurus/plugin-pwa',
        {
          debug: false,
          offlineModeActivationStrategies: [
            'appInstalled',
            'standalone',
            'queryString',
          ],
          pwaHead: [
            {
              tagName: 'link',
              rel: 'manifest',
              href: '/manifest.json',
            },
          ],
        },
      ],
      './src/plugins/tutorial.ts',
      [
        './src/plugins/short-url.ts',
        {
          shortUrls: [
            {
              name: 'discord',
              href: 'https://discord.gg/7FH8c6npmg',
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
      latestNewsPath: await getLatestNewsletterPath(),
      buildDate: new Date().toISOString(),
    },
  };
  return config;
}
