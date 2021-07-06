const path = require('path');
const remarkGithubPlugin = require('remark-github');
const remarkGridTablesPlugin = require('remark-grid-tables');

module.exports = {
  siteMetadata: {
    title: 'Armeria - Your go-to microservice framework',
    siteUrl: 'https://armeria.dev/',
    shortUrls: [
      {
        name: 'slack',
        href:
          'https://join.slack.com/t/line-armeria/shared_invite/enQtNjIxNDU1ODU1MTI2LWRlMGRjMzIwOTIzMzA2NDA1NGMwMTg2MTA3MzE4MDYyMjUxMjRlNWRiZTc1N2Q3ZGRjNDA5ZDZhZTI1NGEwODk',
      },
    ],
  },
  plugins: [
    {
      resolve: 'gatsby-plugin-build-date',
      options: {
        formatting: {
          format: 'YYYY-MM-DD[T]HH:mm:ss.SSS[Z]',
          utc: true,
        },
      },
    },
    'gatsby-plugin-cname',
    {
      resolve: 'gatsby-plugin-google-analytics',
      options: {
        trackingId: 'UA-145425527-1',
      },
    },
    {
      resolve: 'gatsby-plugin-import',
      options: {
        libraryName: 'antd',
        libraryDirectory: 'es',
        style: true,
      },
    },
    {
      resolve: 'gatsby-plugin-less',
      options: {
        lessOptions: {
          javascriptEnabled: true,
          modifyVars: {
            hack: `true; @import '${path.resolve(
              __dirname,
              'src',
              'styles',
              'antd-overrides.less',
            )}';`,
          },
        },
      },
    },
    {
      resolve: 'gatsby-plugin-manifest',
      options: {
        name: 'Armeria',
        short_name: 'Armeria',
        start_url: '/',
        lang: 'en',
        display: 'browser',
        icon: 'src/images/favicon.svg',
      },
    },
    {
      resolve: 'gatsby-plugin-mdx',
      options: {
        defaultLayouts: {
          default: path.resolve(__dirname, 'src', 'layouts', 'base.tsx'),
          community: path.resolve(__dirname, 'src', 'layouts', 'community.tsx'),
          docs: path.resolve(__dirname, 'src', 'layouts', 'docs.tsx'),
          news: path.resolve(__dirname, 'src', 'layouts', 'news.tsx'),
          'release-notes': path.resolve(
            __dirname,
            'src',
            'layouts',
            'release-notes.tsx',
          ),
          tutorials: path.resolve(__dirname, 'src', 'layouts', 'tutorials.tsx'),
        },
        remarkPlugins: [remarkGithubPlugin, remarkGridTablesPlugin],
        gatsbyRemarkPlugins: [
          'gatsby-remark-autolink-headers',
          {
            resolve: require.resolve(
              path.resolve(
                __dirname,
                'src',
                'plugins',
                'gatsby-remark-api-links',
              ),
            ),
          },
          'gatsby-remark-copy-linked-files',
          {
            resolve: require.resolve(
              path.resolve(
                __dirname,
                'src',
                'plugins',
                'gatsby-remark-draw-patched',
              ),
            ),
            options: {
              strategy: 'img',
              mermaid: {
                theme: 'neutral',
                backgroundColor: 'transparent',
              },
            },
          },
          {
            resolve: 'gatsby-remark-images',
            options: {
              linkImagesToOriginal: false,
            },
          },
          {
            resolve: 'gatsby-remark-plantuml-lite',
            options: {
              imageType: 'svg',
            },
          },
          {
            resolve: require.resolve(
              path.resolve(
                __dirname,
                'src',
                'plugins',
                'gatsby-remark-table-of-contents-patched',
              ),
            ),
          },
        ],
      },
    },
    {
      resolve: `gatsby-plugin-nprogress`,
      options: {
        color: `#ff0089`,
      },
    },
    'gatsby-plugin-sharp',
    {
      resolve: 'gatsby-plugin-sitemap',
      options: {
        exclude: ['/s/*'],
      },
    },
    'gatsby-plugin-react-helmet',
    'gatsby-plugin-typescript',
    {
      resolve: `gatsby-source-filesystem`,
      options: {
        name: 'images',
        path: path.resolve(__dirname, 'src', 'images'),
      },
    },
    {
      resolve: `gatsby-source-filesystem`,
      options: {
        name: 'community',
        path: path.resolve(__dirname, 'src', 'pages', 'community'),
      },
    },
    {
      resolve: `gatsby-source-filesystem`,
      options: {
        name: 'docs',
        path: path.resolve(__dirname, 'src', 'pages', 'docs'),
      },
    },
    {
      resolve: `gatsby-source-filesystem`,
      options: {
        name: 'tutorials',
        path: path.resolve(__dirname, 'src', 'pages', 'tutorials'),
      },
    },
    {
      resolve: `gatsby-source-filesystem`,
      options: {
        name: 'news',
        path: path.resolve(__dirname, 'src', 'pages', 'news'),
      },
    },
    {
      resolve: `gatsby-source-filesystem`,
      options: {
        name: 'release-notes',
        path: path.resolve(__dirname, 'src', 'pages', 'release-notes'),
      },
    },
    'gatsby-transformer-sharp',
  ],
};
