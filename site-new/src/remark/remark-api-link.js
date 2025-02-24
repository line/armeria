import { visit } from 'unist-util-visit';
import apiIndex from '../../gen-src-temp/api-index.json';

// eslint-disable-next-line no-unused-vars
const plugin = (options) => {
  const transformer = (markdownAST) => {
    visit(markdownAST, 'link', (node) => {
      const typeInput = node.url;

      if (typeInput !== 'type' && typeInput !== 'typeplural') {
        return;
      }

      const typeName = node.children[0].value;
      const href = apiIndex[typeName];
      if (href) {
        // eslint-disable-next-line no-param-reassign
        node.url = `${typeInput}://${href}`;
      }
    });
  };

  return transformer;
};

export default plugin;

/*
{
  type: 'link',
  title: null,
  url: 'type',
  children: [ { type: 'text', value: 'AttributeKey', position: [Object] } ],
  position: {
    start: { line: 10, column: 6, offset: 190 },
    end: { line: 10, column: 26, offset: 210 }
  }
}
*/
