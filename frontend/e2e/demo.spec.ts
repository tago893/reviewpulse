import { expect, test } from '@playwright/test';

// Recorded walkthrough (npm run demo): search with highlighting and filters, open a review,
// create one and watch it become searchable, edit it, delete it, and check the status page.
test('ReviewPulse walkthrough', async ({ page }) => {
  // Slowed down on purpose for the recording (slowMo + pauses), so it needs more than the default limit.
  test.setTimeout(180_000);
  const word = `pulsedemo${Date.now().toString(36)}`;
  const pause = (ms: number) => page.waitForTimeout(ms);

  await page.goto('/?q=not%20greasy%20moisturizer');
  await expect(page.locator('ol.results > li').first()).toBeVisible();
  await pause(1500);
  await page.getByLabel('Rating ≥').selectOption('5');
  await page.getByLabel('Sort').selectOption('helpfulness');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await pause(1500);
  await page.locator('ol.results > li').first().click();
  await pause(2000);
  await page.getByRole('dialog').getByRole('button', { name: 'Close' }).click();

  await page.getByRole('tab', { name: 'Demo controls' }).click();
  await page.getByLabel('Title').fill(`Brand new review ${word}`);
  await page.getByLabel('Body').fill(`This review was written live during the demo: ${word}`);
  await page.getByRole('button', { name: 'Create new' }).click();
  await expect(page.locator('p.ok')).toContainText('searchable after');
  const reviewId = await page.getByRole('textbox', { name: 'Review id' }).inputValue();
  await pause(1500);

  await page.getByRole('tab', { name: 'Search' }).click();
  await page.getByRole('textbox', { name: 'Search reviews' }).fill(word);
  await page.getByLabel('Rating ≥').selectOption('');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(page.locator('ol.results > li')).toHaveCount(1);
  await pause(2000);

  await page.getByRole('tab', { name: 'Demo controls' }).click();
  // Switching tabs unmounts the editor, so the id has to be entered again.
  await page.getByRole('textbox', { name: 'Review id' }).fill(reviewId);
  await page.getByRole('button', { name: 'Load' }).click();
  await page.getByRole('button', { name: '+1 helpful' }).click();
  await expect(page.locator('p.ok')).toContainText('Helpful vote committed');
  await pause(1200);
  await page.getByRole('button', { name: 'Delete' }).click();
  await expect(page.locator('p.ok')).toContainText('Deleted review');
  await pause(1200);

  await page.getByRole('tab', { name: 'Search' }).click();
  await page.getByRole('textbox', { name: 'Search reviews' }).fill(word);
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(page.getByText('No reviews match')).toBeVisible();
  await pause(1500);

  await page.getByRole('tab', { name: 'System status' }).click();
  await expect(page.getByText(/System (UP|DEGRADED)/)).toBeVisible();
  await pause(3000);
});
