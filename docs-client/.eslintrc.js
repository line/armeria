const defaultConfig = require('../settings/eslint/eslintrc');

module.exports = {
  ...defaultConfig,
  ignorePatterns: [...defaultConfig.ignorePatterns, 'webpack.config.ts'],
};
