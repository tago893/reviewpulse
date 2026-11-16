import { expect, type Page, test } from '@playwright/test';

/** Searches from the Search tab and returns the number of result cards. */
async function searchCount(page: Page, query: string): Promise<number> {
  await page.getByRole('tab', { name: 'Search' }).click();
  await page.getByRole('textbox', { name: 'Search reviews' }).fill(query);
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(page.getByText(/matches for/)).toContainText(query);
  return page.locator('ol.results > li').count();
}

test('create → searchable → edit → old terms gone → delete → gone', async ({ page }) => {
  const original = `e2eorig${Date.now().toString(36)}`;
  const edited = `e2eedit${Date.now().toString(36)}`;

  await page.goto('/?tab=demo');
  await page.getByLabel('Title').fill(`Playwright ${original}`);
  await page.getByLabel('Body').fill(`created by the end-to-end test ${original}`);
  await page.getByRole('button', { name: 'Create new' }).click();
  // The UI polls GET /api/reviews/{id} until the asynchronously indexed version is searchable.
  await expect(page.locator('p.ok')).toContainText(/Create committed \(version 1\); searchable after \d+ ms/);
  const reviewId = await page.getByRole('textbox', { name: 'Review id' }).inputValue();
  expect(reviewId).toMatch(/^\d+$/);

  expect(await searchCount(page, original)).toBe(1);

  await page.getByRole('tab', { name: 'Demo controls' }).click();
  await page.getByRole('textbox', { name: 'Review id' }).fill(reviewId);
  await page.getByRole('button', { name: 'Load' }).click();
  await page.getByLabel('Body').fill(`edited by the end-to-end test ${edited}`);
  await page.getByRole('button', { name: 'Save edit' }).click();
  await expect(page.locator('p.ok')).toContainText(/Update committed \(version 2\); searchable/);

  expect(await searchCount(page, original)).toBe(1); // still matches via the unchanged title
  expect(await searchCount(page, edited)).toBe(1);

  await page.getByRole('tab', { name: 'Demo controls' }).click();
  await page.getByRole('textbox', { name: 'Review id' }).fill(reviewId);
  await page.getByRole('button', { name: 'Load' }).click();
  await page.getByRole('button', { name: 'Delete' }).click();
  await expect(page.locator('p.ok')).toContainText(`Deleted review ${reviewId}`);

  // Deleted reviews are never shown: hydration drops them even before the index catches up.
  await expect.poll(() => searchCount(page, edited), { timeout: 15_000 }).toBe(0);
});

test('a stale edit is rejected with a version conflict', async ({ page, request }) => {
  const word = `e2econflict${Date.now().toString(36)}`;
  const created = await request.post('/api/reviews', {
    data: { parentAsin: 'B0E2E00001', title: word, body: 'conflict test', rating: 3 },
  });
  expect(created.status()).toBe(201);
  const id: string = (await created.json()).data.id;

  await page.goto('/?tab=demo');
  await page.getByRole('textbox', { name: 'Review id' }).fill(id);
  await page.getByRole('button', { name: 'Load' }).click();
  // Someone else edits it first (version 1 -> 2)...
  const other = await request.put(`/api/reviews/${id}`, {
    data: { expectedVersion: 1, title: word, body: 'changed elsewhere', rating: 3 },
  });
  expect(other.status()).toBe(200);
  // ...so saving from the UI with the stale version 1 must fail loudly.
  await page.getByLabel('Body').fill('my late edit');
  await page.getByRole('button', { name: 'Save edit' }).click();
  await expect(page.getByRole('alert')).toContainText(/expected version 1, current version 2/);
});
