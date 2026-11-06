import {test,expect} from "@playwright/test";
const source=`# Original formatting is preserved
openapi: 3.0.3
info:
  title: Unified Upload Review API
  version: '2.0'
servers:
  - url: https://api.example.com
security: []
components:
  schemas:
    Order:
      type: object
      required: [name]
      properties:
        name:
          type: string
          example: new
paths:
  /orders:
    post:
      operationId: createOrder
      summary: Create order
      parameters:
        - name: X-Team
          in: header
          required: true
          schema:
            type: string
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/Order'
      responses:
        '201':
          description: Created
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Order'
        '400':
          description: Invalid order
          content:
            application/json:
              example:
                error: Name required
`;
async function login(page:any){await page.goto('/');await page.getByLabel('Username').fill('admin');await page.getByLabel('Password',{exact:true}).fill('e2e-admin-password');await page.getByRole('button',{name:'Sign in'}).click();await expect(page.getByRole('heading',{name:'Dashboard',exact:true})).toBeVisible();}
test('one YAML upload links endpoint details, workflow, collection and tests, with complete file comparison',async({page})=>{
 await login(page);await page.locator('nav').getByRole('button',{name:'API Workspace',exact:true}).click();
 await page.locator('input[type=file]').setInputFiles({name:'unified.yaml',mimeType:'application/yaml',buffer:Buffer.from(source)});
 await expect(page.getByRole('status')).toContainText('Saved successfully');await page.getByRole('button',{name:'Split source'}).click();await expect(page.getByLabel('OpenAPI source')).toHaveValue(source);
 await page.locator('.swagger-operation > summary').filter({hasText:'/orders'}).click();
 await expect(page.locator('.swagger-operation')).toContainText('X-Team');
 await expect(page.locator('.swagger-operation')).toContainText('Invalid order');
 await expect(page.getByLabel('request object example')).toContainText('"name": "new"');
 const requestSent = page.waitForRequest(req => req.url().endsWith('/operations/execute'));
 await page.route('**/api/workspace/*/operations/execute', route => route.fulfill({json: {status: 201, durationMs: 5, body: 'Created'}}));
 await page.getByRole('button',{name:'Try it out',exact:true}).click();
 await page.getByLabel('X-Team header parameter').fill('qa');
 await page.getByRole('button',{name:'Execute',exact:true}).click();
 expect((await requestSent).postDataJSON()).toMatchObject({
   operation: 'POST /orders', serverIndex: 0,
   parameters: {'header:X-Team':'qa'}, mediaType: 'application/json',
 });
 await expect(page.getByText('201 · 5 ms')).toBeVisible();
 await page.getByRole('button',{name:'Show endpoint details'}).click();
 await page.getByRole('button',{name:'Inspect POST /orders',exact:true}).click();await page.getByRole('tab',{name:'Parameters & headers'}).click();await expect(page.getByText('X-Team',{exact:true}).first()).toBeVisible();await page.getByRole('tab',{name:'Request body',exact:true}).click();await expect(page.getByLabel('request object example')).toContainText('"name": "new"');
 await page.getByRole('tab',{name:/^Responses/}).click();await page.getByRole('button',{name:'400 Invalid order'}).click();await expect(page.getByLabel('response object example')).toContainText('Name required');
 await page.getByRole('button',{name:'Request review',exact:true}).click();await expect(page.getByRole('button',{name:'Approve',exact:true})).toBeEnabled();await page.getByRole('button',{name:'Approve',exact:true}).click();await expect(page.getByText('APPROVED',{exact:true})).toBeVisible();await page.getByRole('button',{name:'Edit as draft',exact:true}).click();await expect(page.getByText('DRAFT',{exact:true})).toBeVisible();
 await page.getByRole('button',{name:'Create collection',exact:true}).click();await page.getByRole('button',{name:'Open Request Studio',exact:true}).click();await expect(page.locator('.rs-collection-heading strong').filter({hasText:'Unified Upload Review API · 2.0'})).toHaveCount(1);await page.getByRole('button',{name:'POST Create order',exact:true}).click();await page.getByRole('tab',{name:'Body',exact:true}).click();await expect(page.getByLabel('Request body')).toContainText('new');
 await page.locator('nav').getByRole('button',{name:'Test Library',exact:true}).click();await expect(page.locator('table tbody tr')).toHaveCount(4);await page.getByRole('button',{name:'Suites',exact:true}).click();await expect(page.locator('table tbody tr')).toHaveCount(4);await page.getByRole('button',{name:'Plans',exact:true}).click();await expect(page.locator('table tbody tr')).toHaveCount(1);
 await page.locator('nav').getByRole('button',{name:'API Workspace',exact:true}).click();await page.getByRole('tab',{name:'Source',exact:true}).click();await page.getByLabel('OpenAPI source').fill(source.replace("version: '2.0'","version: '2.1'"));await expect(page.getByRole('button',{name:'Request review',exact:true})).toBeDisabled();await page.getByRole('tab',{name:'Changes',exact:true}).click();await page.getByRole('tab',{name:'Paste previous file'}).click();await page.getByLabel('Previous contract source').fill(source);await page.getByRole('button',{name:'Compare complete files'}).click();await expect(page.getByRole('dialog',{name:'Contract comparison'})).toBeVisible();await expect(page.getByText('Previous · v2.0')).toBeVisible();await expect(page.getByText('Current · v2.1')).toBeVisible();await expect(page.locator('.cc-line.changed')).toHaveCount(1);await page.getByRole('button',{name:"What's new",exact:true}).click();await expect(page.getByText('/info/version',{exact:true})).toBeVisible();await page.keyboard.press('Escape');await expect(page.getByRole('dialog',{name:'Contract comparison'})).toHaveCount(0);
 await page.locator('nav').getByRole('button',{name:'Request Studio',exact:true}).click();await page.getByRole('button',{name:'POST Create order',exact:true}).click();await page.getByLabel('Actions for Unified Upload Review API · 2.0',{exact:true}).click();await page.getByRole('button',{name:'Delete collection',exact:true}).click();const dialog=page.getByRole('dialog',{name:'Delete collection'});await expect(dialog).toBeVisible();await dialog.getByRole('button',{name:'Delete',exact:true}).click();await expect(page.locator('.rs-collection-heading strong').filter({hasText:'Unified Upload Review API · 2.0'})).toHaveCount(0);await expect(page.getByLabel('Request name')).toHaveValue('Create order');
});

