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
  openDebug,
  setMonacoValue,
} from './helpers';
import { expect, test } from './test-fixture';

test('offers JSON schema completion and sends an unframed gRPC request', async ({
  page,
}) => {
  await gotoMethod(page, 'example.GrpcService', 'greet', 'POST');
  const dialog = await openDebug(page);
  const editor = dialog.locator('.monaco-editor');
  await expect(editor).toBeVisible();

  await setMonacoValue(editor, '{}');
  await editor.locator('textarea.inputarea').press('ArrowLeft');
  await expectMonacoSuggestion(page, editor, 'name');
  await editor.locator('textarea.inputarea').press('Escape');

  await setMonacoValue(editor, '{"name":"Browser","count":3}');
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await expect(dialog).toContainText('"method": "POST"');
  await expect(dialog).toContainText('"path": "/example.GrpcService/Greet"');
  await expect(dialog).toContainText(
    '"content-type": "application/json; charset=utf-8"',
  );
  await expect(dialog).toContainText(
    '"x-e2e-injected": "from-injected-script"',
  );
  await expect(dialog).toContainText('"name": "Browser"');
});

test('offers JSON schema completion and sends a Thrift JSON request', async ({
  context,
  page,
}) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await gotoMethod(page, 'example.ThriftService', 'greet', 'POST');
  const dialog = await openDebug(page);
  const editor = dialog.locator('.monaco-editor');

  await setMonacoValue(editor, '{}');
  await editor.locator('textarea.inputarea').press('ArrowLeft');
  await expectMonacoSuggestion(page, editor, 'name');
  await editor.locator('textarea.inputarea').press('Escape');

  await setMonacoValue(editor, '{"name":"Browser"}');
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await expect(dialog).toContainText('"path": "/thrift"');
  await expect(dialog).toContainText('"method": "ExampleService:greet"');
  await expect(dialog).toContainText('"type": "CALL"');
  await expect(dialog).toContainText('"name": "Browser"');

  await dialog.getByRole('button', { name: 'Copy as a curl command' }).click();
  const curl = await page.evaluate(() => navigator.clipboard.readText());
  expect(curl).toContain('application/x-thrift; protocol=TTEXT');
  expect(curl).toContain('ExampleService:greet');
});

test('keeps RPC debugging usable when JSON schemas are unavailable', async ({
  page,
}) => {
  await gotoMethod(
    page,
    'example.GrpcService',
    'greet',
    'POST',
    '/schema-error/',
  );
  const dialog = await openDebug(page);
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await expect(dialog).toContainText('"name": "Armeria"');
});

test('sends a Thrift method without a service fragment', async ({ page }) => {
  await gotoMethod(page, 'example.ThriftService', 'plain', 'POST');
  const dialog = await openDebug(page);
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await expect(dialog).toContainText('"path": "/thrift-plain"');
  await expect(dialog).toContainText('"method": "plain"');
});

test('supports inline and absent method schemas', async ({ page }) => {
  await gotoMethod(page, 'example.GrpcService', 'inline', 'POST');
  let dialog = await openDebug(page);
  let editor = dialog.locator('.monaco-editor');
  await setMonacoValue(editor, '{}');
  await editor.locator('textarea.inputarea').press('ArrowLeft');
  await expectMonacoSuggestion(page, editor, 'inlineName');
  await editor.locator('textarea.inputarea').press('Escape');
  await setMonacoValue(editor, '{"inlineName":"value"}');
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await expect(dialog).toContainText('"inlineName": "value"');
  await dialog.getByRole('button', { name: 'Close' }).click();

  await gotoMethod(page, 'example.GrpcService', 'missingSchema', 'POST');
  dialog = await openDebug(page);
  editor = dialog.locator('.monaco-editor');
  await setMonacoValue(editor, '{}');
  await dialog.getByRole('button', { name: 'Submit' }).click();
  await expect(dialog).toContainText(
    '"path": "/example.GrpcService/MissingSchema"',
  );
});

test('does not restore a retained request model when revisiting a method', async ({
  page,
}) => {
  await gotoMethod(page, 'example.GrpcService', 'inline', 'POST');
  let dialog = await openDebug(page);
  let editor = dialog.locator('.monaco-editor');
  await setMonacoValue(editor, '{"inlineName":"stale"}');
  await dialog.getByRole('button', { name: 'Close' }).click();

  await gotoMethod(page, 'example.GrpcService', 'missingSchema', 'POST');
  dialog = await openDebug(page);
  editor = dialog.locator('.monaco-editor');
  await expect(editor.locator('.view-lines')).toHaveText('');
  await dialog.getByRole('button', { name: 'Close' }).click();

  await gotoMethod(page, 'example.GrpcService', 'inline', 'POST');
  dialog = await openDebug(page);
  editor = dialog.locator('.monaco-editor');
  await expect(editor.locator('.view-lines')).toHaveText('');
});

test('synchronizes a retained request model after remounting', async ({
  page,
}) => {
  await gotoMethod(page, 'example.GrpcService', 'inline', 'POST');
  let dialog = await openDebug(page);
  let editor = dialog.locator('.monaco-editor');
  await setMonacoValue(editor, '{"inlineName":"stale"}');
  await dialog.getByRole('button', { name: 'Close' }).click();

  await page.goto('/docs/#/');
  await expect(page).toHaveURL(/\/docs\/#\/$/);
  await gotoMethod(page, 'example.GrpcService', 'inline', 'POST');
  dialog = await openDebug(page);
  editor = dialog.locator('.monaco-editor');
  await expect(editor.locator('.view-lines')).toHaveText('');
});
