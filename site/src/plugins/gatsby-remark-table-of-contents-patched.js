/* eslint-disable import/no-extraneous-dependencies */
const util = require('mdast-util-toc');
/* eslint-enable import/no-extraneous-dependencies */
const dayjs = require('dayjs');
const advancedFormat = require('dayjs/plugin/advancedFormat');

dayjs.extend(advancedFormat);

const transformer = (props) => {
  const { frontmatter } = props.markdownNode;
  const { markdownAST } = props;
  // Find the first heading at the first level.
  // We will insert the ToC right next to it.
  const index = markdownAST.children.findIndex(
    (node) => node.type === 'heading' && node.depth === 1,
  );

  const hasTopHeading = index >= 0;

  // Generate the document title from the first heading.
  let pageTitle;
  if (hasTopHeading) {
    try {
      pageTitle =
        frontmatter.title ||
        util({
          ...markdownAST,
          children: [markdownAST.children[index]],
        }).map.children[0].children[0].children[0].children[0].value;
      frontmatter.title = pageTitle;
    } catch (e) {
      // Ignore.
    }
  }

  // Generate ToC from the non-first headings.
  const toc = hasTopHeading
    ? util(
        {
          ...markdownAST,
          children: markdownAST.children.flatMap((node) => {
            if (node.type === 'heading' && node.depth >= 2) {
              return [node];
            }
            return [];
          }),
        },
        {
          maxDepth: 4,
        },
      ).map
    : undefined;

  let oldChildren = markdownAST.children;
  const newChildren = [];

  if (pageTitle) {
    newChildren.push({
      type: 'export',
      value: `export const pageTitle = ${JSON.stringify(pageTitle)}`,
    });
  }

  if (hasTopHeading) {
    newChildren.push(...oldChildren.slice(0, index + 1));
    oldChildren = oldChildren.slice(index + 1);
  }

  if (frontmatter.date) {
    newChildren.push({
      type: 'paragraph',
      children: [
        {
          type: 'text',
          value: `${dayjs(frontmatter.date).format('Do MMMM YYYY')}`,
        },
      ],
      data: {
        hProperties: { className: 'date' },
      },
    });
  }

  if (toc) {
    // Insert ToC and body.
    newChildren.push({
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
    });
    newChildren.push(toc);
  }

  newChildren.push(...oldChildren);
  markdownAST.children = newChildren;
};

module.exports = (props, pluginOptions) => {
  return transformer(props, pluginOptions);
};
