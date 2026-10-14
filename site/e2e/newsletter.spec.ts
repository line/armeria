import type { Route } from '@playwright/test';
import { expect, test, visit } from './fixtures';

const subscriptionURL =
  /https:\/\/[^/]+\.list-manage\.com\/subscribe\/post-json/;
const email = 'reader+e2e@example.com';

const subscriptionRequest = (route: Route) => {
  const url = new URL(route.request().url());
  expect(url.hostname).toBe('github.us19.list-manage.com');
  expect(url.pathname).toBe('/subscribe/post-json');
  expect(url.searchParams.get('u')).toBe('3447f8227584634e6ee046edf');
  expect(url.searchParams.get('id')).toBe('852d70ccdc');
  expect(url.searchParams.get('b_3447f8227584634e6ee046edf_852d70ccdc')).toBe(
    '',
  );
  expect(url.searchParams.get('c')).toMatch(/^__jp\d+$/);
  return url;
};

test('newsletter rejects invalid email without sending a request', async ({
  page,
}) => {
  const requests: string[] = [];
  await page.route(subscriptionURL, async (route) => {
    requests.push(route.request().url());
    await route.abort();
  });
  await visit(page, '/docs');
  const footer = page.getByRole('contentinfo');
  await footer
    .getByRole('searchbox', { name: 'Your e-mail' })
    .fill('invalid-email');
  await footer.getByRole('button', { name: /Subscribe$/ }).click();
  await expect(
    page.getByText('Please enter a valid e-mail address.', { exact: true }),
  ).toBeVisible();
  await expect(
    footer.getByRole('searchbox', { name: 'Your e-mail' }),
  ).toHaveValue('invalid-email');
  expect(requests).toEqual([]);
});

test('newsletter sends the email once, shows loading and clears the field on success', async ({
  page,
}) => {
  const requests: string[] = [];
  let release = () => {};
  const pending = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route(subscriptionURL, async (route) => {
    const url = subscriptionRequest(route);
    requests.push(url.searchParams.get('EMAIL')!);
    await pending;
    await route.fulfill({
      contentType: 'application/javascript',
      body: `${url.searchParams.get('c')}(${JSON.stringify({ result: 'success' })});`,
    });
  });
  await visit(page, '/docs');
  const footer = page.getByRole('contentinfo');
  const input = footer.getByRole('searchbox', { name: 'Your e-mail' });
  const button = footer.getByRole('button', { name: /Subscribe$/ });
  await input.fill(email);
  await button.click();
  try {
    await expect.poll(() => requests).toEqual([email]);
    await expect(button).toHaveClass(/ant-btn-loading/);
    await button.click();
    await input.press('Enter');
    expect(requests).toEqual([email]);
  } finally {
    release();
  }
  await expect(page.getByText(/Thank you for signing up!/)).toBeVisible();
  await expect(input).toHaveValue('');
  await expect(button).not.toHaveClass(/ant-btn-loading/);
  expect(requests).toEqual([email]);
});

test('newsletter preserves the email after rejection and permits retry', async ({
  page,
}) => {
  const requests: string[] = [];
  await page.route(subscriptionURL, async (route) => {
    const url = subscriptionRequest(route);
    requests.push(url.searchParams.get('EMAIL')!);
    const response =
      requests.length === 1
        ? { result: 'error', msg: 'Subscription failed. Please retry.' }
        : { result: 'success' };
    await route.fulfill({
      contentType: 'application/javascript',
      body: `${url.searchParams.get('c')}(${JSON.stringify(response)});`,
    });
  });
  await visit(page, '/docs');
  const footer = page.getByRole('contentinfo');
  const input = footer.getByRole('searchbox', { name: 'Your e-mail' });
  const button = footer.getByRole('button', { name: /Subscribe$/ });
  await input.fill(email);
  await button.click();
  await expect(
    page.getByText('Subscription failed. Please retry.', { exact: true }),
  ).toBeVisible();
  await expect(input).toHaveValue(email);
  await expect(button).not.toHaveClass(/ant-btn-loading/);
  await button.click();
  await expect(page.getByText(/Thank you for signing up!/)).toBeVisible();
  await expect(input).toHaveValue('');
  expect(requests).toEqual([email, email]);
});

test('newsletter reports a network timeout and releases the loading state', async ({
  page,
}) => {
  let requested = false;
  await page.route(subscriptionURL, async (route) => {
    subscriptionRequest(route);
    requested = true;
    await route.abort();
  });
  await visit(page, '/docs');
  await page.clock.install();
  const footer = page.getByRole('contentinfo');
  const input = footer.getByRole('searchbox', { name: 'Your e-mail' });
  const button = footer.getByRole('button', { name: /Subscribe$/ });
  await input.fill(email);
  await button.click();
  await expect.poll(() => requested).toBe(true);
  await expect(button).toHaveClass(/ant-btn-loading/);
  await page.clock.fastForward(60001);
  await expect(
    page.getByText('Failed to sign up. Please try again later.', {
      exact: true,
    }),
  ).toBeVisible();
  await expect(input).toHaveValue(email);
  await expect(button).not.toHaveClass(/ant-btn-loading/);
});
