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

import { Locator, Page } from '@playwright/test';

import { expect } from './test-fixture';

export const selectAllShortcut =
  process.platform === 'darwin' ? 'Meta+A' : 'Control+A';

export function methodUrl(
  serviceName: string,
  methodName: string,
  httpMethod: string,
  root = '/docs/',
) {
  return `${root}#/methods/${serviceName}/${methodName}/${httpMethod}`;
}

export async function gotoMethod(
  page: Page,
  serviceName: string,
  methodName: string,
  httpMethod: string,
  root = '/docs/',
) {
  await page.goto(methodUrl(serviceName, methodName, httpMethod, root));
  await expect(
    page.getByText(
      `${serviceName.substring(
        serviceName.lastIndexOf('.') + 1,
      )}.${methodName}()`,
    ),
  ).toBeVisible();
}

export async function openDebug(page: Page) {
  await page.getByRole('button', { name: 'Debug', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  return dialog;
}

export async function chooseSelect(
  page: Page,
  scope: Locator,
  prompt: string,
  option: string,
) {
  await scope.getByText(prompt, { exact: true }).click();
  await page.getByRole('option', { name: option, exact: true }).click();
}

export async function setMonacoValue(editor: Locator, value: string) {
  const input = editor.locator('textarea.inputarea');
  await input.focus();
  await input.press(selectAllShortcut);
  await input.press('Backspace');
  await input.pressSequentially(value);
}

export async function expectMonacoSuggestion(
  page: Page,
  editor: Locator,
  label: string,
) {
  const input = editor.locator('textarea.inputarea');
  await input.press('Control+Space');
  const suggestion = page
    .locator('.suggest-widget.visible')
    .getByText(label, { exact: true })
    .first();
  await expect(suggestion).toBeVisible();
  return suggestion;
}
