import { visit } from 'unist-util-visit';
import apiIndex from '../../gen-src/api-index.json';

/**
 * A remark plugin that appends API links to type links.
 * It turns `[RequestContext](type)` into `[RequestContext](type://https://actual-javadoc-link-here.html)`.
 */
// eslint-disable-next-line no-unused-vars
const plugin = (options) => {
  const transformer = (markdownAST) => {
    visit(markdownAST, 'link', (node) => {
      const typeInput = node.url;
      if (typeInput !== 'type' && typeInput !== 'typeplural') {
        return;
      }

      const typeNameInput = node.children[0].value;
      let optionIndex = typeNameInput.lastIndexOf('?');
      let option;
      if (optionIndex < 0) {
        optionIndex = typeNameInput.length;
      } else {
        option = typeNameInput.substring(optionIndex);
      }
      // Exclude option string from typeName
      let typeName = typeNameInput.substring(0, optionIndex);
      // Decode escaped string such as &lt;init&gt; that represents a constructor.
      typeName = decodeURIComponent(typeName);
      const href = typeName.startsWith('@')
        ? apiIndex[typeName.substring(1)]
        : apiIndex[typeName];
      if (href) {
        // eslint-disable-next-line no-param-reassign
        node.children[0].value = typeName;
        // eslint-disable-next-line no-param-reassign
        node.url = `${typeInput}://${href}`;
        if (option) {
          // eslint-disable-next-line no-param-reassign
          node.url += option;
        }
      } else {
        // If the api link is not found, set a blank URL
        // eslint-disable-next-line no-param-reassign
        node.url = 'type://#';
      }
    });
  };

  return transformer;
};

export default plugin;
