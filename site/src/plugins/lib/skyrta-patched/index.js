/* eslint-disable import/no-extraneous-dependencies */
// Installed by gatsby-remark-draw
const Svg = require('skyrta/lib/svg');
/* eslint-enable import/no-extraneous-dependencies */
const SupportedLanguages = require('./lib/supportedlanguages');

const langs = new SupportedLanguages();

function supportedLanguages() {
  return langs;
}

function validate(language, source) {
  const plugin = langs.getCommand(language);

  if (!plugin) {
    throw new Error(
      `Unsupported language ${language}.  Must be one of ${langs.languages.toString()} `,
    );
  }

  if (!source) {
    throw new Error('No source provided for input');
  }

  return plugin;
}

function execute(plugin, source, options) {
  try {
    const output = plugin.generate(source, options);
    if (output) {
      return new Svg(output, options);
    }
  } catch (e) {
    throw new Error(
      `Unable to render graph language with ${plugin.lang()}: ${e}`,
    );
  }

  return null;
}

function generate(language, source, options = {}) {
  const plugin = validate(language, source);
  return execute(plugin, source, options);
}

module.exports = {
  generate,
  supportedLanguages,
};
