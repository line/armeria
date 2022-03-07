/* eslint-disable import/no-extraneous-dependencies */
// Installed by gatsby-remark-draw
const visit = require('unist-util-visit');
/* eslint-enable import/no-extraneous-dependencies */

// TODO(trustin): Use svgbob-wasm or something similar so that we don't have to install svgbob_cli manually.
const Draw = require('./lib/draw-patched');

module.exports = ({ markdownAST }, pluginOptions = {}) => {
  visit(markdownAST, 'code', (node, index, parent) => {
    const draw = new Draw();
    const lang = node.lang || '';

    if (!draw.isValidLanguage(lang)) {
      return;
    }

    let svg;
    try {
      svg = draw.render(lang, node.value, pluginOptions).value.replace(
        /(<\/style>)/,
        `.remark-draw-bob-svg * {
            font-family: Hack;
            font-size: 13px;
          }
          .remark-draw-bob-svg rect.backdrop,
          .remark-draw-bob-svg .nofill {
            fill: none;
          }
          $1`,
      );
    } catch (e) {
      // eslint-disable-next-line no-console
      console.warn('Failed to render a diagram:', node.value, e);
      return;
    }

    if (!svg.includes('font-family: Hack')) {
      throw new Error(`Failed to inject the font CSS: ${svg}`);
    }

    const image = {
      type: 'html',
      value: `<span class="${draw.className} ${draw.className}-${lang}">${svg}</span>`,
    };

    parent.children.splice(index, 1, image);
  });

  return markdownAST;
};
