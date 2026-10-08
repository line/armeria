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

import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

import {
  ConsoleMessage,
  expect,
  Request,
  TestInfo,
  test as base,
} from '@playwright/test';

interface BrowserIssues {
  consoleErrors: string[];
  pageErrors: string[];
  requestFailures: string[];
}

export function expectConsoleError(testInfo: TestInfo, message: string) {
  testInfo.annotations.push({
    type: 'expected-console-error',
    description: message,
  });
}

export function expectRequestFailure(testInfo: TestInfo, message: string) {
  testInfo.annotations.push({
    type: 'expected-request-failure',
    description: message,
  });
}

export const test = base.extend<{ browserGuard: void }>({
  browserGuard: [
    async ({ page }, use, testInfo) => {
      const issues: BrowserIssues = {
        consoleErrors: [],
        pageErrors: [],
        requestFailures: [],
      };
      await page.addInitScript(() => {
        const snapshotKey = 'docs-client-e2e-coverage-snapshots';
        window.addEventListener('beforeunload', () => {
          const globals = window as unknown as Record<string, unknown>;
          // eslint-disable-next-line @typescript-eslint/dot-notation
          const currentCoverage = globals['__coverage__'];
          if (!currentCoverage) {
            return;
          }
          const snapshots = JSON.parse(
            window.sessionStorage.getItem(snapshotKey) || '[]',
          );
          snapshots.push(currentCoverage);
          window.sessionStorage.setItem(snapshotKey, JSON.stringify(snapshots));
        });
      });
      page.on('console', (message: ConsoleMessage) => {
        if (message.type() === 'error') {
          issues.consoleErrors.push(message.text());
        }
      });
      page.on('pageerror', (error: Error) =>
        issues.pageErrors.push(error.message),
      );
      page.on('requestfailed', (request: Request) => {
        issues.requestFailures.push(
          `${request.method()} ${request.url()}: ${
            request.failure()?.errorText
          }`,
        );
      });

      await use();

      if (testInfo.status !== testInfo.expectedStatus) {
        await testInfo.attach('browser-issues', {
          body: Buffer.from(JSON.stringify(issues, null, 2)),
          contentType: 'application/json',
        });
        return;
      }

      const coverage = await page.evaluate(() => {
        const snapshotKey = 'docs-client-e2e-coverage-snapshots';
        const globals = window as unknown as Record<string, unknown>;
        // eslint-disable-next-line @typescript-eslint/dot-notation
        const current = globals['__coverage__'];
        const snapshots = JSON.parse(
          window.sessionStorage.getItem(snapshotKey) || '[]',
        );
        return { current, snapshots };
      });
      expect(
        coverage.current,
        'The E2E bundle must be instrumented for coverage.',
      ).toBeTruthy();

      const rawDir = path.resolve(
        __dirname,
        '..',
        'build',
        'e2e-coverage',
        'raw',
      );
      fs.mkdirSync(rawDir, { recursive: true });
      const testId = createHash('sha256')
        .update(
          [
            testInfo.file,
            testInfo.title,
            testInfo.repeatEachIndex,
            testInfo.retry,
          ].join('\0'),
        )
        .digest('hex')
        .slice(0, 16);
      [...coverage.snapshots, coverage.current].forEach(
        (coverageSnapshot, index) => {
          fs.writeFileSync(
            path.join(
              rawDir,
              `${testInfo.workerIndex}-${testId}-${index}.json`,
            ),
            JSON.stringify(coverageSnapshot),
          );
        },
      );

      const expectedConsoleErrors = testInfo.annotations
        .filter((annotation) => annotation.type === 'expected-console-error')
        .map((annotation) => annotation.description!);
      for (const expectedError of expectedConsoleErrors) {
        expect(
          issues.consoleErrors.some((error) => error.includes(expectedError)),
          `Expected a console error containing: ${expectedError}`,
        ).toBe(true);
      }
      const unexpectedConsoleErrors = issues.consoleErrors.filter((error) => {
        return !expectedConsoleErrors.some((expectedError) =>
          error.includes(expectedError),
        );
      });
      const expectedRequestFailures = testInfo.annotations
        .filter((annotation) => annotation.type === 'expected-request-failure')
        .map((annotation) => annotation.description!);
      for (const expectedFailure of expectedRequestFailures) {
        expect(
          issues.requestFailures.some((failure) =>
            failure.includes(expectedFailure),
          ),
          `Expected a request failure containing: ${expectedFailure}`,
        ).toBe(true);
      }
      const unexpectedRequestFailures = issues.requestFailures.filter(
        (failure) => {
          return !expectedRequestFailures.some((expectedFailure) =>
            failure.includes(expectedFailure),
          );
        },
      );
      expect({
        ...issues,
        consoleErrors: unexpectedConsoleErrors,
        requestFailures: unexpectedRequestFailures,
      }).toEqual({
        consoleErrors: [],
        pageErrors: [],
        requestFailures: [],
      });
    },
    { auto: true },
  ],
});

export { expect };
