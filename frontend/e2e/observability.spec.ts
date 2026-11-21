import { test, expect, type Page } from "@playwright/test";

async function login(page: Page) {
  await page.goto("/");
  await page.getByLabel("Username").fill("admin");
  await page.getByLabel("Password", { exact: true }).fill("e2e-admin-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(
    page.getByRole("heading", { name: "Dashboard", exact: true }),
  ).toBeVisible();
}

test("Monitoring page renders all observability sections", async ({ page }) => {
  await login(page);
  await page
    .locator("nav")
    .getByRole("button", { name: "Monitoring", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Monitoring", exact: true }),
  ).toBeVisible();
  for (const tab of [
    "Reports",
    "Schedules",
    "Alerts",
    "Notifications",
    "Investigations",
    "Knowledge",
    "Ops",
  ]) {
    await expect(
      page.getByRole("tab", { name: tab, exact: true }),
    ).toBeVisible();
  }
  await expect(page.getByText("Report designer")).toBeVisible();
  await expect(page.getByLabel("Splunk connections")).toBeVisible();
  await page.getByRole("tab", { name: "Alerts", exact: true }).click();
  await expect(page.getByText("Rule builder")).toBeVisible();
  await page.getByRole("tab", { name: "Schedules", exact: true }).click();
  await expect(page.getByText("New schedule")).toBeVisible();
});

test("Connection Studio offers Splunk provider and Add app flow", async ({
  page,
}) => {
  await login(page);
  await page
    .locator("nav")
    .getByRole("button", { name: "Connection Studio", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Connection Studio", exact: true }),
  ).toBeVisible();
  await page.getByRole("tab", { name: /Add app/ }).click();
  const provider = page.getByLabel("Provider");
  await expect(provider).toBeVisible();
  await expect(provider.locator("option", { hasText: "Splunk" })).toHaveCount(1);
});
