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

import {
  expectMonacoSuggestion,
  gotoMethod,
  methodUrl,
  openDebug,
  selectAllShortcut,
  setMonacoValue,
} from './helpers';
import { expect, expectRequestFailure, test } from './test-fixture';

test('loads the GraphQL schema, completes a query, and sends variables', async ({
  context,
  page,
}) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  const introspectionRequest = page.waitForRequest((request) => {
    if (!request.url().endsWith('/graphql') || request.method() !== 'POST') {
      return false;
    }
    return request.postDataJSON().operationName === 'IntrospectionQuery';
  });
  await gotoMethod(page, 'example.GraphqlService', 'execute', 'POST');
  expect((await introspectionRequest).headers()['doc-service-debug']).toBe(
    'true',
  );

  const dialog = await openDebug(page);
  const queryEditor = dialog.locator('.monaco-editor');
  const queryInput = queryEditor.locator('textarea.inputarea');
  const queryToggle = dialog.getByRole('button', {
    name: '# Query',
    exact: true,
  });
  await queryInput.focus();
  await queryInput.press(selectAllShortcut);
  await queryInput.press('Backspace');
  await queryToggle.click();
  await queryToggle.click();
  await expect(queryEditor.locator('.view-lines')).toHaveText('');

  await setMonacoValue(queryEditor, '{ }');
  await queryInput.press('ArrowLeft');
  await expectMonacoSuggestion(page, queryEditor, 'greeting');
  await queryInput.press('Escape');

  await queryToggle.click();
  await expect(queryEditor).toBeHidden();
  await queryToggle.click();
  await expect(queryEditor).toBeVisible();
  await setMonacoValue(
    queryEditor,
    'query ($name: String!) { greeting(name: $name) { message } }',
  );

  const variablesButton = dialog.getByRole('button', {
    name: '# Query Variables',
  });
  const variables = dialog.locator(
    'textarea.MuiInputBase-input[rows="5"]:not([aria-hidden="true"])',
  );
  if (!(await variables.isVisible())) {
    await variablesButton.click();
  }
  await variables.fill('not-json');
  await variables.fill('{"name":"Browser"}');
  await variablesButton.click();
  await expect(variables).toBeHidden();
  await variablesButton.click();
  await expect(variables).toBeVisible();

  await dialog.getByRole('button', { name: 'Submit' }).click();
  await expect(dialog).toContainText('"message": "Hello, Browser"');
  await expect(page).toHaveURL(/request_body=/);
  await dialog.getByRole('button', { name: 'Copy as a curl command' }).click();
  const curl = await page.evaluate(() => navigator.clipboard.readText());
  expect(curl).toContain('application/graphql+json');
  expect(curl).toContain('accept: application/json');
  expect(curl).toContain('greeting');

  await page.reload();
  const restoredDialog = page.getByRole('dialog');
  await expect(restoredDialog).toBeVisible();
  await expect(restoredDialog.locator('.monaco-editor')).toContainText(
    'greeting',
  );
  await expect(
    restoredDialog.locator(
      'textarea.MuiInputBase-input[rows="5"]:not([aria-hidden="true"])',
    ),
  ).toHaveValue('{"name":"Browser"}');
  await page.waitForLoadState('networkidle');
});

test('keeps GraphQL editors usable without introspection data', async ({
  page,
}) => {
  const noSchemaResponse = page.waitForResponse((response) =>
    response.url().endsWith('/graphql-no-schema'),
  );
  await gotoMethod(page, 'example.GraphqlService', 'noSchema', 'POST');
  await noSchemaResponse;
  let editor = page.locator('.monaco-editor');
  await expect(editor).toBeVisible();
  await setMonacoValue(editor, 'query { stale }');

  const noDataResponse = page.waitForResponse((response) =>
    response.url().endsWith('/graphql-no-data'),
  );
  await gotoMethod(page, 'example.GraphqlService', 'noSchemaData', 'POST');
  await noDataResponse;
  editor = page.locator('.monaco-editor');
  await expect(editor).toBeVisible();
  await expect(editor.locator('.view-lines')).toHaveText('');
});

