import { expect, test } from '@playwright/test';

test.describe('search', () => {
  test('a shared link runs the query and highlights matching terms', async ({ page }) => {
    await page.goto('/?q=not%20greasy%20moisturizer');
    const results = page.locator('ol.results > li');
    await expect(results.first()).toBeVisible();
    await expect(page.getByText(/matches for/)).toContainText('not greasy moisturizer');
    // Highlights are real <mark> elements produced from server offsets.
    await expect(results.first().locator('mark').first()).toBeVisible();
    const marked = (await results.first().locator('mark').allInnerTexts()).map((t) => t.toLowerCase());
    expect(marked.some((t) => ['not', 'greasy', 'moisturizer'].includes(t))).toBeTruthy();
  });

  test('filters, sort and pagination work together', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('textbox', { name: 'Search reviews' }).fill('dry skin');
    await page.getByLabel('Rating ≥').selectOption('5');
    await page.getByLabel('Sort').selectOption('recency');
    await page.getByRole('button', { name: 'Search', exact: true }).click();

    const results = page.locator('ol.results > li');
    await expect(results).toHaveCount(10);
    for (const stars of await results.locator('.stars').all()) {
      await expect(stars).toHaveAttribute('aria-label', '5 out of 5 stars');
    }
    await page.getByRole('button', { name: 'Load more' }).click();
    await expect(results).toHaveCount(20);
  });

  test('opening a result shows the full review with index freshness', async ({ page }) => {
    await page.goto('/?q=lipstick');
    await page.locator('ol.results > li').first().click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText('Review id');
    await expect(dialog).toContainText(/searchable|pending/);
    await dialog.getByRole('button', { name: 'Close' }).click();
    await expect(dialog).toBeHidden();
  });

  test('a stop-word-only query explains itself instead of failing', async ({ page }) => {
    await page.goto('/?q=the%20and%20of');
    await expect(page.getByText('No reviews match')).toBeVisible();
    await expect(page.getByText('no searchable terms')).toBeVisible();
  });
});
