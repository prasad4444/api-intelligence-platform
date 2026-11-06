import { test, expect } from "@playwright/test";

test("AI Tools tabs use company configuration and explicit send confirmation", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("Username").fill("admin");
  await page.getByLabel("Password", { exact: true }).fill("e2e-admin-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Dashboard", exact: true })).toBeVisible();
  await page.route("**/api/records/integrations", async route => {
    const response = await route.fetch();
    const rows = await response.json();
    await route.fulfill({ json: [...rows, {
      id: "company-copilot", revision: 3, name: "Company Copilot", provider: "github-copilot",
      baseUrl: "https://company.example", enabled: true, aiChatEnabled: true, aiProbePath: "/health",
    }] });
  });
  await page.route("**/api/ai-tools/company-copilot/status", route => route.fulfill({ json: { state: "UNTESTED" } }));
  await page.route("**/api/ai-tools/company-copilot/test", route => route.fulfill({ json: {
    state: "CONNECTED", message: "The configured provider responded successfully.",
  } }));
  await page.route("**/api/ai-tools/company-copilot/preview", route => route.fulfill({ json: {
    confirmationId: "mock-confirmation", destination: "company.example", prompt: "Review the selected contract",
  } }));
  let sends = 0;
  await page.route("**/api/ai-tools/company-copilot/send", route => {
    expect(route.request().postDataJSON().confirmationId).toBe("mock-confirmation");
    sends++;
    return route.fulfill({ json: { result: { response: "Company gateway response" }, requestId: "fixture-id" } });
  });
  await page.locator("nav").getByRole("button", { name: "AI Copilot", exact: true }).click();
  const tabs = page.getByRole("tablist", { name: "AI providers" });
  for (const name of ["Microsoft Copilot", "Devin", "GitHub Copilot", "Stylus"]) {
    await expect(tabs.getByRole("tab", { name, exact: true })).toBeVisible();
  }
  await tabs.getByRole("tab", { name: "GitHub Copilot", exact: true }).click();
  await page.getByRole("button", { name: "Test connection", exact: true }).click();
  await expect(page.getByText("Connected", { exact: true })).toBeVisible();
  await page.getByRole("textbox", { name: "GitHub Copilot prompt" }).fill("Review the selected contract");
  await page.getByRole("combobox", { name: "Workspace context" }).selectOption("contract");
  await page.getByRole("button", { name: "Preview request" }).click();
  await expect(page.getByText("Review data before sending to company.example")).toBeVisible();
  expect(sends).toBe(0);
  await page.getByRole("button", { name: "Confirm and send" }).click();
  await expect(page.getByText("Company gateway response")).toBeVisible();
  expect(sends).toBe(1);

  await tabs.getByRole("tab", { name: "Stylus", exact: true }).click();
  await page.getByRole("button", { name: "Configure Stylus", exact: true }).click();
  const editor = page.getByRole("dialog", { name: "Configure connector" });
  await expect(editor.locator('input[type="password"]')).toHaveCount(0);
  await editor.getByRole("combobox", { name: "Stylus setup method" }).selectOption("curl");
  await editor.getByRole("textbox", { name: "Redacted cURL template" }).fill(
    "curl -X POST 'https://company.example/stylus' -H 'Authorization: Bearer {{vault:stylus-token}}' --data-raw '{\"message\":\"{{prompt}}\"}'",
  );
  await editor.getByRole("button", { name: "Import cURL fields" }).click();
  await expect(editor.getByRole("textbox", { name: "Full AI request URL" })).toHaveValue("https://company.example/stylus");
  await expect(editor.getByRole("textbox", { name: "Secret reference" })).toHaveValue("stylus-token");
});