test('resets GraphQL state and schema when switching methods', async ({
  page,
}) => {
  const initialSchema = page.waitForResponse((response) =>
    response.url().endsWith('/graphql'),
  );
  await gotoMethod(page, 'example.GraphqlService', 'execute', 'POST');
  await initialSchema;

  let dialog = await openDebug(page);
  let editor = dialog.locator('.monaco-editor');
  await setMonacoValue(
    editor,
    'query ($name: String!) { greeting(name: $name) { message } }',
  );
  const variablesButton = dialog.getByRole('button', {
    name: '# Query Variables',
  });
  let variables = dialog.locator(
    'textarea.MuiInputBase-input[rows="5"]:not([aria-hidden="true"])',
  );
  if (!(await variables.isVisible())) {
    await variablesButton.click();
  }
  await variables.fill('{"name":"Old"}');
  const initialRequest = page.waitForRequest((request) => {
    if (!request.url().endsWith('/graphql')) {
      return false;
    }
    return request.postDataJSON().operationName !== 'IntrospectionQuery';
  });
  await dialog.getByRole('button', { name: 'Submit' }).click();
  expect((await initialRequest).postDataJSON().variables).toEqual({
    name: 'Old',
  });
  await dialog.getByRole('button', { name: 'Close' }).click();

  const alternateSchema = page.waitForResponse((response) =>
    response.url().endsWith('/graphql-alternate'),
  );
  await gotoMethod(page, 'example.GraphqlService', 'alternate', 'POST');
  await alternateSchema;
  dialog = await openDebug(page);
  editor = dialog.locator('.monaco-editor');
  await expect(editor.locator('.view-lines')).toHaveText('');
  variables = dialog.locator(
    'textarea.MuiInputBase-input[rows="5"]:not([aria-hidden="true"])',
  );
  await expect(variables).toHaveCount(0);
  await dialog.getByRole('button', { name: '# Query Variables' }).click();
  variables = dialog.locator(
    'textarea.MuiInputBase-input[rows="5"]:not([aria-hidden="true"])',
  );
  await expect(variables).toHaveValue('');

  await setMonacoValue(editor, '{ }');
  const input = editor.locator('textarea.inputarea');
  await input.press('ArrowLeft');
  await expectMonacoSuggestion(page, editor, 'farewell');
  await expect(
    page
      .locator('.suggest-widget.visible')
      .getByText('greeting', { exact: true }),
  ).toHaveCount(0);
  await input.press('Escape');

  await setMonacoValue(editor, 'query { farewell }');
  const alternateRequest = page.waitForRequest((request) => {
    if (!request.url().endsWith('/graphql-alternate')) {
      return false;
    }
    return request.postDataJSON().operationName !== 'IntrospectionQuery';
  });
  await dialog.getByRole('button', { name: 'Submit' }).click();
  const body = (await alternateRequest).postDataJSON();
  expect(body.variables).toEqual({});
  await expect(dialog).toContainText('"farewell": "Goodbye"');
});

test('cancels introspection when switching GraphQL methods', async ({
  page,
}, testInfo) => {
  expectRequestFailure(testInfo, '/graphql-pending');
  const pendingRequest = page.waitForRequest((request) =>
    request.url().endsWith('/graphql-pending'),
  );
  const cancelledRequest = page.waitForEvent('requestfailed', (request) =>
    request.url().endsWith('/graphql-pending'),
  );

  await gotoMethod(page, 'example.GraphqlService', 'pendingSchema', 'POST');
  await pendingRequest;
  await gotoMethod(page, 'example.GraphqlService', 'noSchemaData', 'POST');
  await cancelledRequest;

  const editor = page.locator('.monaco-editor');
  await expect(editor.locator('.view-lines')).toHaveText('');
});

test('restores GraphQL request state with browser history', async ({
  page,
}) => {
  await gotoMethod(page, 'example.GraphqlService', 'execute', 'POST');
  const dialog = await openDebug(page);
  const editor = dialog.locator('.monaco-editor');

  await setMonacoValue(editor, 'query { greeting(name: "Back") { message } }');
  let request = page.waitForRequest((candidate) => {
    if (!candidate.url().endsWith('/graphql')) {
      return false;
    }
    return candidate.postDataJSON().operationName !== 'IntrospectionQuery';
  });
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await request;
  await expect(dialog).toContainText('Hello, Back');
  const firstUrl = page.url();

  await setMonacoValue(
    editor,
    'query { greeting(name: "Forward") { message } }',
  );
  request = page.waitForRequest((candidate) => {
    if (!candidate.url().endsWith('/graphql')) {
      return false;
    }
    return candidate.postDataJSON().operationName !== 'IntrospectionQuery';
  });
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await request;
  await expect(dialog).toContainText('Hello, Forward');
  await expect(page).not.toHaveURL(firstUrl);

  await page.goBack();
  await expect(page).toHaveURL(firstUrl);
  await expect(editor.locator('.view-lines')).toContainText('Back');
  request = page.waitForRequest((candidate) => {
    if (!candidate.url().endsWith('/graphql')) {
      return false;
    }
    return candidate.postDataJSON().operationName !== 'IntrospectionQuery';
  });
  await dialog.getByRole('button', { name: 'Submit' }).click();
  expect((await request).postDataJSON().query).toContain('Back');
});

