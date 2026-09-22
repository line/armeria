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

import { Locator } from '@playwright/test';

import {
  chooseSelect,
  gotoMethod,
  openDebug,
  selectAllShortcut,
  setMonacoValue,
} from './helpers';
import {
  expect,
  expectConsoleError,
  expectRequestFailure,
  test,
} from './test-fixture';

/* eslint-disable no-await-in-loop */

function textInputs(dialog: Locator) {
  return dialog.locator('input.MuiInputBase-input:not(.MuiSelect-nativeInput)');
}

function headerInput(dialog: Locator) {
  return dialog.locator(
    'textarea.MuiInputBase-input:not([aria-hidden="true"])',
  );
}

function responseButtons(dialog: Locator) {
  return dialog.locator('button:has(svg)');
}

test('sends an exact GET and exposes response, clipboard, clear, and URL state', async ({
  context,
  page,
}) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await gotoMethod(page, 'example.HttpService', 'hello', 'GET');
  const dialog = await openDebug(page);
  await expect(page).toHaveURL(/debug_form_is_open=true/);

  const endpointPath = textInputs(dialog).first();
  await expect(endpointPath).toHaveValue('/hello');
  await expect(endpointPath).toHaveAttribute('readonly');
  await expect(responseButtons(dialog).nth(0)).toBeDisabled();
  await expect(responseButtons(dialog).nth(1)).toBeDisabled();

  await dialog.getByRole('button', { name: 'Submit' }).click();
  await expect(dialog.getByText('Response Body:')).toBeVisible();
  await expect(dialog).toContainText('"message": "hello"');
  await expect(dialog).toContainText('9007199254740993');
  await expect(dialog).toContainText('Status: 200');
  await expect(dialog).toContainText('Duration:');
  await expect(dialog).toContainText('Size:');
  await expect(dialog).toContainText('Timestamp');
  await expect(dialog).toContainText('x-e2e-response: true');

  await responseButtons(dialog).nth(0).click();
  await expect(
    page.evaluate(() => navigator.clipboard.readText()),
  ).resolves.toContain('9007199254740993');
  await dialog.getByRole('button', { name: 'Copy as a curl command' }).click();
  await expect(
    dialog.getByText('The curl command has been copied'),
  ).toBeVisible();
  const curl = await page.evaluate(() => navigator.clipboard.readText());
  expect(curl).toContain('curl -XGET');
  expect(curl).toContain("'http://127.0.0.1:3000/hello'");
  expect(curl).toContain('doc-service-debug: true');

  await responseButtons(dialog).nth(1).click();
  await expect(dialog.getByText('Response Body:')).toHaveCount(0);
  await expect(responseButtons(dialog).nth(1)).toBeDisabled();
  await dialog.getByRole('button', { name: 'Close' }).click();
  await expect(dialog).toBeHidden();
  await expect(page).not.toHaveURL(/debug_form_is_open/);

  const goTo = page.getByPlaceholder('Go to ...');
  await goTo.fill('HttpService#echo');
  await goTo.press('Enter');
  await goTo.fill('HttpService#hello');
  await goTo.press('Enter');
  const reopenedDialog = await openDebug(page);
  await expect(reopenedDialog.getByText('Response Body:')).toHaveCount(0);
});

