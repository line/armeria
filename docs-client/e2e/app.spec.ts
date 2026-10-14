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

import { expect, test } from './test-fixture';

/* eslint-disable no-await-in-loop */

test('shows loading, title, injected script, and complete version information', async ({
  page,
}) => {
  let releaseSpecification: () => void = () => {};
  const specificationReleased = new Promise<void>((resolve) => {
    releaseSpecification = resolve;
  });
  let releaseVersions: () => void = () => {};
  const versionsReleased = new Promise<void>((resolve) => {
    releaseVersions = resolve;
  });
  await page.route('**/slow-docs/specification.json', async (route) => {
    const response = await route.fetch();
    await specificationReleased;
    await route.fulfill({ response });
  });
  await page.route('**/slow-docs/versions.json', async (route) => {
    const response = await route.fetch();
    await versionsReleased;
    await route.fulfill({ response });
  });

  await page.goto('/slow-docs/');
  await expect(page.getByRole('progressbar')).toBeVisible();
  releaseSpecification();

  await expect(
    page.getByText('Welcome to the Armeria documentation service'),
  ).toBeVisible();
  await expect(page.getByText('Version information')).toHaveCount(0);
  releaseVersions();

  await expect(page).toHaveTitle(
    'E2E Docs - Armeria documentation service 1.2.3',
  );
  await expect(
    page.getByRole('cell', { name: 'armeria', exact: true }),
  ).toBeVisible();
  await expect(page.getByText('1.2.4(!)', { exact: true })).toBeVisible();
  await expect(page.getByText('Unknown', { exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'abcdef0' })).toHaveAttribute(
    'href',
    'https://github.com/line/armeria/commit/abcdef0123456789',
  );
  const readableTime = page.getByText(/ago$/, { exact: true });
  await readableTime.hover();
  await expect(page.getByRole('tooltip')).toContainText('GMT');
  await expect(
    page.evaluate(() => {
      const globals = window as unknown as Record<string, unknown>;
      // eslint-disable-next-line @typescript-eslint/dot-notation
      return globals['__e2eInjected'];
    }),
  ).resolves.toBe(true);
});

test('navigates every desktop section and Go to autocomplete group', async ({
  page,
}) => {
  await page.goto('/docs/');
  await expect(page.getByText('Services', { exact: true })).toBeVisible();

  await page.getByText('Services', { exact: true }).click();
  await expect(page.getByText('Overview', { exact: true })).toBeHidden();
  await page.getByText('Services', { exact: true }).click();
  await page.getByText('Overview', { exact: true }).click();
  await expect(
    page.getByText('Overview', { exact: true }).last(),
  ).toBeVisible();

  const navigation = page.getByRole('navigation');
  await navigation.getByText('HttpService', { exact: true }).click();
  await expect(navigation.getByText('hello()', { exact: true })).toBeHidden();
  await navigation.getByText('HttpService', { exact: true }).click();
  for (const section of [
    { name: 'Enums', item: 'State' },
    { name: 'Structs', item: 'Message' },
    { name: 'Exceptions', item: 'ServiceException' },
  ]) {
    await navigation.getByText(section.name, { exact: true }).click();
    await expect(
      navigation.getByText(section.item, { exact: true }),
    ).toBeHidden();
    await navigation.getByText(section.name, { exact: true }).click();
    await expect(
      navigation.getByText(section.item, { exact: true }),
    ).toBeVisible();
  }

  const goTo = page.getByPlaceholder('Go to ...');
  const destinations = [
    {
      search: 'State',
      option: 'example.State',
      heading: 'State',
    },
    {
      search: 'Request',
      option: 'example.Request',
      heading: 'Request',
    },
    {
      search: 'ServiceException',
      option: 'example.ServiceException',
      heading: 'ServiceException',
    },
    {
      search: 'GraphqlService#execute',
      option: 'example.GraphqlService#execute|POST',
      heading: 'GraphqlService.execute()',
    },
  ];
  for (const destination of destinations) {
    await goTo.fill(destination.search);
    await expect(
      page.getByRole('option', { name: destination.option, exact: true }),
    ).toBeVisible();
    await goTo.press('Enter');
    await expect(
      page.getByRole('main').getByText(destination.heading, { exact: true }),
    ).toBeVisible();
  }

  await page.waitForLoadState('networkidle');
  await goTo.fill('nothing matches this');
  await expect(page.getByText('No results', { exact: true })).toBeVisible();
});

