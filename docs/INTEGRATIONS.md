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

1. Add the Jira hostname to `INTEGRATION_HOSTS`. Use the exact Jira URL, including any Data Center context path.
2. For Jira Cloud, choose `JIRA_TOKEN`, enter your Atlassian account email, and paste the profile API token in the credential field. Use `https://YOUR_INSTANCE.atlassian.net` for an unscoped token or `https://api.atlassian.com/ex/jira/CLOUD_ID` for a scoped token. For Data Center, select that deployment and use `BEARER` with the personal access token.
3. Save and enable the connector. In Jira, choose it, click **Test Jira connection**, then run a JQL query. The connection check calls `myself`; search uses `/rest/api/3/search/jql` on Cloud and `/rest/api/2/search` on Data Center.

Existing Cloud connections configured with Basic and an encrypted `email:api_token` still work. To create a Test issue, propose the matching version's `/issue` action through the approval queue.

## Configurable AI request

In Integration Hub, create or edit a connector for Friday, Stylus, Copilot or another provider and enable **Use a configurable AI request**. Paste the full HTTPS API URL. Choose `AUTHORIZATION` and paste the complete header **value**, for example `Bearer <token>`, into the credential field. The value is stored in the encrypted vault.

Copy the request's JSON payload shape into the template. Replace the message text with `{{prompt}}` and remove any credentials from the URL and payload. For example:

```json
{"messages":[{"role":"user","content":"{{prompt}}"}],"stream":false}
```

Set a JSON pointer for the reply, such as `/choices/0/message/content`. For SSE, select the SSE format and supply the pointer to each text delta, such as `/choices/0/delta/content`. Save and enable the connection, then reopen it and click **Test saved AI connection**. The same connection can be selected in AI Copilot and, when enabled there, in Test Library.

Use a request the service permits from a server client. A browser token can expire or depend on cookies, device state, or additional headers. A compatible API or approved gateway is required when URL, Authorization, and JSON payload are insufficient.

## OpenAI-compatible / Azure / Anthropic / Ollama

Specify the provider's base URL, model and credentials. OpenAI-compatible chat uses `/v1/chat/completions`. Azure uses the deployment-specific path and API version in `chatPath`, with `API_KEY` authentication and `api-key` header. Anthropic uses `x-api-key`, `/v1/messages`, and `anthropic-version: 2023-06-01`. Ollama uses `/api/generate`. For private HTTPS AI or Jira hosts, configure both `INTEGRATION_HOSTS` and `INTEGRATION_PRIVATE_HOSTS` and restart the backend. The local rule-based assistant always works without credentials.

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

## Provider presets and AI artifact review

Integration Hub includes presets for Microsoft 365 Copilot gateways, internal Stylus gateways, Devin sessions, OpenAI-compatible APIs, Anthropic Messages, and custom AI HTTP endpoints. Configure the exact approved destination and store credentials in the vault. Copilot and Stylus presets expect a compatible gateway. Devin tasks use the session action and approval queue.

AI Copilot can generate editable contract suggestions, Mermaid diagrams, and Jira story drafts. Choose an enabled provider, inspect the selected contract revision and destination, and confirm sending the context. For diagrams and stories, operation scope includes the selected operation and shared components. Review records are saved locally and are visible to their creator. Applying a contract suggestion creates a new DRAFT revision, regenerates the linked artifacts, and clears the previous approval. Applying a diagram or story saves a local record. From a reviewed story, you can propose a Jira issue separately for approval. Test Library has its own reviewed test package flow.
