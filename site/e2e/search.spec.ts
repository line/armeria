import type { Page } from '@playwright/test';
import { expect, test, visit } from './fixtures';

const mockSearch = async (page: Page) => {
  const requests: { query: string; indexName: string }[] = [];
  const hit = {
    objectID: 'server-basics',
    type: 'lvl1',
    url: 'https://armeria.dev/docs/server/basics',
    url_without_anchor: 'https://armeria.dev/docs/server/basics',
    anchor: null,
    content: null,
    hierarchy: {
      lvl0: 'Documentation',
      lvl1: 'Server basics',
      lvl2: null,
      lvl3: null,
      lvl4: null,
      lvl5: null,
      lvl6: null,
    },
    _highlightResult: {
      hierarchy: {
        lvl0: { value: 'Documentation', matchLevel: 'none' },
        lvl1: { value: 'Server basics', matchLevel: 'full' },
      },
    },
  };
  await page.route(
    /https:\/\/[^/]+\.algolia(?:\.net|net\.com)\/1\/indexes\/\*\/queries/,
    async (route) => {
      const httpRequest = route.request();
      const url = new URL(httpRequest.url());
      expect(url.hostname).toMatch(
        /^tasshennei-(?:dsn\.algolia\.net|[1-3]\.algolianet\.com)$/,
      );
      expect(httpRequest.method()).toBe('POST');
      expect(
        url.searchParams.get('x-algolia-application-id') ??
          httpRequest.headers()['x-algolia-application-id'],
      ).toBe('TASSHENNEI');
      // This search-only API key is public in docusaurus.config.ts.
      expect(
        url.searchParams.get('x-algolia-api-key') ??
          httpRequest.headers()['x-algolia-api-key'],
      ).toBe('1defe0560dbb853243bd0cccce18127f');
      const body = route.request().postDataJSON() as {
        requests: { query: string; indexName: string }[];
      };
      body.requests.forEach((query) =>
        expect(query.indexName).toBe('Armeria Crawler'),
      );
      requests.push(...body.requests);
      if (body.requests.some((request) => request.query === 'offline')) {
        await route.abort();
        return;
      }
      await route.fulfill({
        json: {
          results: body.requests.map((request) => {
            const hits = request.query === 'no-such-topic' ? [] : [hit];
            return {
              hits,
              nbHits: hits.length,
              page: 0,
              nbPages: hits.length,
              hitsPerPage: 20,
              processingTimeMS: 1,
              query: request.query,
            };
          }),
        },
      });
    },
  );
  return requests;
};

test('search submits the query and opens the selected document', async ({
  page,
}) => {
  const requests = await mockSearch(page);
  await visit(page, '/docs');
  await page.getByRole('button', { name: /^Search/ }).click();
  const modal = page.locator('.DocSearch-Modal');
  await modal
    .getByRole('searchbox', { name: 'Search', exact: true })
    .fill('server');
  const result = modal.getByRole('link', {
    name: 'Server basics',
    exact: true,
  });
  await expect(result).toBeVisible();
  expect(requests).toEqual(
    expect.arrayContaining([
      expect.objectContaining({
        query: 'server',
        indexName: 'Armeria Crawler',
      }),
    ]),
  );
  await result.click();
  await expect(modal).not.toBeVisible();
  await expect(page).toHaveURL(/\/docs\/server\/basics\/?$/);
  await expect(
    page.getByRole('heading', { name: 'Server basics', exact: true }),
  ).toBeVisible();
});

test('search reports no results and recovers when the query changes', async ({
  page,
}) => {
  await mockSearch(page);
  await visit(page, '/docs');
  await page.getByRole('button', { name: /^Search/ }).click();
  const modal = page.locator('.DocSearch-Modal');
  const input = modal.getByRole('searchbox', { name: 'Search', exact: true });
  await input.fill('no-such-topic');
  await expect(
    modal.getByText('No results found for', { exact: false }),
  ).toContainText('no-such-topic');
  await expect(modal.getByRole('option')).toHaveCount(0);
  await input.fill('server');
  await expect(
    modal.getByRole('link', { name: 'Server basics', exact: true }),
  ).toBeVisible();
  await expect(
    modal.getByText('No results found for', { exact: false }),
  ).not.toBeVisible();
});

test.describe('search connection failure', () => {
  // DocSearch rethrows RetryError after all Algolia hosts fail.
  test.use({ expectedBrowserErrors: [/^RetryError: Unreachable hosts /] });
  test('search remains usable after a connection failure', async ({
    page,
    browserErrors,
  }) => {
    const requests = await mockSearch(page);
    await visit(page, '/docs');
    await page.getByRole('button', { name: /^Search/ }).click();
    const modal = page.locator('.DocSearch-Modal');
    const input = modal.getByRole('searchbox', { name: 'Search', exact: true });
    await input.fill('offline');
    await expect.poll(() => browserErrors.length).toBe(1);
    await expect(input).toHaveValue('offline');
    await expect(
      modal.getByRole('link', { name: 'Server basics', exact: true }),
    ).not.toBeVisible();
    expect(requests.some((request) => request.query === 'offline')).toBe(true);
    await input.fill('server');
    await expect(
      modal.getByRole('link', { name: 'Server basics', exact: true }),
    ).toBeVisible();
  });
});

test('search supports keyboard opening, dismissal and result selection', async ({
  page,
  isMobile,
}) => {
  test.skip(isMobile, 'This flow uses the desktop keyboard shortcut.');
  await mockSearch(page);
  await visit(page, '/docs');
  await page.keyboard.press('Control+k');
  const modal = page.locator('.DocSearch-Modal');
  await expect(modal.getByRole('searchbox')).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(modal).not.toBeVisible();
  await page.keyboard.press('Control+k');
  await modal.getByRole('searchbox').fill('server');
  await expect(
    modal.getByRole('option', { name: 'Server basics', exact: true }),
  ).toBeVisible();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/docs\/server\/basics\/?$/);
  await expect(
    page.getByRole('heading', { name: 'Server basics', exact: true }),
  ).toBeVisible();
});
