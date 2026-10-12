/* eslint-disable no-await-in-loop -- User interactions must run in order. */
import { readFileSync, readdirSync } from 'fs';
import path from 'path';
import matter from 'gray-matter';
import { expect, test, visit } from './fixtures';

const latestPostTitle = (language: string) => {
  const directory = path.join(__dirname, '../src/content/blog', language);
  return readdirSync(directory)
    .filter((file) => file.endsWith('.mdx'))
    .map((file) => {
      const { data, content } = matter(
        readFileSync(path.join(directory, file), 'utf8'),
      );
      return {
        date: new Date(data.date ?? file.substring(0, 10)).getTime(),
        title: content.match(/^# (.+)$/m)![1],
      };
    })
    .sort((a, b) => b.date - a.date)[0].title;
};

test('blog language selection updates the list and survives a reload', async ({
  page,
  isMobile,
}) => {
  await visit(page, '/blog');
  let currentLanguage = 'English';
  for (const [language, destination, sourceLanguage] of [
    ['日本語', '/blog/ja', 'ja'],
    ['한국어', '/blog/ko', 'ko'],
    ['English', '/blog', 'en'],
  ]) {
    if (isMobile) {
      await page
        .getByRole('button', { name: 'Toggle navigation bar', exact: true })
        .click();
    }
    const selector = page.getByRole('button', {
      name: new RegExp(`^${currentLanguage}(?: down)?$`),
    });
    await expect(selector).toBeVisible();
    if (isMobile) {
      await selector.tap();
    } else {
      await selector.hover();
    }
    // The link can appear stable before the popup's animation finishes.
    await expect(page.locator('.ant-dropdown')).toHaveCSS('transform', 'none');
    await page
      .getByRole('menuitem', { name: language, exact: true })
      .getByRole('link')
      .click();
    await expect(page).toHaveURL(new RegExp(`${destination}/?$`));
    await expect(
      page.getByRole('main').getByRole('article').first(),
    ).toBeVisible();
    const title = latestPostTitle(sourceLanguage);
    await expect(
      page
        .getByRole('main')
        .getByRole('article')
        .first()
        .locator('header')
        .getByRole('heading', { level: 2 }),
    ).toHaveText(title);
    await page.reload();
    await expect(
      page
        .getByRole('main')
        .getByRole('article')
        .first()
        .locator('header')
        .getByRole('heading', { level: 2 }),
    ).toHaveText(title);
    if (isMobile) {
      await page
        .getByRole('button', { name: 'Toggle navigation bar', exact: true })
        .click();
    }
    await expect(
      page.getByRole('button', { name: new RegExp(`^${language}(?: down)?$`) }),
    ).toBeVisible();
    if (isMobile) {
      await page
        .getByRole('button', { name: 'Close navigation bar', exact: true })
        .click();
    }
    currentLanguage = language;
  }
});

test('blog list opens an article and browser history restores the list', async ({
  page,
}) => {
  await visit(page, '/blog');
  const article = page.getByRole('main').getByRole('article').first();
  const excerptElements = await article
    .locator('p, pre, ul, ol, table')
    .count();
  const title = (
    await article
      .locator('header')
      .getByRole('heading', { level: 2 })
      .innerText()
  ).trim();
  const destination = await article
    .locator('header')
    .getByRole('heading', { level: 2 })
    .getByRole('link')
    .getAttribute('href');
  await article
    .getByRole('link', { name: `Read more about ${title}`, exact: true })
    .click();
  await expect(page).toHaveURL((url) => url.pathname === destination);
  await expect(
    page.getByRole('heading', { name: title, exact: true, level: 1 }),
  ).toBeVisible();
  await expect
    .poll(() =>
      page.getByRole('article').locator('p, pre, ul, ol, table').count(),
    )
    .toBeGreaterThan(excerptElements);
  await page.reload();
  await expect(
    page.getByRole('heading', { name: title, exact: true, level: 1 }),
  ).toBeVisible();
  await page.goBack();
  await expect(page).toHaveURL(/\/blog\/?$/);
  await expect(
    page
      .getByRole('main')
      .getByRole('article')
      .first()
      .getByRole('heading', { level: 2, name: title, exact: true }),
  ).toHaveText(title);
});
/* eslint-enable no-await-in-loop */
