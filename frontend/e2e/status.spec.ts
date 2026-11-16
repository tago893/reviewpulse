import { expect, test } from '@playwright/test';

test('status page shows health, counts and live metrics', async ({ page }) => {
  await page.goto('/?tab=status');
  await expect(page.getByText(/System (UP|DEGRADED)/)).toBeVisible();
  for (const label of ['Reviews in PostgreSQL', 'Reviews indexed', 'Search latency p50 / p95', 'Indexing throughput', 'Backlog']) {
    await expect(page.locator('.card-label', { hasText: label })).toBeVisible();
  }
  const indexed = page.locator('.card', { hasText: 'Reviews indexed' }).locator('.card-value');
  await expect(indexed).toHaveText(/^[1-9][\d,]*$/);
});

test('admin replay controls are reachable through the proxy', async ({ page }) => {
  await page.goto('/?tab=demo');
  await expect(page.getByRole('heading', { name: 'Live replay of real reviews' })).toBeVisible();
  // The status line comes from GET /api/admin/replay/status, which needs the proxy-injected admin token.
  await expect(page.getByText(/^(IDLE|RUNNING|PAUSED|COMPLETED|CANCELLED)/)).toBeVisible();
});
