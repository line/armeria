const skyrta = require('./skyrta-patched');

module.exports = class Draw {
  constructor(className) {
    this.languages = new Map();
    this.languages.set('bob-svg', 'bob');

    this.generator = skyrta;

    this.className = className || this.defaultClassName;
  }

  isValidLanguage(lang) {
    const mapped = this.languages.get(lang);
    return !!mapped;
  }

  render(language, input, pluginOptions = {}) {
    const lang = this.languages.get(language);

    if (!lang) {
      throw Error(`Unknown language ${language}`);
    }

    const langOptions = pluginOptions[lang] || {};
    return this.generator.generate(lang, input, langOptions);
  }

  get defaultClassName() {
    return 'remark-draw';
  }

  setGenerator(generator) {
    this.generator = generator;
  }
};
