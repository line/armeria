import { expect, test, visit } from './fixtures';

test('cookie acceptance is saved and hides the banner on later visits', async ({
  page,
  context,
}) => {
  await visit(page, '/docs');
  await page
    .getByRole('button', { name: 'Accept cookies', exact: true })
    .click();
  await expect(
    page.getByRole('button', { name: 'Accept cookies', exact: true }),
  ).not.toBeVisible();
  expect(await context.cookies()).toEqual(
    expect.arrayContaining([
      expect.objectContaining({
        name: 'CookieConsent',
        value: 'true',
        sameSite: 'Strict',
      }),
    ]),
  );
  await page.reload();
  await expect(
    page.getByRole('button', { name: 'Accept cookies', exact: true }),
  ).not.toBeVisible();
  await visit(page, '/community');
  await expect(
    page.getByRole('button', { name: 'Accept cookies', exact: true }),
  ).not.toBeVisible();
});

test('cookie opt-out opens help and is retained after a reload', async ({
  page,
  context,
}) => {
  const helpURL = 'https://tools.google.com/dlpage/gaoptout/';
  await context.route(helpURL, (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: '<title>Opt-out help</title>',
    }),
  );
  await visit(page, '/docs');
  const popup = page.waitForEvent('popup');
  await page
    .getByRole('button', { name: 'Decline cookies', exact: true })
    .click();
  const help = await popup;
  await expect(help).toHaveURL(helpURL);
  await help.close();
  expect(await context.cookies()).toEqual(
    expect.arrayContaining([
      expect.objectContaining({ name: 'CookieConsent', value: 'false' }),
    ]),
  );
  await page.reload();
  await expect(
    page.getByRole('button', { name: 'Decline cookies', exact: true }),
  ).not.toBeVisible();
});
