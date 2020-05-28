/* eslint-disable import/no-extraneous-dependencies */
const visit = require('unist-util-visit');
/* eslint-enable import/no-extraneous-dependencies */
const apiIndex = require('../../gen-src/api-index.json');

const transformer = (markdownAST) => {
  visit(markdownAST, 'link', (node) => {
    if (
      !node.url.startsWith('type://') &&
      !node.url.startsWith('typeplural://')
    ) {
      return;
    }

    const prefixLength = node.url.indexOf('://') + 3;
    const typeName = node.url.substring(prefixLength);
    const href = typeName.startsWith('@')
      ? apiIndex[typeName.substring(1)]
      : apiIndex[typeName];
    if (href) {
      // eslint-disable-next-line no-param-reassign
      node.url = `${node.url.substring(0, prefixLength)}${typeName}:${href}`;
    }
  });
};

module.exports = ({ markdownAST }, pluginOptions) => {
  return transformer(markdownAST, pluginOptions);
};
