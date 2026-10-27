import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests/',
  testMatch: /.*\.test\.ts$/,
  use: {
    browserName: 'chromium',
    headless: true,
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'offline' },
  ],
});
