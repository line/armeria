const fs = require('fs');
const path = require('path');
const gatsbyConfig = require('./gatsby-config');

const pagePathPrefix = path.resolve(__dirname, 'src', 'pages');
const path2hrefs = {};
const path2title = {};

// Creates pages and populates pathHrefs.
exports.createPages = ({ graphql, actions: { createPage } }) => {
  function createShortUrlPages() {
    const template = path.resolve(__dirname, 'src', 'layouts', 'short-url.tsx');
    gatsbyConfig.siteMetadata.shortUrls.forEach((e) => {
      createPage({
        path: `/s/${e.name}`,
        component: template,
        context: {
          href: e.href,
        },
      });
    });
  }
  createShortUrlPages();

  function createPageIndices(sourceName, pageNameSorter, titleFunction) {
    const allPagesFilePath = path.resolve(
      __dirname,
      'gen-src',
      `${sourceName}-all.json`,
    );
    const recentPagesFilePath = path.resolve(
      __dirname,
      'gen-src',
      `${sourceName}-recent.json`,
    );

    return graphql(`
      query {
        allMdx(
          filter: {
            fileAbsolutePath: { glob: "**/src/pages/${sourceName}/**" }
          }
        ) {
          nodes {
            tableOfContents(maxDepth: 1)
            parent {
              ... on File {
                name
              }
            }
          }
        }
      }
    `).then((result) => {
      if (result.errors) {
        throw result.errors;
      }

      const pageNames = pageNameSorter(
        result.data.allMdx.nodes.map((node) => node.parent.name),
      );

      // Populate the pages' hrefs.
      pageNames.forEach((pageName, i) => {
        const key = `/${sourceName}/${pageName}`;
        const hrefs = path2hrefs[key] || (path2hrefs[key] = {});
        if (i > 0) {
          hrefs.prev = {
            label: `Newer: ${pageNames[i - 1]}`,
            href: `/${sourceName}/${pageNames[i - 1]}`,
          };
        }
        if (i < pageNames.length - 1) {
          hrefs.next = {
            label: `Older: ${pageNames[i + 1]}`,
            href: `/${sourceName}/${pageNames[i + 1]}`,
          };
        }
      });
      // Index page is the latest release page.
      path2hrefs[`/${sourceName}`] =
        path2hrefs[`/${sourceName}/${pageNames[0]}`];

      // Populate the pages' titles.
      result.data.allMdx.nodes.forEach((node) => {
        const pageName = node.parent.name;
        const key = `/${sourceName}/${pageName}`;
        const tableOfContents = node.tableOfContents;
        if (tableOfContents.items && tableOfContents.items[0]) {
          path2title[key] = tableOfContents.items[0].title;
        }
      });

      const resultAll = {};
      const resultRecent = {};
      pageNames.forEach((pageName, i) => {
        const key = `/${sourceName}/${pageName}`;
        const value = titleFunction(key, path2title[key]);
        resultAll[key] = value;
        if (i < 16) {
          resultRecent[key] = value;
        }
      });

      // Write all versions and recent versions into JSON files,
      // so that they are sourced from pages.
      fs.writeFileSync(allPagesFilePath, JSON.stringify(resultAll));
      fs.writeFileSync(recentPagesFilePath, JSON.stringify(resultRecent));
    });
  }

  return Promise.all([
    createPageIndices(
      'release-notes',
      (pageNames) => {
        const versionRegex = /^(([0-9]+)\.([0-9]+)\.([0-9]+))/;
        return (
          pageNames
            // Extract [pageName, fullVersion, major, minor, micro] from the page name.
            .map((pageName) => versionRegex.exec(pageName))
            // Remove the pages whose name is not a version.
            .filter((e) => e !== null)
            // Convert major, minor, micro version number into numbers.
            .map((e) => [
              e[0],
              e[1],
              Number.parseInt(e[2], 10),
              Number.parseInt(e[3], 10),
              Number.parseInt(e[4], 10),
            ])
            // Descending sort by version number.
            .sort((a, b) => {
              let v = b[2] - a[2];
              if (v !== 0) {
                return v;
              }
              v = b[3] - a[3];
              if (v !== 0) {
                return v;
              }
              return b[4] - a[4];
            })
            // Convert to a full version number.
            .map((e) => e[1])
        );
      },
      (pagePath) => `v${pagePath.substring(pagePath.lastIndexOf('/') + 1)}`,
    ),
    createPageIndices(
      'news',
      (pageNames) => {
        const dateRegex = /^[0-9]{8}/;
        return (
          pageNames
            .filter((pageName) => pageName.match(dateRegex))
            // Descending sort by page name.
            .sort((a, b) => {
              if (a > b) {
                return -1;
              }
              if (a < b) {
                return 1;
              }
              return 0;
            })
        );
      },
      (pageName, pageTitle) => pageTitle || pageName,
    ),
  ]);
};

exports.onCreateWebpackConfig = ({ stage, actions, getConfig }) => {
  // From: https://github.com/gatsbyjs/gatsby/discussions/30169
  if (stage === 'build-javascript' || stage === 'develop') {
    const config = getConfig();
    const miniCssExtractPlugin = config.plugins.find(
      (plugin) => plugin.constructor.name === 'MiniCssExtractPlugin',
    );
    if (miniCssExtractPlugin) {
      miniCssExtractPlugin.options.ignoreOrder = true;
    }
    actions.replaceWebpackConfig(config);
  }
};

exports.onCreatePage = ({ page }) => {
  const componentPath = page.componentPath;
  if (!componentPath) {
    return;
  }
  const ext = path.extname(componentPath);
  const pathWithoutExt = componentPath.substring(
    0,
    componentPath.length - ext.length,
  );

  // Loads the '.json' file next to the page into 'pageContext.companion'.
  // Note that this will cause some GraphQL schema warnings while building,
  // but it seems safe to ignore.
  function attachCompanion() {
    const jsonPath = `${pathWithoutExt}.json`;

    if (fs.existsSync(jsonPath)) {
      /* eslint-disable global-require */
      /* eslint-disable import/no-dynamic-require */
      // eslint-disable-next-line no-param-reassign
      page.context.companion = require(jsonPath);
      /* eslint-enable import/no-dynamic-require */
      /* eslint-enable global-require */
    }
  }
  attachCompanion();

  function attachHrefs() {
    let pagePath = pathWithoutExt;
    if (pagePath.match(/[/\\]index$/)) {
      pagePath = pagePath.substring(0, pagePath.length - 6);
    } else if (pagePath.endsWith('/')) {
      pagePath = pagePath.substring(0, pagePath.length - 1);
    }
    if (pagePath.startsWith(pagePathPrefix)) {
      pagePath = pagePath.substring(pagePathPrefix.length);
    }
    if (pagePath === '') {
      pagePath = '/';
    }
    // eslint-disable-next-line no-param-reassign
    page.context.hrefs = path2hrefs[pagePath];
  }
  attachHrefs();
};
