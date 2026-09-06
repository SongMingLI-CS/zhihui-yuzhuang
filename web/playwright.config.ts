import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e', timeout: 45_000, fullyParallel: true,
  use: { baseURL: 'http://127.0.0.1:3000', trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  projects: [
    { name: 'desktop-chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'tablet-chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 768, height: 1024 } } },
    { name: 'mobile-chromium', use: { ...devices['Pixel 5'] } },
  ],
  webServer: [
    { command: 'npm run dev -- --hostname 127.0.0.1', cwd: '.', url: 'http://127.0.0.1:3000/b/dashboard', reuseExistingServer: !process.env.CI },
    { command: 'npm run dev -- --host 127.0.0.1', cwd: '../h5', url: 'http://127.0.0.1:5173/h5/', reuseExistingServer: !process.env.CI },
  ],
});
