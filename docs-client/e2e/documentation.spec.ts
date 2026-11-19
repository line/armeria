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

import { gotoMethod } from './helpers';
import { expect, test } from './test-fixture';

test('renders Markdown, return data, exceptions, and endpoint details', async ({
  page,
}) => {
  await gotoMethod(page, 'example.HttpService', 'hello', 'GET');

  const markdown = page.locator('.markdown-body');
  await expect(markdown.getByRole('heading', { name: 'Hello' })).toBeVisible();
  await expect(markdown.locator('del')).toHaveText('old text');
  await expect(markdown.getByRole('checkbox')).toBeChecked();
  await expect(markdown.getByRole('cell', { name: 'world' })).toBeVisible();
  await expect(
    markdown.getByRole('link', { name: 'Armeria link' }),
  ).toHaveAttribute('href', 'https://armeria.dev');
  await expect(markdown.getByText('{"message":"hello"}')).toBeVisible();

  await expect(page.getByText('There are no parameters')).toBeVisible();
  await expect(page.getByText('The returned message.')).toBeVisible();
  await expect(
    page.getByText('Thrown when the service cannot respond.'),
  ).toBeVisible();
  await expect(
    page.getByRole('cell', { name: 'api.example.com' }),
  ).toBeVisible();
  await expect(
    page.getByRole('cell', { name: 'exact:/hello', exact: true }),
  ).toBeVisible();
  const defaultMime = page.getByText('application/json; charset=utf-8', {
    exact: true,
  });
  await expect(defaultMime).toHaveCSS('font-weight', '700');

  const returnSection = page
    .getByRole('heading', { name: 'Return Type' })
    .locator('xpath=..');
  await returnSection.getByRole('row').first().click();
  await expect(page.getByText('Large identifier.')).toBeVisible();
  await page.getByRole('link', { name: 'State' }).first().click();
  await expect(page.getByText('Current state of the response.')).toBeVisible();
});

test('renders nested parameters, plain descriptions, and type links', async ({
  page,
}) => {
  await gotoMethod(page, 'example.HttpService', 'echo', 'POST');
  await expect(page.getByText('Echoes the request.')).toBeVisible();
  await expect(page.getByText(/@param request/)).toHaveCount(0);
  await expect(page.getByText('The request to echo.')).toBeVisible();

  const requestRow = page.getByRole('row').filter({
    has: page.getByText('request', { exact: true }),
  });
  await requestRow.getByRole('cell').first().click();
  await expect(page.getByText('Name to echo.')).toBeVisible();
  await expect(page.getByText('Additional details.')).toBeVisible();
  await requestRow.getByRole('link', { name: 'Request' }).click();
  await expect(page.getByText('A request with nested fields.')).toBeVisible();

  await page.getByRole('link', { name: 'Details' }).click();
  await expect(page.getByText('Nested request details.')).toBeVisible();
});

test('expands repeated gRPC message fields one level at a time', async ({
  page,
}) => {
  await gotoMethod(page, 'example.GrpcService', 'greet', 'POST');
  const methodUrl = page.url();
  const parameters = page
    .getByRole('heading', { name: 'Parameters', exact: true })
    .locator('xpath=..');
  const itemLabels = parameters.getByText('item_label', { exact: true });

  await parameters.getByRole('cell', { name: 'request', exact: true }).click();
  await parameters.getByRole('cell', { name: 'tags', exact: true }).click();
  await parameters
    .getByRole('cell', { name: 'items_by_id', exact: true })
    .click();
  await expect(itemLabels).toHaveCount(0);

  const expandItems = parameters.getByRole('button', {
    name: 'Expand items',
    exact: true,
  });
  await expect(expandItems).toHaveAttribute('aria-expanded', 'false');
  await expandItems.press('Enter');
  await expect(
    parameters.getByRole('button', { name: 'Collapse items', exact: true }),
  ).toHaveAttribute('aria-expanded', 'true');
  await expect(itemLabels).toHaveCount(1);
  const typeLink = parameters.getByRole('link', { name: 'GrpcItem' }).first();
  const typeUrl = /\/structs\/example\.GrpcItem$/;
  await expect(typeLink).toHaveAttribute('href', typeUrl);
  const [typePage] = await Promise.all([
    page.context().waitForEvent('page'),
    typeLink.click({ modifiers: ['ControlOrMeta'] }),
  ]);
  await expect(typePage).toHaveURL(typeUrl);
  await typePage.close();
  await expect(
    parameters.getByRole('button', { name: 'Collapse items', exact: true }),
  ).toHaveAttribute('aria-expanded', 'true');
  await expect(itemLabels).toBeVisible();

  const children = parameters.getByRole('cell', {
    name: 'children',
    exact: true,
  });
  await expect(children).toHaveCount(1);
  await children.click();
  await expect(itemLabels).toHaveCount(2);
  await expect(children).toHaveCount(2);

  await children.first().click();
  await expect(itemLabels).toHaveCount(1);
  await expect(page).toHaveURL(methodUrl);
  await expect(page.getByRole('button', { name: 'Debug' })).toBeVisible();
});

test('renders enum, empty types, and exception pages', async ({ page }) => {
  await page.goto('/docs/#/enums/example.State');
  await expect(
    page.getByRole('cell', { name: 'READY', exact: true }),
  ).toBeVisible();
  await expect(page.getByRole('cell', { name: '1' })).toBeVisible();
  await expect(page.getByText('Ready to serve.')).toBeVisible();
  await expect(
    page.getByRole('cell', { name: 'UNKNOWN', exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole('cell', { name: 'UNSPECIFIED', exact: true }),
  ).toBeVisible();
  await expect(page.getByText('Indented description.')).toBeVisible();

  await page.goto('/docs/#/enums/example.EmptyEnum');
  await expect(page.getByText('There are no values.')).toBeVisible();
  await page.goto('/docs/#/structs/example.EmptyStruct');
  await expect(page.getByText('There are no fields')).toBeVisible();
  await page.goto('/docs/#/structs/example.ServiceException');
  await expect(page.getByText('An example service error.')).toBeVisible();
});

test('renders Mermaid again after route changes', async ({ page }) => {
  await gotoMethod(page, 'example.GrpcService', 'greet', 'POST');
  await expect(page.locator('.mermaid svg')).toBeVisible();
  await expect(page.locator('.mermaid')).toContainText('Client');

  await gotoMethod(page, 'example.HttpService', 'hello', 'GET');
  await gotoMethod(page, 'example.GrpcService', 'greet', 'POST');
  await expect(page.locator('.mermaid svg')).toBeVisible();
});

test('renders regex endpoints and hides Debug for unsupported transports', async ({
  page,
}) => {
  await gotoMethod(page, 'example.HttpService', 'regexWithPrefix', 'GET');
  await expect(
    page.getByRole('cell', {
      name: 'prefix:/api/ regex:^/items/[0-9]+$',
      exact: true,
    }),
  ).toBeVisible();

  await gotoMethod(page, 'example.HttpService', 'trace', 'TRACE');
  await expect(page.getByRole('button', { name: 'Debug' })).toHaveCount(0);

  await gotoMethod(page, 'example.StaticService', 'download', 'GET');
  await expect(page.getByRole('button', { name: 'Debug' })).toHaveCount(0);
});
