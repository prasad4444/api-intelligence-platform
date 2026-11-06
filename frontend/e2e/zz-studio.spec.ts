import {test,expect} from "@playwright/test";
async function login(page:any){await page.goto('/');await page.getByLabel('Username').fill('admin');await page.getByLabel('Password',{exact:true}).fill('e2e-admin-password');await page.getByRole('button',{name:'Sign in'}).click();await expect(page.getByRole('heading',{name:'Dashboard',exact:true})).toBeVisible();}
test('uploaded YAML generates linked cases suites and plans without inventing runs',async({page})=>{
 await login(page);await page.locator('nav').getByRole('button',{name:'API Workspace',exact:true}).click();
 await page.getByRole('tab',{name:'Source',exact:true}).click();
 await page.getByLabel('OpenAPI source').fill("openapi: 3.0.3\ninfo:\n  title: Studio regression API\n  version: '1.0'\npaths:\n  /widgets:\n    get:\n      operationId: listWidgets\n      summary: List widgets\n      responses:\n        '200':\n          description: OK\n");
 await page.getByRole('button',{name:'Import contract',exact:true}).click();await expect(page.getByRole('status')).toContainText('Saved successfully');
 await page.locator('nav').getByRole('button',{name:'Test Library',exact:true}).click();await expect(page.getByText('Studio regression API',{exact:true})).toBeVisible();
 await page.getByRole('button',{name:'Generate cases, suites & plan',exact:true}).click();await expect(page.getByText('Package saved',{exact:true})).toBeVisible();await expect(page.getByText('4 cases',{exact:true})).toBeVisible();
 await expect(page.locator('table tbody tr')).toHaveCount(4);await page.getByRole('button',{name:'Suites',exact:true}).click();await expect(page.locator('table tbody tr')).toHaveCount(4);await page.getByRole('button',{name:'Plans',exact:true}).click();await expect(page.locator('table tbody tr')).toHaveCount(1);await page.getByRole('button',{name:'Runs',exact:true}).click();await expect(page.getByText('No records yet. Create one to get started.')).toBeVisible();
});
test('request tabs retain headers and separate response state',async({page})=>{
 await login(page);await page.locator('nav').getByRole('button',{name:'Request Studio',exact:true}).click();await page.getByLabel('Request URL').fill('https://example.com/orders');await page.getByRole('tab',{name:'Headers',exact:true}).click();await page.getByLabel('Header 1 key').fill('X-Team');await page.getByLabel('Header 1 value').fill('api');
 await page.route('**/api/client/send',async route=>{const body=route.request().postDataJSON();expect(body.headers['X-Team']).toBe('api');await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({status:200,durationMs:7,headers:{'content-type':'application/json'},body:'{"ok":true}'})});});
 await page.getByRole('button',{name:'Send',exact:true}).click();await expect(page.getByLabel('Response body')).toContainText('"ok": true');await page.getByLabel('New request tab').click();await expect(page.getByText('Make a request. See what happens.')).toBeVisible();await page.getByRole('tab',{name:/^GET New request/}).click();await expect(page.getByLabel('Response body')).toContainText('"ok": true');
 await page.getByRole('button',{name:'Test Results',exact:false}).click();await expect(page.getByText('PASS',{exact:true})).toBeVisible();
});
test('saved environments persist while secrets stay masked and out of localStorage',async({page})=>{
 await login(page);await page.locator('nav').getByRole('button',{name:'Request Studio',exact:true}).click();await page.getByLabel('Manage environments').click();
 await page.getByLabel('Environment name').fill('Secure browser test');await page.getByLabel('Environment allowed hosts').fill('example.com');await page.getByLabel('Variable 1 value').fill('https://example.com');await page.getByLabel('Variable 2 key').fill('bearerToken');await page.getByLabel('Variable 2 value').fill('demo-secret-do-not-persist-plain');await page.getByLabel('Variable 2 type').selectOption('secret');await page.getByRole('button',{name:'Save profile',exact:true}).click();await expect(page.getByLabel('Variable 2 value')).toHaveValue('');await page.getByLabel('Close dialog').click();
 const selected=await page.getByLabel('Active environment').inputValue();expect(selected).not.toBe('local');await page.reload();await page.locator('nav').getByRole('button',{name:'Request Studio',exact:true}).click();await expect(page.getByLabel('Active environment')).toHaveValue(selected);expect(await page.evaluate(()=>JSON.stringify(localStorage))).not.toContain('demo-secret');
 await page.getByRole('tab',{name:'Authorization',exact:true}).click();await page.getByLabel('Auth type').selectOption('bearer');await page.getByLabel('Auth credential').fill('{{bearerToken}}');
 await page.route('**/api/client/send',route=>{const body=route.request().postDataJSON();expect(body.environmentId).toBe(selected);expect(body.headers.Authorization).toBe('Bearer {{bearerToken}}');return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({status:200,body:'{}',headers:{},durationMs:1})});});await page.getByRole('button',{name:'Send',exact:true}).click();await expect(page.getByLabel('Response body')).toBeVisible();
});
test('Windows authentication is optional and configurable per connector',async({page})=>{
 await login(page);await page.locator('nav').getByRole('button',{name:'Integration Hub',exact:true}).click();await page.locator('.connector-grid .panel').filter({has:page.getByRole('heading',{name:/^github$/i})}).getByRole('button',{name:'Manage connection'}).click();
 await page.getByLabel('Authentication',{exact:true}).selectOption('WINDOWS');await expect(page.getByLabel('Windows protocol')).toHaveValue('NEGOTIATE');await expect(page.getByLabel('Windows identity')).toHaveValue('current');await expect(page.getByLabel('Windows password (encrypted in vault)')).toHaveCount(0);
 await page.getByLabel('Windows identity').selectOption('explicit');await expect(page.getByLabel('Windows username')).toBeVisible();await expect(page.getByLabel('Windows password (encrypted in vault)')).toBeVisible();await page.getByLabel('Windows protocol').selectOption('NTLM');await page.getByLabel('Authentication',{exact:true}).selectOption('BEARER');await expect(page.getByLabel('Windows protocol')).toHaveCount(0);await page.getByRole('button',{name:'Cancel',exact:true}).click();
});

