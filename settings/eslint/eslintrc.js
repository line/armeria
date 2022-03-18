const commonParserOptions = {
  ecmaVersion: '2020',
  sourceType: 'module',
};

const commonRules = {
  'class-methods-use-this': 'off',
  'eslint-comments/no-unused-disable': 'error',
  'func-names': [ 'error', 'never' ],
  'import/export': 'off',
  'import/no-cycle': 'off',
  'import/no-named-as-default': 'off',
  'import/no-useless-path-segments': 'off',
  'import/prefer-default-export': 'off',
  'no-continue': 'off',
  'no-restricted-syntax': [
    'error',
    {
      selector: 'ForInStatement',
      message: 'for..in loops iterate over the entire prototype chain, which is virtually never what you want. Use Object.{keys,values,entries}, and iterate over the resulting array.',
    },
    {
      selector: 'LabeledStatement',
      message: 'Labels are a form of GOTO; using them makes code confusing and hard to maintain and understand.',
    },
    {
      selector: 'WithStatement',
      message: '`with` is disallowed in strict mode because it makes code impossible to predict and optimize.',
    },
  ],
  'prefer-destructuring': 'off',
  'react/destructuring-assignment': 'off',
  'react/function-component-definition': [
    'error',
    {
      namedComponents: 'arrow-function',
      unnamedComponents: 'arrow-function',
    },
  ],
  'react/jsx-props-no-spreading': 'off',
  'react/require-default-props': 'off',
};

module.exports = {
  env: {
    browser: true,
    node: true,
    jest: true,
  },
  ignorePatterns: ['node_modules/', 'public/', 'build/', '!.*.js', '!.*.json'],
  // Settings for .js, .jsx and .json
  parserOptions: {
    ...commonParserOptions,
  },
  extends: [
    'airbnb',
    'airbnb/hooks',
    'eslint:recommended',
    'plugin:eslint-comments/recommended',
    'plugin:json/recommended',
    'plugin:prettier/recommended',
  ],
  rules: {
    ...commonRules,
  },
  overrides: [
    {
      // Settings for .ts and .tsx
      files: ['**/*.ts', '**/*.tsx'],
      parser: '@typescript-eslint/parser',
      parserOptions: {
        ...commonParserOptions,
        project: './tsconfig.json',
      },
      extends: [
        'airbnb-typescript',
        'airbnb/hooks',
        'plugin:@typescript-eslint/eslint-recommended',
        'plugin:eslint-comments/recommended',
        'plugin:prettier/recommended',
      ],
      rules: {
        ...commonRules,
        'react/prop-types': 'off',
        '@typescript-eslint/explicit-function-return-type': 'off',
        '@typescript-eslint/no-explicit-any': 'off',
        '@typescript-eslint/no-non-null-assertion': 'off',
        '@typescript-eslint/no-use-before-define': [
          'error', {
            'functions': false,
          }
        ]
      },
    },
  ],
};