test('sends path, query, headers, body, and injected headers in a POST', async ({
  context,
  page,
}) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await gotoMethod(page, 'example.HttpService', 'echo', 'POST');
  const dialog = await openDebug(page);

  await chooseSelect(
    page,
    dialog,
    'Select an example path...',
    '/echo/example',
  );
  await chooseSelect(
    page,
    dialog,
    'Select example queries...',
    'page=1&sort=name',
  );
  await chooseSelect(
    page,
    dialog,
    'Select example headers...',
    '"x-example-header":"method"',
  );
  await dialog.getByText('Select example requests...', { exact: true }).click();
  await page.getByRole('option').first().click();

  await textInputs(dialog).nth(0).fill('/echo/browser');
  await textInputs(dialog).nth(1).fill('page=2&sort=id');
  await headerInput(dialog).fill(
    '{"x-e2e-injected":"manual","x-custom":"value"}',
  );
  await setMonacoValue(
    dialog.locator('.monaco-editor'),
    '{"name":"browser","details":{"count":3}}',
  );
  await dialog
    .getByRole('checkbox', {
      name: 'Use these HTTP headers for all functions.',
    })
    .check();
  await dialog.getByRole('button', { name: 'Submit' }).click();

  await expect(dialog).toContainText('"method": "POST"');
  await expect(dialog).toContainText('"path": "/echo/browser"');
  await expect(dialog).toContainText('"page": "2"');
  await expect(dialog).toContainText('"x-e2e-injected": "manual"');
  await expect(dialog).toContainText('"x-custom": "value"');
  await expect(dialog).toContainText('"name": "browser"');
  await expect(page).toHaveURL(/endpoint_path=%2Fecho%2Fbrowser/);
  await expect(page).toHaveURL(/sticky_headers=true/);
  await dialog.getByRole('button', { name: 'Copy as a curl command' }).click();
  const curl = await page.evaluate(() => navigator.clipboard.readText());
  expect(curl).toContain('/echo/browser?page=2&sort=id');
  expect(curl).toContain('x-custom: value');
  expect(curl).toMatch(/"name":\s*"browser"/);

  await dialog.getByRole('button', { name: 'Close' }).click();
  const goTo = page.getByPlaceholder('Go to ...');
  await goTo.fill('HttpService#hello');
  await goTo.press('Enter');
  const helloDialog = await openDebug(page);
  await expect(headerInput(helloDialog)).toHaveValue(
    /"x-e2e-injected": "manual"[\s\S]*"x-custom": "value"/,
  );
});

test('validates prefix, regex, and regex-with-prefix endpoint paths', async ({
  page,
}) => {
  const cases = [
    {
      method: 'echo',
      verb: 'POST',
      invalid: '/wrong',
      error: 'should start with the prefix: /echo',
      valid: '/echo/valid',
    },
    {
      method: 'regex',
      verb: 'GET',
      invalid: '/items/nope',
      error: 'expected: regex:^/items/[0-9]+$',
      valid: '/items/42',
    },
    {
      method: 'regexWithPrefix',
      verb: 'GET',
      invalid: '/elsewhere/items/42',
      error: 'should start with the prefix: /api',
      secondInvalid: '/api/items/nope',
      secondError: 'expected: prefix:/api/ regex:^/items/[0-9]+$',
      valid: '/api/items/42',
    },
  ];

  for (const item of cases) {
    await gotoMethod(page, 'example.HttpService', item.method, item.verb);
    const dialog = await openDebug(page);
    await textInputs(dialog).first().fill(item.invalid);
    await dialog.getByRole('button', { name: 'Submit' }).click();
    await expect(dialog).toContainText(item.error);
    if (item.secondInvalid && item.secondError) {
      await textInputs(dialog).first().fill(item.secondInvalid);
      await dialog.getByRole('button', { name: 'Submit' }).click();
      await expect(dialog).toContainText(item.secondError);
    }
    await textInputs(dialog).first().fill(item.valid);
    await dialog.getByRole('button', { name: 'Submit' }).click();
    await expect(dialog).toContainText(`"path": "${item.valid}"`);
    await dialog.getByRole('button', { name: 'Close' }).click();
  }
});

test('selects among exact endpoints and rejects an unsupported path', async ({
  page,
}) => {
  await gotoMethod(page, 'example.HttpService', 'multiExact', 'GET');
  const dialog = await openDebug(page);
  await textInputs(dialog).first().fill('/multi/missing');
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await expect(dialog).toContainText('Supported paths:');

  await textInputs(dialog).first().fill('/multi/b');
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await expect(dialog).toContainText('"path": "/multi/b"');
});

