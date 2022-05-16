const svgbobPlugin = require('./plugins/svgbob');

module.exports = class SupportedLanguages {
  constructor() {
    this.supportedLanguages = new Map();
    this.addLanguage(svgbobPlugin);
  }

  getCommand(language) {
    const lang = (language || '').toLowerCase();
    return this.supportedLanguages.get(lang);
  }

  get languages() {
    return [...this.supportedLanguages.keys()];
  }

  addLanguage(plugin) {
    this.supportedLanguages.set(plugin.lang(), plugin);
  }
};
