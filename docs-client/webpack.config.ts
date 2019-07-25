/*
 * Copyright 2018 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

import path from 'path';

import HtmlWebpackPlugin from 'html-webpack-plugin';
import { Configuration, DefinePlugin } from 'webpack';

import { docServiceDebug } from './src/lib/header-provider';

const armeriaPort = process.env.ARMERIA_PORT || '8080';

const isDev = !!process.env.WEBPACK_DEV;

const config: Configuration = {
  mode: isDev ? 'development' : 'production',
  devtool: isDev ? 'eval-source-map' : undefined,
  entry: {
    main: './src/index.tsx',
  },
  output: {
    path: path.resolve(process.cwd(), './build/web'),
    // We don't mount to '/' for production build since we want the code to be relocatable.
    publicPath: isDev ? '/' : '',
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
              plugins: ['react-hot-loader/babel'],
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
        use: 'file-loader',
      },
    ],
  },
  resolve: {
    modules: ['src', 'node_modules'],
    extensions: ['.js', '.jsx', '.ts', '.tsx'],
    mainFields: ['browser', 'module', 'jsnext:main', 'main'],
  },
  plugins: [
    new HtmlWebpackPlugin({
      template: './src/index.html',
    }),
    new DefinePlugin({
      'process.env.WEBPACK_DEV': JSON.stringify(process.env.WEBPACK_DEV),
    }),
  ],
  devServer: {
    historyApiFallback: true,
    hot: true,
    open: true,
    openPage: 'docs/',
    port: 3000,
    proxy: [
      {
        path: '/',
        context: (pathname, req) =>
          !!req.headers[docServiceDebug] ||
          pathname.endsWith('specification.json') ||
          pathname.endsWith('injected.js'),
        target: `http://127.0.0.1:${armeriaPort}`,
        changeOrigin: true,
      },
    ],
  },
};

export default config;
