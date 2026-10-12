import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./e2e",
  workers: 1,
  timeout: 120000,
  reporter: "list",
  use: {
    baseURL: process.env.PAYFLOW_UI_URL || "http://localhost:3001",
    headless: true,
    trace: "off",
    screenshot: "off",
    video: "off",
  },
});