test('clears stale GraphQL state for a primitive deep link', async ({
  page,
}) => {
  await gotoMethod(page, 'example.GraphqlService', 'execute', 'POST');
  let dialog = await openDebug(page);
  await setMonacoValue(dialog.locator('.monaco-editor'), 'query { stale }');

  await page.goto(
    `${methodUrl(
      'example.GraphqlService',
      'execute',
      'POST',
    )}?debug_form_is_open=true&request_body=%22primitive%22`,
  );
  dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await expect(dialog.locator('.monaco-editor .view-lines')).toHaveText('');
});

test('keeps GraphQL editing usable after invalid introspection JSON', async ({
  page,
}) => {
  const invalidSchema = page.waitForResponse((response) =>
    response.url().endsWith('/graphql-invalid-schema'),
  );
  await gotoMethod(page, 'example.GraphqlService', 'invalidSchema', 'POST');
  await invalidSchema;
  const dialog = await openDebug(page);
  const editor = dialog.locator('.monaco-editor');
  await setMonacoValue(editor, 'query { stillEditable }');
  await expect(editor.locator('.view-lines')).toContainText('stillEditable');
});

test('blocks invalid and clears empty GraphQL variables', async ({ page }) => {
  await gotoMethod(page, 'example.GraphqlService', 'execute', 'POST');
  const dialog = await openDebug(page);
  const editor = dialog.locator('.monaco-editor');
  await setMonacoValue(
    editor,
    'query ($name: String!) { greeting(name: $name) { message } }',
  );
  const variablesButton = dialog.getByRole('button', {
    name: '# Query Variables',
  });
  const variables = dialog.locator(
    'textarea.MuiInputBase-input[rows="5"]:not([aria-hidden="true"])',
  );
  if (!(await variables.isVisible())) {
    await variablesButton.click();
  }

  let executionRequests = 0;
  page.on('request', (request) => {
    if (
      request.url().endsWith('/graphql') &&
      request.postDataJSON().operationName !== 'IntrospectionQuery'
    ) {
      executionRequests += 1;
    }
  });
  await variables.fill('{"name":"Valid"}');
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await expect(dialog).toContainText('Hello, Valid');
  expect(executionRequests).toBe(1);

  await variables.fill('not-json');
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await expect(dialog).toContainText(
    'Failed to parse a JSON object in the GraphQL variables',
  );
  expect(executionRequests).toBe(1);

  await variables.fill('');
  await setMonacoValue(
    editor,
    'query { greeting(name: "Cleared") { message } }',
  );
  const clearedRequest = page.waitForRequest((request) => {
    if (!request.url().endsWith('/graphql')) {
      return false;
    }
    return request.postDataJSON().operationName !== 'IntrospectionQuery';
  });
  await dialog.getByRole('button', { name: 'Submit' }).click();
  expect((await clearedRequest).postDataJSON().variables).toEqual({});
  await expect(dialog).toContainText('Hello, Cleared');
});

test('uses one GraphQL schema owner for inline and dialog editors', async ({
  page,
}) => {
  let introspectionRequests = 0;
  page.on('request', (request) => {
    if (
      request.url().endsWith('/graphql') &&
      request.postDataJSON().operationName === 'IntrospectionQuery'
    ) {
      introspectionRequests += 1;
    }
  });
  const schemaResponse = page.waitForResponse((response) =>
    response.url().endsWith('/graphql'),
  );
  await gotoMethod(page, 'example.GraphqlService', 'execute', 'POST');
  await schemaResponse;

  let editor = page.locator('.monaco-editor');
  await setMonacoValue(editor, '{ }');
  let input = editor.locator('textarea.inputarea');
  await input.press('ArrowLeft');
  await expectMonacoSuggestion(page, editor, 'greeting');
  await input.press('Escape');

  const dialog = await openDebug(page);
  await dialog.getByRole('button', { name: 'Close' }).click();
  await page.waitForLoadState('networkidle');
  expect(introspectionRequests).toBe(1);

  editor = page.locator('.monaco-editor');
  await setMonacoValue(editor, '{ }');
  input = editor.locator('textarea.inputarea');
  await input.press('ArrowLeft');
  await expectMonacoSuggestion(page, editor, 'greeting');
});