test('supports streaming JSON bodies and rejects primitive header values', async ({
  page,
}) => {
  let requestCount = 0;
  page.on('request', (request) => {
    if (request.url().includes('/echo/stream')) {
      requestCount += 1;
    }
  });
  await gotoMethod(page, 'example.HttpService', 'echo', 'POST');
  const dialog = await openDebug(page);
  await textInputs(dialog).first().fill('/echo/stream');
  const editor = dialog.locator('.monaco-editor');
  const editorInput = editor.locator('textarea.inputarea');
  await editorInput.focus();
  await editorInput.press(selectAllShortcut);
  await editorInput.press('Backspace');
  await headerInput(dialog).fill('{"content-type":"application/x-ndjson"}');
  await expect(editor.locator('.view-lines')).toHaveText('');
  await editorInput.focus();
  await editorInput.pressSequentially('{"stream":true}');
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await expect(dialog).toContainText('"stream": true');
  expect(requestCount).toBe(1);

  await headerInput(dialog).fill('1');
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await expect(dialog).toContainText('HTTP headers must be a JSON object');
  expect(requestCount).toBe(1);

  await headerInput(dialog).fill('{}');
  await setMonacoValue(dialog.locator('.monaco-editor'), '{"name":"again"}');
  await dialog.getByRole('button', { name: 'Submit' }).click();
  const submittedUrl = page.url();
  await dialog.getByRole('button', { name: 'Submit' }).click();
  expect(page.url()).toBe(submittedUrl);
  expect(requestCount).toBe(3);
});

test('keeps malformed JSON readable and restores cached responses', async ({
  page,
}) => {
  await gotoMethod(page, 'example.HttpService', 'invalidJson', 'GET');
  let dialog = await openDebug(page);
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await expect(dialog).toContainText('not valid json');
  await dialog.getByRole('button', { name: 'Close' }).click();

  const goTo = page.getByPlaceholder('Go to ...');
  await goTo.fill('HttpService#hello');
  await goTo.press('Enter');
  dialog = await openDebug(page);
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await expect(dialog).toContainText('"message": "hello"');
  await dialog.getByRole('button', { name: 'Close' }).click();

  await goTo.fill('HttpService#invalidJson');
  await goTo.press('Enter');
  dialog = await openDebug(page);
  await expect(dialog).toContainText('not valid json');
});

test('does not show a response completed for a previous method', async ({
  page,
}) => {
  let releaseResponse: () => void = () => {};
  const responseReleased = new Promise<void>((resolve) => {
    releaseResponse = resolve;
  });
  let markRequestObserved: () => void = () => {};
  const requestObserved = new Promise<void>((resolve) => {
    markRequestObserved = resolve;
  });
  let markResponseCompleted: () => void = () => {};
  const responseCompleted = new Promise<void>((resolve) => {
    markResponseCompleted = resolve;
  });
  await page.route('**/hello', async (route) => {
    const response = await route.fetch();
    markRequestObserved();
    await responseReleased;
    await route.fulfill({ response });
    markResponseCompleted();
  });

  await gotoMethod(page, 'example.HttpService', 'hello', 'GET');
  let dialog = await openDebug(page);
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await requestObserved;
  await dialog.getByRole('button', { name: 'Close' }).click();

  const goTo = page.getByPlaceholder('Go to ...');
  await goTo.fill('HttpService#text');
  await goTo.press('Enter');
  dialog = await openDebug(page);
  releaseResponse();
  await responseCompleted;
  await expect(dialog.getByText('Response Body:')).toHaveCount(0);
});

test('does not show a request failure from a previous method', async ({
  page,
}, testInfo) => {
  expectRequestFailure(testInfo, '/hello');
  expectConsoleError(testInfo, 'Failed to load resource: net::ERR_FAILED');
  let releaseRequest: () => void = () => {};
  const requestReleased = new Promise<void>((resolve) => {
    releaseRequest = resolve;
  });
  let markRequestObserved: () => void = () => {};
  const requestObserved = new Promise<void>((resolve) => {
    markRequestObserved = resolve;
  });
  await page.route('**/hello', async (route) => {
    markRequestObserved();
    await requestReleased;
    await route.abort('failed');
  });

  await gotoMethod(page, 'example.HttpService', 'hello', 'GET');
  let dialog = await openDebug(page);
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await requestObserved;
  await dialog.getByRole('button', { name: 'Close' }).click();

  const goTo = page.getByPlaceholder('Go to ...');
  await goTo.fill('HttpService#text');
  await goTo.press('Enter');
  dialog = await openDebug(page);
  const failedRequest = page.waitForEvent('requestfailed', (request) =>
    request.url().endsWith('/hello'),
  );
  releaseRequest();
  await failedRequest;
  await expect(dialog.getByText('Response Body:')).toHaveCount(0);
});

