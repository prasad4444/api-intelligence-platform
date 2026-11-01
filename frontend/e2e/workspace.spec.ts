import { test, expect } from "@playwright/test";
const pages = [
  "Dashboard",
  "API Workspace",
  "Request Studio",
  "Test Library",
  "GitHub",
  "Jira",
  "Team Capacity",
  "Governance",
  "Architecture",
  "AI Copilot",
  "Dev Utilities",
  "Integration Hub",
  "Admin",
];
async function login(page: any) {
  await page.goto("/");
  await page.getByLabel("Username").fill("admin");
  await page.getByLabel("Password", { exact: true }).fill("e2e-admin-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(
    page.getByRole("heading", { name: "Dashboard", exact: true }),
  ).toBeVisible();
}
test("login and every workspace module renders", async ({ page }) => {
  await login(page);
  await page.waitForSelector(".stat-value");
  for (const name of pages) {
    await page
      .locator("nav")
      .getByRole("button", { name, exact: true })
      .click();
    await expect(
      page.getByRole("heading", { name, exact: true }),
    ).toBeVisible();
    if(name==='Architecture')await expect(page.locator('.diagram svg')).toBeVisible({timeout:15000});
    await page.screenshot({
      path:
        "test-results/screenshots/" +
        name.toLowerCase().replace(/ /g, "-") +
        ".png",
      fullPage: true,
    });
  }
  await page.getByRole("button", { name: "Toggle theme" }).click();
  await expect(page.locator("html")).toHaveClass(/dark/);
  await page.keyboard.press("Control+k");
  await expect(
    page.getByRole("dialog", { name: "Command palette" }),
  ).toBeVisible();
  await page.keyboard.press("Escape");
});
test("contract lint, local request rejection and capacity workbook import", async ({
  page,
}) => {
  await login(page);
  await page
    .locator("nav")
    .getByRole("button", { name: "API Workspace", exact: true })
    .click();
  await page.getByRole("button", { name: "Lint", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Results", exact: true }),
  ).toBeVisible();
  await page.getByLabel("Try-it URL").fill("http://127.0.0.1");
  await page.getByRole("button", { name: "Send GET" }).click();
  await expect(page.getByRole("status")).toContainText("blocked");
  await page
    .locator("nav")
    .getByRole("button", { name: "Request Studio", exact: true })
    .click();
  await page.getByLabel("Request URL").fill("http://169.254.169.254");
  await page.getByRole("button", { name: "Send", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("blocked");
  await page
    .locator("nav")
    .getByRole("button", { name: "Team Capacity", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Availability heatmap" }),
  ).toBeVisible();
  const file = await page.request.get("/api/capacity/workbook/export");
  await page.locator("input[type=file]").setInputFiles({
    name: "capacity.xlsx",
    mimeType:
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    buffer: await file.body(),
  });
  await expect(
    page.getByRole("heading", { name: "Import report" }),
  ).toBeVisible();
  await expect(page.locator(".output")).toContainText('"errors": []');
});
test("generate tests, inspect history, configure connector and fallback assistant", async ({
  page,
}) => {
  await login(page);
  await page
    .locator("nav")
    .getByRole("button", { name: "Test Library", exact: true })
    .click();
  await page.getByRole("button", { name: "Generate from API" }).click();
  await expect(page.locator("table tbody tr")).toHaveCount(8);
  await page
    .getByRole("button", { name: "History", exact: true })
    .first()
    .click();
  await expect(
    page.getByRole("heading", { name: "Case evidence and versions" }),
  ).toBeVisible();
  await page
    .locator("nav")
    .getByRole("button", { name: "Integration Hub", exact: true })
    .click();
  await page
    .locator(".connector-grid .panel")
    .filter({ has: page.getByRole("heading", { name: "Devin", exact: true }) })
    .getByRole("button", { name: "Manage connection" })
    .click();
  await expect(
    page.getByRole("dialog", { name: "Configure connector" }),
  ).toBeVisible();
  await expect(page.getByLabel("Devin organization ID")).toBeVisible();
  await page.screenshot({
    path: "test-results/screenshots/devin-configuration.png",
    fullPage: false,
  });
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await page
    .locator("nav")
    .getByRole("button", { name: "AI Copilot", exact: true })
    .click();
  await page.getByRole("button", { name: "Ask assistant" }).click();
  await expect(page.locator(".chat-history")).toContainText(
    "Rule-based guidance",
  );
});

test("mobile workspace stays within the viewport", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page);
  await page.waitForSelector(".stat-value");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBeTruthy();
  await page.screenshot({
    path: "test-results/screenshots/mobile-dashboard.png",
    fullPage: true,
  });
});

test('request response rendering and time-limited regex worker',async({page})=>{await login(page);await page.locator('nav').getByRole('button',{name:'Request Studio',exact:true}).click();await page.route('**/api/client/send',route=>route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({status:200,body:'{"ok":true}',durationMs:12,headers:{'content-type':'application/json'}})}));await page.getByRole('button',{name:'Send',exact:true}).click();await expect(page.locator('.output')).toContainText('"status": 200');await page.locator('nav').getByRole('button',{name:'Dev Utilities',exact:true}).click();await page.getByRole('button',{name:'Regex',exact:true}).click();await page.getByLabel('Utility input').fill('a'.repeat(2000)+'!');await page.getByLabel('Regex pattern').fill('(a+)+$');await page.getByRole('button',{name:'Run Regex',exact:true}).click();await expect(page.locator('.output')).toContainText('execution limit');});
