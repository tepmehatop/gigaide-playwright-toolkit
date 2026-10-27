import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests/',
  testMatch: /.*\.test\.ts$/,
  use: {
    browserName: 'chromium',
    headless: true,
  },
  projects: [
    { name: 'offline' },
  ],
});
