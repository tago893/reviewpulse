import { defineConfig } from '@playwright/test';

// End-to-end tests against a RUNNING stack (backend on :8080 with data, plus this UI).
// By default they target the Vite dev server; set E2E_BASE_URL=http://127.0.0.1:3000 for the
// containerized UI. Uses the locally installed Microsoft Edge, so no browser download is needed
// (set E2E_BROWSER_CHANNEL=chrome or leave empty for Playwright's bundled Chromium).
const channel = process.env.E2E_BROWSER_CHANNEL ?? 'msedge';

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173',
    channel: channel || undefined,
    viewport: { width: 1280, height: 860 },
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'e2e', testIgnore: /demo\.spec\.ts/ },
    {
      // `npm run demo`: a slowed-down walkthrough recorded to test-results/**/video.webm.
      name: 'demo',
      testMatch: /demo\.spec\.ts/,
      use: { video: { mode: 'on', size: { width: 1280, height: 860 } }, launchOptions: { slowMo: 350 } },
    },
  ],
});
