import { execFileSync } from 'node:child_process';
import { visit } from 'unist-util-visit';

let warned = false;

/**
 * A remark plugin that renders `bob-svg` code blocks into SVG images using `svgbob_cli`.
 * Leaves the code blocks as they are if `svgbob_cli` is not available.
 */
// eslint-disable-next-line no-unused-vars
const plugin = (options) => {
  const transformer = (markdownAST) => {
    visit(markdownAST, 'code', (node, index, parent) => {
      if (node.lang !== 'bob-svg' || !parent) {
        return;
      }

      let svg;
      try {
        svg = execFileSync('svgbob_cli', ['--background', 'transparent'], {
          input: node.value,
          encoding: 'utf8',
        });
      } catch (e) {
        if (!warned) {
          warned = true;
          // eslint-disable-next-line no-console
          console.warn(
            'Failed to render bob-svg diagrams with svgbob_cli:',
            e.message,
          );
        }
        return;
      }

      parent.children.splice(index, 1, {
        type: 'mdxJsxFlowElement',
        name: 'img',
        attributes: [
          { type: 'mdxJsxAttribute', name: 'className', value: 'bob-svg' },
          { type: 'mdxJsxAttribute', name: 'alt', value: 'diagram' },
          {
            type: 'mdxJsxAttribute',
            name: 'src',
            value: `data:image/svg+xml;base64,${Buffer.from(svg).toString('base64')}`,
          },
        ],
        children: [],
      });
    });
  };

  return transformer;
};

export default plugin;