test('keeps request edits made while a response is pending', async ({
  page,
}) => {
  let releaseResponse: () => void = () => {};
  const responseReleased = new Promise<void>((resolve) => {
    releaseResponse = resolve;
  });
  let markRequestObserved: () => void = () => {};
  const requestObserved = new Promise<void>((resolve) => {
    markRequestObserved = resolve;
  });
  let markResponseCompleted: () => void = () => {};
  const responseCompleted = new Promise<void>((resolve) => {
    markResponseCompleted = resolve;
  });
  await page.route('**/echo', async (route) => {
    const response = await route.fetch();
    markRequestObserved();
    await responseReleased;
    await route.fulfill({ response });
    markResponseCompleted();
  });

  await gotoMethod(page, 'example.HttpService', 'echo', 'POST');
  const dialog = await openDebug(page);
  const editor = dialog.locator('.monaco-editor');
  await textInputs(dialog).nth(0).fill('/echo');
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await requestObserved;
  await expect(page).toHaveURL(/request_body=/);
  await setMonacoValue(editor, '{"draft":true}');

  releaseResponse();
  await responseCompleted;
  await expect(dialog).toContainText('"path": "/echo"');
  await expect(editor.locator('.view-lines')).toContainText('"draft":true');
});

test('does not update Debug state after leaving the method page', async ({
  page,
}) => {
  let releaseResponse: () => void = () => {};
  const responseReleased = new Promise<void>((resolve) => {
    releaseResponse = resolve;
  });
  let markRequestObserved: () => void = () => {};
  const requestObserved = new Promise<void>((resolve) => {
    markRequestObserved = resolve;
  });
  let markResponseCompleted: () => void = () => {};
  const responseCompleted = new Promise<void>((resolve) => {
    markResponseCompleted = resolve;
  });
  await page.route('**/hello', async (route) => {
    const response = await route.fetch();
    markRequestObserved();
    await responseReleased;
    await route.fulfill({ response });
    markResponseCompleted();
  });

  await gotoMethod(page, 'example.HttpService', 'hello', 'GET');
  const dialog = await openDebug(page);
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await requestObserved;
  await page.goto('/docs/#/');
  await expect(page).toHaveURL(/\/docs\/#\/$/);

  releaseResponse();
  await responseCompleted;
  await page.evaluate(
    () =>
      new Promise<void>((resolve) => {
        requestAnimationFrame(() => resolve());
      }),
  );
});

test('reports invalid header and request JSON without sending it', async ({
  page,
}) => {
  let requestCount = 0;
  page.on('request', (request) => {
    if (request.url().includes('/echo/invalid')) {
      requestCount += 1;
    }
  });
  await gotoMethod(page, 'example.HttpService', 'echo', 'POST');
  const dialog = await openDebug(page);
  await textInputs(dialog).first().fill('/echo/invalid');
  await headerInput(dialog).fill('{not-json');
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await expect(dialog).toContainText(
    'Failed to parse a JSON object in the HTTP headers',
  );
  expect(requestCount).toBe(0);

  await headerInput(dialog).fill('{}');
  await setMonacoValue(dialog.locator('.monaco-editor'), 'not-json');
  await dialog.getByRole('button', { name: 'Copy as a curl command' }).click();
  await expect(dialog).toContainText(
    'Failed to parse a JSON object in the request body',
  );
  expect(requestCount).toBe(0);

  await setMonacoValue(dialog.locator('.monaco-editor'), '1');
  await dialog.getByRole('button', { name: 'Copy as a curl command' }).click();
  await expect(dialog).toContainText('request body must be a JSON object');
  expect(requestCount).toBe(0);
});

test('restores a complete Debug form from a deep link', async ({ page }) => {
  const params = new URLSearchParams({
    debug_form_is_open: 'true',
    endpoint_path: '/echo/deep-link?existing=1',
    queries: 'source=link',
    headers: '{}',
    request_body: '{}',
  });
  await page.goto(
    `/docs/#/methods/example.HttpService/echo/POST?${params.toString()}`,
  );
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await expect(textInputs(dialog).nth(0)).toHaveValue(
    '/echo/deep-link?existing=1',
  );
  await expect(textInputs(dialog).nth(1)).toHaveValue('source=link');
  await expect(headerInput(dialog)).toHaveValue('{}');
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await expect(dialog).toContainText('"path": "/echo/deep-link"');
  await expect(dialog).toContainText('"existing": "1"');
  await expect(dialog).toContainText('"source": "link"');
});

test('renders JSON, text, empty, and non-success responses', async ({
  page,
}, testInfo) => {
  expectConsoleError(
    testInfo,
    "Failed to load resource: the server responded with a status of 418 (I'm a Teapot)",
  );
  const cases = [
    { method: 'failure', expected: 'Status: 418', body: 'teapot' },
    { method: 'text', expected: 'Status: 200', body: 'plain response' },
    {
      method: 'empty',
      expected: 'Status: 200',
      body: '<zero-length response>',
    },
  ];
  for (const item of cases) {
    await gotoMethod(page, 'example.HttpService', item.method, 'GET');
    const dialog = await openDebug(page);
    await dialog.getByRole('button', { name: 'Submit' }).click();
    await expect(dialog).toContainText(item.expected);
    await expect(dialog).toContainText(item.body);
    await dialog.getByRole('button', { name: 'Close' }).click();
  }
});

test('handles missing and non-prefix DocService routes and response types', async ({
  page,
}) => {
  for (const root of ['/no-route/', '/exact-route/']) {
    await gotoMethod(page, 'example.HttpService', 'hello', 'GET', root);
    const dialog = await openDebug(page);
    await dialog.getByRole('button', { name: 'Submit' }).click();
    await expect(dialog).toContainText('"message": "hello"');
  }

  await gotoMethod(page, 'example.HttpService', 'noContentType', 'GET');
  const dialog = await openDebug(page);
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await expect(dialog).toContainText('response without content type');
});

test('shows a rejected debug request to the user', async ({
  page,
}, testInfo) => {
  expectConsoleError(testInfo, 'Failed to load resource: net::ERR_FAILED');
  expectRequestFailure(testInfo, '/hello');
  await page.route('**/hello', (route) => route.abort('failed'));
  await gotoMethod(page, 'example.HttpService', 'hello', 'GET');
  const dialog = await openDebug(page);
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await expect(dialog).toContainText('Failed to fetch');
  await expect(dialog).toContainText('Status: –');
});

test('resets the request editor when navigating between body methods', async ({
  page,
}) => {
  await gotoMethod(page, 'example.HttpService', 'put', 'PUT');
  let dialog = await openDebug(page);
  let editor = dialog.locator('.monaco-editor');
  await expect(editor.locator('.view-lines')).toContainText('PUT');
  await setMonacoValue(editor, '{"verb":"custom"}');
  await dialog.getByRole('button', { name: 'Close' }).click();

  const goTo = page.getByPlaceholder('Go to ...');
  await goTo.fill('HttpService#patch');
  await goTo.press('Enter');
  dialog = await openDebug(page);
  editor = dialog.locator('.monaco-editor');
  await expect(editor.locator('.view-lines')).toContainText('PATCH');
  await expect(editor.locator('.view-lines')).not.toContainText('custom');
});

for (const item of [
  { method: 'options', verb: 'OPTIONS', body: null },
  { method: 'head', verb: 'HEAD', body: null },
  { method: 'put', verb: 'PUT', body: '"verb": "PUT"' },
  { method: 'patch', verb: 'PATCH', body: '"verb": "PATCH"' },
  { method: 'delete', verb: 'DELETE', body: '"verb": "DELETE"' },
]) {
  test(`sends the ${item.verb} HTTP method`, async ({ page }) => {
    await gotoMethod(page, 'example.HttpService', item.method, item.verb);
    const dialog = await openDebug(page);
    await dialog.getByRole('button', { name: 'Submit' }).click();
    if (item.verb === 'HEAD') {
      await expect(dialog).toContainText('<zero-length response>');
    } else {
      await expect(dialog).toContainText(`"method": "${item.verb}"`);
      if (item.body) {
        await expect(dialog).toContainText(item.body);
      }
    }
  });
}

test('prefixes debug requests when DocService is mounted below the server root', async ({
  page,
}) => {
  await gotoMethod(
    page,
    'example.HttpService',
    'echo',
    'POST',
    '/mounted/docs/',
  );
  const dialog = await openDebug(page);
  await textInputs(dialog).first().fill('/echo/mounted');
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await expect(dialog).toContainText('"path": "/echo/mounted"');
  await expect(dialog).toContainText('"requestPath": "/mounted/echo/mounted"');
});

/* eslint-enable no-await-in-loop */