test('redirects a development URL to its trailing-slash form', async ({
  page,
}) => {
  await page.goto('/docs');
  await expect(page).toHaveURL(/\/docs\/#\/$/);
  await expect(
    page.getByText('Welcome to the Armeria documentation service'),
  ).toBeVisible();
});

test('supports legacy routes, missing resources, duplicate names, and empty docs', async ({
  page,
}) => {
  const legacyParams = new URLSearchParams({
    debug_form_is_open: 'true',
    endpoint_path: '/echo/legacy',
    queries: 'legacy=true',
    request_body: '{}',
  });
  await page.goto(
    `/docs/#/method/example.HttpService/echo/POST?${legacyParams.toString()}`,
  );
  await expect(page).toHaveURL(
    /#\/methods\/example\.HttpService\/echo\/POST\?.*debug_form_is_open=true/,
  );
  await expect(page.getByRole('dialog')).toBeVisible();

  await page.goto('/docs/#/namedType/example.Message');
  await expect(page).toHaveURL(/#\/structs\/example\.Message$/);
  await page.goto('/docs/#/namedType/example.State');
  await expect(page).toHaveURL(/#\/enums\/example\.State$/);

  for (const missing of [
    '/docs/#/methods/example.Missing/nope/GET',
    '/docs/#/structs/example.Missing',
    '/docs/#/enums/example.Missing',
  ]) {
    await page.goto(missing);
    await expect(page.getByText('Not found.', { exact: true })).toBeVisible();
  }

  await page.goto('/duplicates/');
  await expect(
    page
      .getByRole('button')
      .filter({ hasText: 'alpha' })
      .filter({ hasText: 'DuplicateService' }),
  ).toBeVisible();
  await expect(
    page
      .getByRole('button')
      .filter({ hasText: 'beta' })
      .filter({ hasText: 'DuplicateService' }),
  ).toBeVisible();
  await expect(
    page.getByText('alpha.DuplicateEnum', { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText('beta.DuplicateStruct', { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText('alpha.DuplicateException', { exact: true }),
  ).toBeVisible();

  await page.goto('/empty-docs/#/overview');
  await expect(page).toHaveTitle('Armeria documentation service ');
  await expect(page.getByText('There are no services.')).toBeVisible();
  await expect(page.getByText('Services', { exact: true })).toHaveCount(1);
});

test('shows specification failure and continues after schema failure', async ({
  page,
}) => {
  await page.goto('/spec-error/');
  await expect(
    page.getByText('Failed to load specifications. Try refreshing!'),
  ).toBeVisible();

  await page.goto('/schema-error/#/methods/example.GrpcService/greet/POST');
  await expect(page.getByText('GrpcService.greet()')).toBeVisible();
  await page.getByRole('button', { name: 'Debug', exact: true }).click();
  await expect(
    page.getByRole('dialog').locator('.monaco-editor'),
  ).toBeVisible();
});

test('opens the mobile drawer and closes it after navigation', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/docs/');
  const goTo = page.getByPlaceholder('Go to ...');
  await expect(goTo).toBeVisible();
  const goToBox = await goTo.boundingBox();
  expect(goToBox).not.toBeNull();
  expect(goToBox!.x + goToBox!.width).toBeLessThanOrEqual(390);
  await goTo.fill('State');
  await goTo.press('Enter');
  await expect(
    page.getByRole('main').getByText('State', { exact: true }),
  ).toBeVisible();

  const menuButton = page.getByRole('banner').getByRole('button').first();
  await menuButton.click();
  const temporaryDrawer = page.locator('.MuiDrawer-modal');
  await expect(temporaryDrawer).toBeVisible();
  await temporaryDrawer.getByText('HttpService', { exact: true }).click();
  await temporaryDrawer.getByText('hello()', { exact: true }).click();
  await expect(page.getByText('HttpService.hello()')).toBeVisible();
  await expect(temporaryDrawer).toBeHidden();
});

/* eslint-enable no-await-in-loop */
