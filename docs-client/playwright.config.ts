/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import { defineConfig } from '@playwright/test';

const mockServerPort = 51234;
const nodeExecutable = JSON.stringify(process.execPath);

export default defineConfig({
  testDir: './e2e',
  outputDir: './build/playwright/results',
  fullyParallel: false,
  workers: 1,
  reporter: process.env.CI ? 'line' : 'list',
  use: {
    baseURL: 'http://127.0.0.1:3000/docs/',
    browserName: 'chromium',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  webServer: [
    {
      command: `${nodeExecutable} e2e/mock-server.js ${mockServerPort}`,
      url: `http://127.0.0.1:${mockServerPort}/docs/specification.json`,
    },
    {
      command:
        `${nodeExecutable} node_modules/webpack-dev-server/bin/webpack-dev-server.js ` +
        '--no-open --host 127.0.0.1',
      url: 'http://127.0.0.1:3000/docs/',
      env: {
        ARMERIA_PORT: `${mockServerPort}`,
        DOCS_CLIENT_E2E_COVERAGE: 'true',
        TS_NODE_PROJECT: 'tsconfig-webpack.json',
        WEBPACK_DEV: 'true',
      },
    },
  ],
});
