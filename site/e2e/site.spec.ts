import { readFileSync, readdirSync } from 'fs';
import path from 'path';
import { expect, test, visit } from './fixtures';

const latestContent = (directory: string, pattern: RegExp) => {
  const contentDirectory = path.join(__dirname, '../src/content', directory);
  const file = readdirSync(contentDirectory)
    .filter((name) => pattern.test(name))
    .sort((a, b) => b.localeCompare(a, 'en', { numeric: true }))[0];
  const heading = readFileSync(path.join(contentDirectory, file), 'utf8').match(
    /^# (.+)$/m,
  )![1];
  return {
    destination: `/${directory}/${file.replace(/\.mdx$/, '')}`,
    heading,
  };
};

test('home page leads to documentation and community', async ({ page }) => {
  await visit(page, '/');
  await expect(
    page.getByRole('heading', { name: /Build a reactive microservice/ }),
  ).toBeVisible();
  await page
    .getByRole('link', { name: /Learn more/ })
    .first()
    .click();
  await expect(page).toHaveURL(/\/docs\/?$/);
  await expect(
    page.getByRole('heading', { name: 'What is Armeria?', exact: true }),
  ).toBeVisible();
  await page.goBack();
  await page
    .getByRole('main')
    .getByRole('link', { name: /Community/ })
    .first()
    .click();
  await expect(page).toHaveURL(/\/community\/?$/);
  await expect(
    page.getByRole('heading', { name: 'Contributor manual', exact: true }),
  ).toBeVisible();
});

test('documentation links and heading anchors survive a reload', async ({
  page,
  context,
}) => {
  await visit(page, '/docs/server/basics');
  await expect(
    page.getByRole('heading', { name: 'Server basics', exact: true }),
  ).toBeVisible();
  const portsHeading = page.getByRole('heading', { name: /^Ports/ });
  await portsHeading.scrollIntoViewIfNeeded();
  await portsHeading.hover();
  await page
    .getByRole('link', { name: 'Direct link to Ports', exact: true })
    .click();
  await expect(page).toHaveURL(/#ports$/);
  await page.reload();
  await expect(page.getByRole('heading', { name: /^Ports/ })).toBeInViewport();
  const linkedPage = await context.newPage();
  await linkedPage.goto('/docs/server/basics#ports');
  await expect(
    linkedPage.getByRole('heading', { name: /^Ports/ }),
  ).toBeInViewport();
  await linkedPage.close();
  await expect(
    page.getByRole('link', { name: 'ServerBuilder', exact: true }).first(),
  ).toHaveAttribute('href', /https:\/\/javadoc\.io\/.*ServerBuilder\.html/);
  await page.getByRole('link', { name: /Next.*Decorating a service/ }).click();
  await expect(page).toHaveURL(/\/docs\/server\/decorator\/?$/);
  await expect(
    page.getByRole('heading', { name: 'Decorating a service', exact: true }),
  ).toBeVisible();
});

for (const [legacy, destination, title] of [
  ['/docs/server-basics', '/docs/server/basics', 'Server basics'],
  ['/docs/client-retry', '/docs/client/retry', 'Automatic retry'],
  [
    '/docs/advanced-unit-testing',
    '/docs/advanced/unit-testing',
    'Unit-testing Client and Service',
  ],
] as const) {
  test(`${legacy} redirects to the current document`, async ({ page }) => {
    await page.goto(legacy);
    await expect(page).toHaveURL(
      (url) => url.pathname.replace(/\/$/, '') === destination,
    );
    await expect(
      page.getByRole('heading', { name: title, exact: true }),
    ).toBeVisible();
  });
}

for (const [root, label, content] of [
  ['/news', 'Newsletter', latestContent('news', /^\d{8}-newsletter-\d+\.mdx$/)],
  [
    '/release-notes',
    'Release notes',
    latestContent('release-notes', /^\d+\.\d+\.\d+\.mdx$/),
  ],
] as const) {
  test(`${label} navigation and redirect open the latest source content`, async ({
    page,
    isMobile,
  }) => {
    await visit(page, '/');
    if (isMobile) {
      await page.getByRole('button', { name: 'Toggle navigation bar' }).click();
    }
    await page.getByRole('button', { name: 'News', exact: true }).click();
    const link = page.getByRole('link', { name: label, exact: true });
    await expect(link).toHaveAttribute('href', content.destination);
    await link.click();
    await expect(
      page.getByRole('heading', { name: content.heading, exact: true }),
    ).toBeVisible();
    await page.goto(root);
    await expect(page).toHaveURL(
      (url) => url.pathname.replace(/\/$/, '') === content.destination,
    );
    await expect(
      page.getByRole('heading', { name: content.heading, exact: true }),
    ).toBeVisible();
  });
}

test('theme selection persists after navigation and reload', async ({
  page,
  isMobile,
}) => {
  await page.goto('/docs');
  if (isMobile) {
    await page.getByRole('button', { name: 'Toggle navigation bar' }).click();
  }
  const html = page.locator('html');
  const originalTheme = await html.getAttribute('data-theme');
  await page
    .getByRole('button', { name: /Switch between dark and light mode/ })
    .click();
  const selectedTheme = originalTheme === 'dark' ? 'light' : 'dark';
  await expect(html).toHaveAttribute('data-theme', selectedTheme);
  await page.goto('/docs/server/basics');
  await expect(html).toHaveAttribute('data-theme', selectedTheme);
  await page.reload();
  await expect(html).toHaveAttribute('data-theme', selectedTheme);
});

test('mobile navigation opens, navigates and closes', async ({
  page,
  isMobile,
}) => {
  test.skip(
    !isMobile,
    'Mobile menu is only available below the desktop breakpoint.',
  );
  await page.goto('/');
  await page.getByRole('button', { name: 'Toggle navigation bar' }).click();
  const sidebar = page.locator('.navbar-sidebar');
  await expect(sidebar).toBeVisible();
  await sidebar
    .getByRole('link', { name: 'Documentation', exact: true })
    .click();
  await expect(page).toHaveURL(/\/docs\/?$/);
  await expect(
    page.getByRole('heading', { name: 'What is Armeria?', exact: true }),
  ).toBeVisible();
  await expect(sidebar).not.toBeVisible();
});

test('unknown URLs show the 404 page and allow returning home', async ({
  page,
}) => {
  await page.goto('/e2e-page-that-does-not-exist');
  await expect(page).toHaveTitle(/Page Not Found/);
  await expect(page.getByRole('main')).toContainText('4');
  await expect(page.getByRole('main').getByLabel('0')).toBeVisible();
  await page.getByRole('link', { name: 'Armeria Logo' }).click();
  await expect(page).toHaveURL(/\/$/);
  await expect(
    page.getByRole('heading', { name: /Build a reactive microservice/ }),
  ).toBeVisible();
});
