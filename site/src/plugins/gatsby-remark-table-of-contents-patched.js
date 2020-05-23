/* eslint-disable import/no-extraneous-dependencies */
const util = require('mdast-util-toc');
/* eslint-enable import/no-extraneous-dependencies */

const transformer = markdownAST => {
  // Find the first heading at the first level.
  // We will insert the ToC right next to it.
  const index = markdownAST.children.findIndex(
    node => node.type === 'heading' && node.depth === 1,
  );

  // we have no TOC
  if (index === -1) {
    return;
  }

  // Generate the document title from the first heading.
  let pageTitle;
  try {
    pageTitle = util({
      ...markdownAST,
      children: [markdownAST.children[index]],
    }).map.children[0].children[0].children[0].children[0].value;
  } catch (e) {
    pageTitle = undefined;
  }

  // Generate ToC from the non-first headings.
  const result = util(
    {
      ...markdownAST,
      children: markdownAST.children.flatMap(node => {
        if (node.type === 'heading' && node.depth >= 2) {
          return [node];
        }
        return [];
      }),
    },
    {
      maxDepth: 4,
    },
  );

  if (!result.map) {
    // Insert the pageTitle only.
    // eslint-disable-next-line no-param-reassign
    markdownAST.children = [].concat(
      {
        type: 'export',
        value: `export const pageTitle = ${JSON.stringify(pageTitle)}`,
      },
      markdownAST.children,
    );
  } else {
    // Insert the pageTitle and ToC.
    // eslint-disable-next-line no-param-reassign
    markdownAST.children = [].concat(
      {
        type: 'export',
        value: `export const pageTitle = ${JSON.stringify(pageTitle)}`,
      },
      markdownAST.children.slice(0, index + 1),
      {
        type: 'heading',
        depth: 6, // Use the level ignored by Tocbot.
        children: [
          {
            type: 'text',
            value: 'Table of contents',
          },
        ],
        data: {
          hProperties: { className: 'inlinePageToc', role: 'navigation' },
        },
      },
      result.map,
      markdownAST.children.slice(index + 1),
    );
  }
};

module.exports = ({ markdownAST }, pluginOptions) => {
  return transformer(markdownAST, pluginOptions);
};
