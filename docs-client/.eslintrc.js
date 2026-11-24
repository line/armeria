const defaultConfig = require('../settings/eslint/eslintrc');

module.exports = {
  ...defaultConfig,
  ignorePatterns: [...defaultConfig.ignorePatterns, 'webpack.config.ts'],
  overrides: [
    ...defaultConfig.overrides,
    {
      files: ['e2e/**/*.{js,ts}', 'playwright.config.ts'],
      rules: {
        'import/no-extraneous-dependencies': [
          'error',
          { devDependencies: true },
        ],
      },
    },
  ],
};
