import path from 'path';

import FaviconsWebpackPlugin from 'favicons-webpack-plugin';
import HtmlWebpackPlugin from 'html-webpack-plugin';
import { LicenseWebpackPlugin } from 'license-webpack-plugin';
import CompressionWebpackPlugin from 'compression-webpack-plugin';
import { Configuration, DefinePlugin, optimize } from 'webpack';
import WebpackDevServer from 'webpack-dev-server';
import { docServiceDebug } from './src/lib/header-provider';
import MonacoWebpackPlugin from 'monaco-editor-webpack-plugin';

declare module 'webpack' {
  interface Configuration {
    devServer?: WebpackDevServer.Configuration;
  }
}

const armeriaPort = process.env.ARMERIA_PORT || '8080';

const isDev = !!process.env.WEBPACK_DEV;
const isWindows = process.platform === 'win32';

const config: Configuration = {
  mode: isDev ? 'development' : 'production',
  devtool: isDev ? 'eval-source-map' : undefined,
  entry: {
    main: ['react-hot-loader/patch', './src/index.tsx'],
  },
  output: {
    path: path.resolve(process.cwd(), './build/web'),
    // We don't mount to '/' for production build since we want the code to be relocatable.
    publicPath: isDev ? '/' : '',
    filename: isDev ? '[name].js' : '[name].[contenthash].js'
  },
  module: {
    rules: [
      {
        test: /\.ts(x?)$/,
        exclude: /node_modules/,
        use: [
          {
            loader: 'babel-loader',
            options: {
              presets: [
                [
                  '@babel/env',
                  {
                    modules: false,
                    useBuiltIns: 'entry',
                    corejs: 3,
                    targets: {
                      browsers: ['>1%', 'not ie 11', 'not op_mini all'],
                    },
                  },
                ],
                '@babel/react',
              ],
              plugins: isDev ? ['react-hot-loader/babel'] : [],
            },
          },
          {
            loader: 'ts-loader',
            options: {
              compilerOptions: {
                noEmit: false,
              },
              transpileOnly: true,
              onlyCompileBundledFiles: true,
              reportFiles: ['src/**/*.{ts,tsx}'],
            },
          },
        ],
      },
      {
        test: /\.css$/,
        use: ['style-loader', 'css-loader'],
      },
      {
        test: /\.(eot|otf|ttf|woff|woff2)$/,
        type: 'asset/resource',
      },
    ],
  },
  resolve: {
    modules: ['src', 'node_modules'],
    extensions: ['.js', '.jsx', '.ts', '.tsx'],
    mainFields: ['browser', 'module', 'jsnext:main', 'main'],
  },
  plugins: [],
  devServer: {
    historyApiFallback: true,
    hot: true,
    open: 'docs/',
    port: 3000,
    proxy: [
      {
        path: '/',
        context: (pathname, req) =>
          !!req.headers[docServiceDebug] ||
          pathname.endsWith('specification.json') ||
          pathname.endsWith('versions.json') ||
          pathname.endsWith('schemas.json') ||
          pathname.endsWith('injected.js'),
        target: `http://127.0.0.1:${armeriaPort}`,
        changeOrigin: true,
      },
    ],
    client: {
      overlay: {
        warnings: false,
        errors: true,
      },
    },
  },
};

// Configure plugins.
const plugins = config.plugins as any[];
plugins.push(new HtmlWebpackPlugin({
  template: './src/index.html',
}));
plugins.push(new FaviconsWebpackPlugin({
  logo: './src/images/logo.png',
  // We don't need the many different icon versions of webapp mode and use light mode
  // to keep JAR size down.
  mode: 'light',
  devMode: 'light',
}));

// Do not add LicenseWebpackPlugin on Windows, because otherwise it will fail with a known issue.
if (!isWindows) {
  plugins.push(new LicenseWebpackPlugin({
    stats: {
      warnings: true,
      errors: true,
    },
    perChunkOutput: false,
    outputFilename: '../../../licenses/web-licenses.txt',
  }) as any);
}

plugins.push(
  new MonacoWebpackPlugin({
    languages: ['json', 'graphql'],
    publicPath: isDev ? '/' : '',
    customLanguages: [
      {
        label: 'graphql',
        entry: undefined,
        worker: {
          id: 'graphql',
          entry: require.resolve('monaco-graphql/esm/graphql.worker.js'),
        },
      },
    ],
    features: [
      "!accessibilityHelp",
      "!bracketMatching",
      "!browser",
      "!caretOperations",
      "!clipboard",
      "!codeAction",
      "!codelens",
      "!colorPicker",
      "!comment",
      "!contextmenu",
      "!cursorUndo",
      "!dnd",
      "!find",
      "!folding",
      "!fontZoom",
      "!gotoError",
      "!gotoLine",
      "!gotoSymbol",
      "!iPadShowKeyboard",
      "!inPlaceReplace",
      "!inspectTokens",
      "!linesOperations",
      "!links",
      "!multicursor",
      "!parameterHints",
      "!quickCommand",
      "!quickOutline",
      "!readOnlyMessage",
      "!referenceSearch",
      "!rename",
      "!smartSelect",
      "!toggleHighContrast",
      "!toggleTabFocusMode",
      "!wordOperations",
      "!wordPartOperations",
    ],
  })
)

const enableAnalyzer = !!process.env.WEBPACK_ANALYZER;
if (enableAnalyzer) {
  const BundleAnalyzerPlugin = require('webpack-bundle-analyzer').BundleAnalyzerPlugin;
  plugins.push(new BundleAnalyzerPlugin())
}

plugins.push(new optimize.LimitChunkCountPlugin({
  maxChunks: 1,
}));

plugins.push(new DefinePlugin({
  'process.env.WEBPACK_DEV': JSON.stringify(process.env.WEBPACK_DEV),
}));

// Do not add CompressionWebpackPlugin on dev
if (!isDev) {
  plugins.push(new CompressionWebpackPlugin({
    test: /\.(js|css|html|svg)$/,
    algorithm: 'gzip',
    filename: '[path][base].gz',
    // If a `Accept-Encoding` is not specified, `DocService` decompresses the compressed content on the fly.
    deleteOriginalAssets: true
  }) as any);
}


export default config;
