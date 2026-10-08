import { expect, test as base, type Page } from '@playwright/test';

export const test = base.extend<{
  browserErrors: string[];
  expectedBrowserErrors: RegExp[];
}>({
  expectedBrowserErrors: [[], { option: true }],
  browserErrors: [
    async ({ context, baseURL, expectedBrowserErrors }, use) => {
      const errors: string[] = [];
      context.on('page', (page) => {
        page.on('pageerror', (error) =>
          errors.push(`${error.name}: ${error.message}`),
        );
      });
      await context.route('**/*', async (route) => {
        if (
          new URL(route.request().url()).origin === new URL(baseURL!).origin
        ) {
          await route.continue();
        } else {
          await route.abort();
        }
      });
      await use(errors);
      expect(errors, 'Unhandled browser errors').toHaveLength(
        expectedBrowserErrors.length,
      );
      expectedBrowserErrors.forEach((pattern, index) => {
        expect(errors[index]).toMatch(pattern);
      });
    },
    { auto: true },
  ],
});

export { expect };

export const visit = async (page: Page, path: string) => {
  await page.goto(path);
  await expect(page.locator('html')).toHaveAttribute(
    'data-has-hydrated',
    'true',
  );
};
