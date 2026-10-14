const defaultConfig = require('../settings/eslint/eslintrc');

module.exports = {
  ...defaultConfig,
  overrides: [
    ...defaultConfig.overrides,
    {
      files: ['playwright.config.ts', 'e2e/**/*.ts'],
      rules: {
        'import/no-extraneous-dependencies': [
          'error',
          { devDependencies: true },
        ],
      },
    },
  ],
};
