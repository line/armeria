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
            blogSidebarTitle: ' ',
            blogSidebarCount: 'ALL',
            showReadingTime: false,
            blogListComponent: '@site/src/components/news-redirect', // Redirect to the latest newsletter when accessing /news
            feedOptions: {
              title: 'Armeria Newsletter',
              description: '',
              copyright: `© 2015-${new Date().getFullYear()}, LY Corporation`,
              type: ['rss'],
              xslt: false,
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

    headTags: [
      {
        tagName: 'link',
        attributes: {
          rel: 'alternate',
          type: 'application/rss+xml',
          href: '/rss.xml',
          title: 'Armeria RSS Feed',
        },
      },
    ],

    themeConfig: {
      image: 'img/og-image.png',
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
      // TODO: enable Algolia search when the index is ready.
      // algolia: {
      //   appId: 'TASSHENNEI',
      //   apiKey: '1defe0560dbb853243bd0cccce18127f',
      //   indexName: 'Armeria Crawler',
      //   contextualSearch: true,
      //   searchPagePath: 'search',
      // },
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
          blogSidebarTitle: ' ',
          blogSidebarCount: 'ALL',
          showReadingTime: true,
          feedOptions: {
            title: 'Armeria Blog',
            description: '',
            copyright: `© 2015-${new Date().getFullYear()}, LY Corporation`,
            type: ['rss'],
            xslt: false,
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
          blogSidebarTitle: ' ',
          blogSidebarCount: 'ALL',
          showReadingTime: true,
          feedOptions: {
            title: 'Armeria Blog - Japanese',
            description: '',
            copyright: `© 2015-${new Date().getFullYear()}, LY Corporation`,
            type: ['rss'],
            xslt: false,
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
          blogSidebarTitle: ' ',
          blogSidebarCount: 'ALL',
          showReadingTime: true,
          feedOptions: {
            title: 'Armeria Blog - Korean',
            description: '',
            copyright: `© 2015-${new Date().getFullYear()}, LY Corporation`,
            type: ['rss'],
            xslt: false,
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

            // Below are legacy redirects from the old site structure to the new one.
            {
              from: '/release-notes/list',
              to: await getLatestReleaseNotePath(),
            },
            {
              from: '/news/list',
              to: '/news/archive',
            },
            {
              from: '/docs/server-basics',
              to: '/docs/server/basics',
            },
            {
              from: '/docs/server-decorator',
              to: '/docs/server/decorator',
            },
            {
              from: '/docs/server-grpc',
              to: '/docs/server/grpc',
            },
            {
              from: '/docs/server-thrift',
              to: '/docs/server/thrift',
            },
            {
              from: '/docs/server-graphql',
              to: '/docs/server/graphql',
            },
            {
              from: '/docs/server-docservice',
              to: '/docs/server/docservice',
            },
            {
              from: '/docs/server-annotated-service',
              to: '/docs/server/annotated-service',
            },
            {
              from: '/docs/server-http-file',
              to: '/docs/server/http-file',
            },
            {
              from: '/docs/server-servlet',
              to: '/docs/server/servlet',
            },
            {
              from: '/docs/server-access-log',
              to: '/docs/server/access-log',
            },
            {
              from: '/docs/server-cors',
              to: '/docs/server/cors',
            },
            {
              from: '/docs/server-sse',
              to: '/docs/server/sse',
            },
            {
              from: '/docs/server-service-registration',
              to: '/docs/server/service-registration',
            },
            {
              from: '/docs/server-multipart',
              to: '/docs/server/multipart',
            },
            {
              from: '/docs/server-timeouts',
              to: '/docs/server/timeouts',
            },
            {
              from: '/docs/client-http',
              to: '/docs/client/http',
            },
            {
              from: '/docs/client-thrift',
              to: '/docs/client/thrift',
            },
            {
              from: '/docs/client-grpc',
              to: '/docs/client/grpc',
            },
            {
              from: '/docs/client-factory',
              to: '/docs/client/factory',
            },
            {
              from: '/docs/client-decorator',
              to: '/docs/client/decorator',
            },
            {
              from: '/docs/client-retrofit',
              to: '/docs/client/retrofit',
            },
            {
              from: '/docs/client-custom-http-headers',
              to: '/docs/client/custom-http-headers',
            },
            {
              from: '/docs/client-timeouts',
              to: '/docs/client/timeouts',
            },
            {
              from: '/docs/client-retry',
              to: '/docs/client/retry',
            },
            {
              from: '/docs/client-circuit-breaker',
              to: '/docs/client/circuit-breaker',
            },
            {
              from: '/docs/client-service-discovery',
              to: '/docs/client/service-discovery',
            },
            {
              from: '/docs/advanced-logging',
              to: '/docs/advanced/logging',
            },
            {
              from: '/docs/advanced-structured-logging',
              to: '/docs/advanced/structured-logging',
            },
            {
              from: '/docs/advanced-custom-attributes',
              to: '/docs/advanced/custom-attributes',
            },
            {
              from: '/docs/advanced-streaming-backpressure',
              to: '/docs/advanced/streaming-backpressure',
            },
            {
              from: '/docs/advanced-structured-logging-kafka',
              to: '/docs/advanced/structured-logging-kafka',
            },
            {
              from: '/docs/advanced-metrics',
              to: '/docs/advanced/metrics',
            },
            {
              from: '/docs/advanced-unit-testing',
              to: '/docs/advanced/unit-testing',
            },
            {
              from: '/docs/advanced-production-checklist',
              to: '/docs/advanced/production-checklist',
            },
            {
              from: '/docs/advanced-saml',
              to: '/docs/advanced/saml',
            },
            {
              from: '/docs/advanced-athenz',
              to: '/docs/advanced/athenz',
            },
            {
              from: '/docs/advanced-spring-boot-integration',
              to: '/docs/advanced/spring-boot-integration',
            },
            {
              from: '/docs/advanced-spring-webflux-integration',
              to: '/docs/advanced/spring-webflux-integration',
            },
            {
              from: '/docs/advanced-dropwizard-integration',
              to: '/docs/advanced/dropwizard-integration',
            },
            {
              from: '/docs/advanced-kotlin',
              to: '/docs/advanced/kotlin',
            },
            {
              from: '/docs/advanced-scala',
              to: '/docs/advanced/scala',
            },
            {
              from: '/docs/advanced-scalapb',
              to: '/docs/advanced/scalapb',
            },
            {
              from: '/docs/advanced-flags-provider',
              to: '/docs/advanced/flags-provider',
            },
            {
              from: '/docs/advanced-zipkin',
              to: '/docs/advanced/zipkin',
            },
            {
              from: '/docs/advanced-client-interoperability',
              to: '/docs/advanced/client-interoperability',
            },
          ],
          createRedirects(existingPath) {
            if (existingPath.includes('/docs/tutorials/rest')) {
              // Redirect from /tutorials/rest/blog/X to /docs/tutorials/rest/X
              return [
                existingPath.replace('/docs/tutorials/rest', '/tutorials/rest/blog'),
              ];
            }
            if (existingPath.includes('/docs/tutorials/grpc')) {
              // Redirect from /tutorials/grpc/blog/X to /docs/tutorials/grpc/X
              return [
                existingPath.replace('/docs/tutorials/grpc', '/tutorials/grpc/blog'),
              ];
            }
            if (existingPath.includes('/docs/tutorials/thrift')) {
              // Redirect from /tutorials/thrift/blog/X to /docs/tutorials/thrift/X
              return [
                existingPath.replace('/docs/tutorials/thrift', '/tutorials/thrift/blog'),
              ];
            }

            return undefined; // Return a falsy value: no redirect created
          },
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
      [
        './src/plugins/rss-generator.ts',
        {
          path: '/release-notes',
          title: 'Armeria Release Notes',
          exclude: ['version-*/index.html'],
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