test('saved revision switching clears execution inputs and preserves the operation', async ({page}) => {
  await login(page);
  await page.locator('nav').getByRole('button', {name:'API Workspace', exact:true}).click();
  const original = source.replace('Unified Upload Review API', 'Revision isolation fixture');
  await page.locator('input[type=file]').setInputFiles({name:'revision.yaml', mimeType:'application/yaml', buffer:Buffer.from(original)});
  const operation = page.getByLabel('Expand POST /orders');
  await expect(operation).toBeVisible();
  await operation.click();
  await page.getByRole('button', {name:'Try it out', exact:true}).click();
  await page.getByLabel('X-Team header parameter').fill('first revision');
  await page.getByRole('tab', {name:'Source', exact:true}).click();
  await page.getByLabel('OpenAPI source').fill(original.replace('Create order', 'Create updated order'));
  await page.getByRole('button', {name:'Save selected contract'}).click();
  await expect(page.getByText('Create updated order', {exact:true})).toBeVisible();
  await expect(page.getByLabel('X-Team header parameter')).toHaveCount(0);
  await page.getByRole('button', {name:'Load revision history'}).click();
  await page.getByLabel('Contract revision').selectOption('1');
  await expect(page.getByText('Create order', {exact:true})).toBeVisible();
  await expect(page.getByRole('button', {name:'Try it out', exact:true})).toBeDisabled();
  await page.getByLabel('Contract revision').selectOption('');
  await expect(page.getByText('Create updated order', {exact:true})).toBeVisible();
  await expect(page.getByRole('button', {name:'Try it out', exact:true})).toBeEnabled();
});

test('AI artifact review binds consent to context and saves an edited local draft', async ({page}) => {
  await login(page);
  await page.route('**/api/records/integrations', async route => {
    const original = await route.fetch();
    const records = await original.json();
    await route.fulfill({json: [...records, {
      id:'fixture-ai', revision:3, provider:'stylus', name:'Fixture AI',
      baseUrl:'https://ai.example.com', enabled:true, aiChatEnabled:true,
    }]});
  });
  let requestCount = 0;
  let draft: Record<string, unknown>;
  await page.route('**/api/ai-drafts/preview', async route => {
    requestCount++;
    const payload = route.request().postDataJSON();
    expect(payload).toMatchObject({kind:'story', integrationId:'fixture-ai', integrationRevision:3, consent:true});
    draft = {id:'fixture-draft', revision:1, specId:payload.specId, specRevision:payload.specRevision,
      specName:'Fixture API', provider:'Fixture AI', kind:'story', status:'DRAFT',
      artifact:{summary:'Improve response', description:'Clarify error response.', acceptanceCriteria:['Show errors.']}};
    await route.fulfill({json:draft});
  });
  await page.route('**/api/ai-drafts/fixture-draft/accept', route => {
    const payload = route.request().postDataJSON();
    expect(payload.artifact.summary).toBe('Reviewed story');
    return route.fulfill({json:{draft:{...draft, artifact:payload.artifact, status:'ACCEPTED', revision:2},record:{status:'To Do'}}});
  });
  await page.locator('nav').getByRole('button',{name:'AI Copilot',exact:true}).click();
  await page.getByRole('combobox',{name:'Artifact provider',exact:true}).selectOption('fixture-ai');
  await page.getByRole('combobox',{name:'Artifact',exact:true}).selectOption('story');
  const generate = page.getByRole('button',{name:'Generate review draft'});
  const consent = page.getByRole('checkbox',{name:'Confirm sending this saved context to this provider'});
  await expect(generate).toBeDisabled();
  await consent.check();
  await page.getByLabel('Artifact instruction').fill('Focus on error responses.');
  await expect(generate).toBeDisabled();
  expect(requestCount).toBe(0);
  await consent.check();
  await generate.click();
  await page.getByLabel('Editable artifact JSON').fill(JSON.stringify({
    summary:'Reviewed story', description:'Clarify error response.', acceptanceCriteria:['Show errors.'],
  }));
  await page.getByRole('button',{name:'Apply reviewed draft'}).click();
  await expect(page.getByText('Saved as a local Jira story draft.')).toBeVisible();
  expect(requestCount).toBe(1);
});
