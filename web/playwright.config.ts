import { defineConfig, devices } from '@playwright/test'

// Un solo test de humo contra MSW (`vite --mode mock`): no hace falta el servidor Go.
// Navegadores: instalación local del proyecto (PLAYWRIGHT_BROWSERS_PATH=0 → node_modules).
export default defineConfig({
  testDir: 'e2e',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: 'list',
  use: { baseURL: 'http://localhost:5174', trace: 'off' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev:mock -- --port 5174 --strictPort',
    url: 'http://localhost:5174',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
})
