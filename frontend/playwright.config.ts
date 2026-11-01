import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./e2e",
  workers: 1,
  timeout: 45000,
  use: {
    baseURL: "http://127.0.0.1:8080",
    viewport: { width: 1440, height: 1000 },
    trace: "retain-on-failure",
  },
  reporter: [["list"], ["html", { open: "never" }]],
  webServer: {
    command:
      'java -jar ../target/api-intelligence-platform-1.0.0-SNAPSHOT.jar --spring.datasource.url="jdbc:h2:mem:e2e;DB_CLOSE_DELAY=-1"',
    url: "http://127.0.0.1:8080",
    timeout: 60000,
    reuseExistingServer: !process.env.CI,
    env: {
      ADMIN_PASSWORD: "e2e-admin-password",
      VAULT_KEY: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    },
  },
});
