/* eslint-disable no-await-in-loop -- User interactions must run in order. */
import versions from '../gen-src/versions.json';
import { expect, test, visit } from './fixtures';

test('build tabs show the selected syntax and synchronize related examples', async ({
  page,
}) => {
  await visit(page, '/docs/setup');
  const examples = page.locator('.tabs-container');
  const first = examples.first();
  const version = versions['com.linecorp.armeria:armeria-bom'];
  expect(version).toMatch(/^\d+\.\d+\.\d+/);
  for (const [label, title, syntax, compilerConfiguration] of [
    ['Gradle', 'build.gradle', 'apply plugin:', 'tasks.withType(JavaCompile)'],
    [
      'Gradle (Kotlin)',
      'build.gradle.kts',
      'apply(plugin =',
      'tasks.withType<JavaCompile>',
    ],
    [
      'Maven',
      'pom.xml',
      '<artifactId>armeria</artifactId>',
      'maven-compiler-plugin',
    ],
  ]) {
    await first.getByRole('tab', { name: label, exact: true }).click();
    await expect(first.getByRole('tabpanel')).toContainText(title);
    await expect(first.getByRole('tabpanel').locator('pre')).toContainText(
      syntax,
    );
    await expect(first.getByRole('tabpanel').locator('pre')).toContainText(
      version,
    );
    await expect(first.getByRole('tabpanel')).not.toContainText('undefined');
    await expect(
      examples.nth(1).getByRole('tab', { name: label, exact: true }),
    ).toHaveAttribute('aria-selected', 'true');
    await expect(examples.nth(1).getByRole('tabpanel')).toContainText(
      compilerConfiguration,
    );
  }
  await page.reload();
  await expect(
    first.getByRole('tab', { name: 'Maven', exact: true }),
  ).toHaveAttribute('aria-selected', 'true');
  await first.getByRole('tab', { name: 'Maven', exact: true }).focus();
  await page.keyboard.press('ArrowRight');
  await expect(
    first.getByRole('tab', { name: 'Gradle', exact: true }),
  ).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(first.getByRole('tabpanel').locator('pre')).toContainText(
    'apply plugin:',
  );
});

test('copy code writes the displayed example to the clipboard', async ({
  page,
  context,
}) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await visit(page, '/docs/server/basics');
  const code = page.locator('pre').first();
  const expected = (await code.locator('.token-line').allTextContents())
    .join('\n')
    .trim();
  expect(expected).toContain('ServerBuilder sb = Server.builder();');
  await code.hover();
  await page
    .getByRole('button', { name: 'Copy code to clipboard', exact: true })
    .first()
    .click();
  await expect(
    page.getByRole('button', { name: 'Copied', exact: true }),
  ).toBeVisible();
  await expect
    .poll(async () =>
      (await page.evaluate(() => navigator.clipboard.readText())).trim(),
    )
    .toBe(expected);
});

test('documentation sidebar expands categories and follows the selected page', async ({
  page,
  isMobile,
}) => {
  await visit(page, '/docs');
  if (isMobile) {
    await page.getByRole('button', { name: 'Toggle navigation bar' }).click();
  }
  const sidebar = isMobile
    ? page.locator('.navbar-sidebar')
    : page.getByRole('navigation', { name: 'Docs sidebar', exact: true });
  await sidebar
    .getByRole('button', {
      name: "Expand sidebar category 'Server'",
      exact: true,
    })
    .click();
  await expect(
    sidebar.getByRole('link', { name: 'Server basics', exact: true }),
  ).toBeVisible();
  await sidebar
    .getByRole('button', {
      name: "Collapse sidebar category 'Server'",
      exact: true,
    })
    .click();
  await expect(
    sidebar.getByRole('link', { name: 'Server basics', exact: true }),
  ).not.toBeVisible();
  await sidebar
    .getByRole('button', {
      name: "Expand sidebar category 'Server'",
      exact: true,
    })
    .click();
  await sidebar
    .getByRole('link', { name: 'Server basics', exact: true })
    .click();
  await expect(page).toHaveURL(/\/docs\/server\/basics\/?$/);
  await expect(
    page.getByRole('heading', { name: 'Server basics', exact: true }),
  ).toBeVisible();
  if (isMobile) {
    await expect(sidebar).not.toBeVisible();
    await page.getByRole('button', { name: 'Toggle navigation bar' }).click();
  }
  await expect(
    sidebar.getByRole('link', { name: 'Server basics', exact: true }),
  ).toHaveAttribute('aria-current', 'page');
  await sidebar
    .getByRole('button', {
      name: "Expand sidebar category 'Client'",
      exact: true,
    })
    .click();
  await expect(
    sidebar.getByRole('link', { name: 'Server basics', exact: true }),
  ).not.toBeVisible();
});
/* eslint-enable no-await-in-loop */
