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

process.env.WEBPACK_SERVE = 'true';

import serve from 'webpack-serve';

import config from '../webpack.config';

// tslint:disable-next-line:no-var-requires
const proxy = require('koa-proxies');

// Very simple fallback to index.html when filename is not a known resource.
async function historyFallback(ctx: any, next: any) {
  if (
    ctx.path.endsWith('.js') ||
    ctx.path.endsWith('.css') ||
    ctx.path.endsWith('.json') ||
    ctx.path.endsWith('.woff') ||
    ctx.path.endsWith('.woff2')
  ) {
    return next();
  }

  ctx.url = '/index.html';
  return next();
}

const proxyPort = process.env.PROXY_PORT || '8080';

const proxier = proxy('/', {
  target: `http://127.0.0.1:${proxyPort}`,
  changeOrigin: true,
});

async function proxyToApi(ctx: any, next: any) {
  if (
    ctx.method !== 'POST' &&
    !ctx.path.endsWith('specification.json') &&
    !ctx.path.endsWith('injected.js')
  ) {
    return next();
  }
  return proxier(ctx, next);
}

serve(
  {},
  {
    config,
    port: 3000,
    add: (app, middleware) => {
      app.use(proxyToApi);
      app.use(historyFallback);
      middleware.webpack();
      middleware.content();
    },
  },
)
  .then((result) => {
    process.on('SIGINT', () => {
      result.app.stop();
      process.exit(0);
    });
  })
  .catch((err) => {
    // tslint:disable-next-line:no-console
    console.log('Error starting dev server.', err);
    process.exit(1);
  });
