import { test, expect, type Page } from "@playwright/test";
import { readFileSync, mkdirSync } from "node:fs";
import { randomUUID } from "node:crypto";
test.skip(
  !process.env.PAYFLOW_API_KEY_FILE,
  "Optional real-backend smoke needs an owner-only local key file.",
);
async function connect(page: Page) {
  const key = readFileSync(process.env.PAYFLOW_API_KEY_FILE!, "utf8").trim();
  await page.goto("/connect");
  await expect(page.getByLabel("PayFlow API Key")).toBeVisible();
  try {
    await page.getByLabel("PayFlow API Key").fill(key);
  } catch {
    throw new Error("Could not enter the local credential");
  }
  await page
    .getByRole("button", { name: "Connect merchant", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Your payments, in view." }),
  ).toBeVisible({ timeout: 30000 });
}
test("real merchant flow: connect, list, create, poll, refund, health and logout", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.name));
  const paths: string[] = [];
  page.on("request", (r) => paths.push(new URL(r.url()).pathname));
  await connect(page);
  await page.goto("/payments");
  await expect(
    page
      .getByRole("table", { name: "Payments" })
      .or(page.getByText("No payments yet")),
  ).toBeVisible();
  await page.goto("/payments/new");
  const run = randomUUID();
  await page.getByLabel("User ID").fill("frontend-smoke-" + run);
  await page.getByLabel("Amount", { exact: true }).fill("5.25");
  await page
    .getByRole("button", { name: "Submit payment", exact: true })
    .click();
  await expect(page).toHaveURL(/\/payments\/[0-9a-f-]{36}$/);
  await expect(page.getByText("Successful", { exact: true })).toBeVisible({
    timeout: 90000,
  });
  const paymentPath = new URL(page.url()).pathname;
  await page
    .getByRole("button", { name: "Submit a refund", exact: true })
    .click();
  await page.getByLabel("Refund amount (INR)").fill("1.00");
  await page
    .getByLabel("Merchant refund reference")
    .fill("frontend-refund-" + run);
  await page
    .getByRole("button", { name: "Submit refund", exact: true })
    .click();
  await expect(page).toHaveURL(/\/refunds\/[0-9a-f-]{36}$/);
  await expect(page.getByText("Successful", { exact: true })).toBeVisible({
    timeout: 90000,
  });
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Refund details" }),
  ).toBeVisible();
  await page.goto(paymentPath);
  await expect(
    page.getByText("Partially refunded", { exact: true }),
  ).toBeVisible({ timeout: 30000 });
  await page.goto("/system");
  await expect(page.getByText("UP", { exact: true })).toBeVisible();
  const stored = await page.evaluate(() => ({
    session: Boolean(sessionStorage.getItem("payflow.demo.apiKey")),
    local: Boolean(localStorage.getItem("payflow.demo.apiKey")),
  }));
  expect(stored).toEqual({ session: true, local: false });
  expect(paths.some((p) => p.includes("actuator"))).toBe(false);
  expect(errors).toEqual([]);
  await page.getByRole("button", { name: "Disconnect merchant" }).click();
  await expect(page).toHaveURL(/\/connect$/);
  expect(
    await page.evaluate(() => sessionStorage.getItem("payflow.demo.apiKey")),
  ).toBeNull();
});
for (const width of [1440, 1024, 768, 390]) {
  test(`responsive real-data dashboard and form at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 1000 });
    await connect(page);
    await expect(
      page.getByText(/Statistics and chart describe this loaded page/),
    ).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth + 1,
      ),
    ).toBe(true);
    if (width < 1200) {
      await page.getByRole("button", { name: "Open navigation" }).click();
      await expect(
        page
          .getByRole("navigation", { name: "Main navigation" })
          .getByText("Payments", { exact: true }),
      ).toBeVisible();
      await page
        .getByRole("navigation", { name: "Main navigation" })
        .getByText("Payments", { exact: true })
        .click();
    }
    await page.goto("/payments");
    await expect(
      page.getByRole("heading", { name: "Payments", exact: true }),
    ).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth + 1,
      ),
    ).toBe(true);
    await page.goto("/payments/new");
    await expect(page.getByLabel("User ID")).toBeVisible();
    await expect(
      page.getByRole("button", { name: "Submit payment" }),
    ).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth + 1,
      ),
    ).toBe(true);
    // Real browser captures for transient visual inspection, never fabricated or committed as portfolio screenshots.
    if (process.env.PAYFLOW_VISUAL_DIR) {
      mkdirSync(process.env.PAYFLOW_VISUAL_DIR, { recursive: true });
      await page.screenshot({
        path: `${process.env.PAYFLOW_VISUAL_DIR}/form-${width}.png`,
        fullPage: true,
      });
      await page.goto("/");
      await expect(
        page.getByRole("heading", { name: "Your payments, in view." }),
      ).toBeVisible();
      await page.waitForTimeout(800);
      await page.screenshot({
        path: `${process.env.PAYFLOW_VISUAL_DIR}/dashboard-${width}.png`,
        fullPage: true,
      });
    }
  });
}
test("controlled UI fixture represents pending confirmation without fabricated transitions", async ({
  page,
}) => {
  await connect(page);
  const id = "11111111-1111-4111-8111-111111111111";
  await page.route(`**/api/v1/payments/${id}`, (route) =>
    route.fulfill({
      json: {
        id,
        merchantId: "browser-fixture-only",
        userId: "simulated-pending",
        amount: 1,
        currency: "INR",
        paymentMethod: "UPI",
        status: "PENDING_CONFIRMATION",
        createdAt: "2026-10-11T00:00:00Z",
        updatedAt: "2026-10-11T00:00:01Z",
      },
    }),
  );
  await page.goto("/payments/" + id);
  await expect(
    page.getByText(/PayFlow is confirming the final result/),
  ).toBeVisible();
  await expect(
    page.getByText("Awaiting confirmation", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Submit a refund" }),
  ).toHaveCount(0);
  await expect(
    page.getByText(/Intermediate transition times are unavailable/),
  ).toBeVisible();
});
