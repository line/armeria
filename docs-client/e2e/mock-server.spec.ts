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

import { Agent, get } from 'node:http';
import { setImmediate } from 'node:timers/promises';

import { expect, test } from '@playwright/test';

import { mockServerPort } from '../playwright.config';

test('mock responses do not reuse stale keep-alive connections', async () => {
  const agent = new Agent({ keepAlive: true });
  const getVersions = () =>
    new Promise<{
      body: string;
      statusCode: number | undefined;
      reusedSocket: boolean;
    }>((resolve, reject) => {
      const request = get(
        `http://127.0.0.1:${mockServerPort}/docs/versions.json`,
        { agent },
        (response) => {
          let body = '';
          response.setEncoding('utf8');
          response.on('data', (chunk) => {
            body += chunk;
          });
          response.once('error', reject);
          response.once('end', () =>
            resolve({
              body,
              statusCode: response.statusCode,
              reusedSocket: request.reusedSocket,
            }),
          );
        },
      );
      request.once('error', reject);
    });

  try {
    const first = await getVersions();
    expect(first.statusCode).toBe(200);
    await setImmediate();
    // Delay socket-close processing until after the mock server's idle timeout.
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 6500);
    const second = await getVersions();
    expect(second.statusCode).toBe(200);
    expect(second.body).toBe(first.body);
    expect(second.reusedSocket).toBe(false);
  } finally {
    agent.destroy();
  }
});
