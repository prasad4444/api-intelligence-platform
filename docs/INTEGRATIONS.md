# Integration recipes

All provider keys live in Integration Hub. They are separate from platform login credentials. Give tokens only the scopes necessary for intended operations. Configure host allowlists in the deployment, then save and enable the connector.

## Optional Windows authentication

Each connector can select WINDOWS independently, with Negotiate or NTLM and either the application process account or a vault-backed configured account. This requires Windows SSPI curl, HTTPS and explicit server-side host configuration. See [Windows authentication setup and limits](WINDOWS_AUTH.md). Browser SSO and Windows-auth streaming are separate, unimplemented capabilities.

## Devin

Preset base URL: `https://api.devin.ai`; auth: Bearer; secret: `devin-token`; organization ID: your Devin organization. Choose allowed workspace roles and enable the connection. In the action console, select this connection.

Read sessions:

```text
/v3/organizations/YOUR_ORG_ID/sessions
```

Propose a task:

```json
{
  "method": "POST",
  "path": "/v3/organizations/YOUR_ORG_ID/sessions",
  "body": {
    "prompt": "Review the provided API contract and propose test improvements. Do not merge changes.",
    "max_acu_limit": 2,
    "title": "API contract review"
  }
}
```

Inspect the exact payload, approve in the queue and execute. Query the returned session through the read console. Costs and available API scopes depend on the provider account. Current recipe follows [Devin v3 Create Session](https://docs.devin.ai/api-reference/v3/sessions/post-organizations-sessions); legacy v1 keys/endpoints are not interchangeable with v3.

## GitHub

Use `https://api.github.com` and Bearer authentication with a fine-grained PAT in the vault. Repository contents writes and pull request writes require suitable token permissions. Use the GitHub page to browse. Read `main`'s commit SHA, propose and execute a new branch, then propose the file commit (existing files require their blob SHA), and finally propose the PR. Every write has its own explicit approval. PR status is retrieved live via the pull request browser. No merge action is automatic.

## Jira

Use `https://YOUR_INSTANCE.atlassian.net`, add that hostname to the server allowlist, and choose Basic authentication. The secret value is `email:api_token`. The Jira page runs JQL through `/rest/api/3/search/jql`. For Test issue creation, configure your site's Test issue type first, then propose `/rest/api/3/issue` through the action console using Jira's fields schema. Local stories are intentionally independent from live Jira until an approved action is executed.

## OpenAI-compatible / Azure / Anthropic / Ollama

Specify the provider's base URL, model and credentials. OpenAI-compatible chat uses `/v1/chat/completions`. Azure uses the deployment-specific path and API version in `chatPath`, with `API_KEY` authentication and `api-key` header. Anthropic uses `x-api-key`, `/v1/messages`, and `anthropic-version: 2023-06-01`. Ollama uses `/api/generate`; private Ollama hosting needs a purpose-built deployment policy before enabling it—the generic integration guard blocks private targets. The local rule-based assistant always works without credentials.

## Friday

Set `FRIDAY_URL` on the server, then use exactly that URL in the Friday connector. Default: `http://localhost:18080`. No credential is required for a trusted local daemon unless you have added authentication.

- List spaces: `GET /api/workspaces` through the connector read console or Copilot.
- Chat: Copilot forwards a workspace chat and streams SSE.
- Trigger a workflow: propose `POST /api/workspaces/SPACE/signals/SIGNAL` with `{"payload":{...}}`.
- Track a session: read `/api/sessions/SESSION`.
- Cancel: propose `DELETE /api/sessions/SESSION` and approve before executing.

Signal payloads come from the daemon's space definitions. See [Friday integration guide](https://docs.hellofriday.ai/guides/integrate-with-your-app). Choose Stream workflow on an approved Friday signal to stream its progress. Standard Execute returns the completed JSON result. Configure a workspace ID and event signal IDs in the connector to queue proposals for spec approval, PR creation, failed tests or over-capacity checks.

## Any future HTTP integration

Add a custom connector, assign an exact base URL, configure its host allowlist, choose authentication and allowed roles. Reads use a relative path on that host. Writes use a reviewed JSON action. OAuth2 client credentials requests use `tokenPath`, `clientId`, a vault reference for the client secret and `scope`; the server obtains a fresh access token for each operation without returning it to the UI. Token URL and API URL must share the configured host. Interactive OAuth consent is a separate future adapter.