test('Request Studio distinguishes upstream 401 from an expired platform session', async ({page}) => {
  await login(page);
  await page.locator('nav').getByRole('button',{name:'Request Studio',exact:true}).click();
  await page.getByLabel('Request URL').fill('https://example.com/restricted');
  await page.route('**/api/client/send', route => route.fulfill({json:{
    status:401, durationMs:20, body:'Unauthorized', requestId:'upstream-fixture',
    diagnostic:{reason:'upstream_unauthorized', authorization:'none', credentialHeader:false, challenge:'bearer', challengeError:'invalid_token'},
  }}));
  await page.getByRole('button',{name:'Send',exact:true}).click();
  await expect(page.getByText(/The target API returned 401/)).toBeVisible();
  await expect(page.getByText('upstream-fixture',{exact:true})).toBeVisible();
  await expect(page.getByText('invalid_token',{exact:true})).toBeVisible();

  await page.unroute('**/api/client/send');
  await page.route('**/api/client/send', route => route.fulfill({
    status:401, headers:{'X-Platform-Failure':'platform_session_required','X-Request-ID':'platform-fixture'},
  }));
  await page.getByRole('button',{name:'Send',exact:true}).click();
  await expect(page.getByText(/Your platform session is missing or expired.*platform-fixture/)).toBeVisible();
});

test('SQLite usage search retrieves a recorded platform request ID', async ({page}) => {
  await login(page);
  await page.locator('nav').getByRole('button',{name:'Admin',exact:true}).click();
  await page.getByRole('button',{name:'Load usage',exact:true}).click();
  const events = page.locator('.usage-table');
  await expect(events.getByText('platform_session_required',{exact:true}).first()).toBeVisible();
  const row = events.getByRole('row').filter({hasText:'platform_session_required'}).first();
  const requestId = (await row.getByRole('button').textContent())!.trim();
  await row.getByRole('button').click();
  await expect(page.getByLabel('Usage request ID')).toHaveValue(requestId);
  await expect(events.locator('tbody tr')).toHaveCount(1);
});
