# Integration recipes

All provider keys live in Integration Hub. They are separate from platform login credentials. Give tokens only the scopes necessary for intended operations. Saving or connecting a connector automatically approves its `baseUrl` host; `INTEGRATION_HOSTS` is an optional bootstrap list only.

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

## Diagnosing HTTP 401 and 403

Request Studio and the API Explorer display the request ID returned by Spring Boot. Search for that ID in the IntelliJ Run console. Outbound attempts log at INFO by default, with warnings for rejections and upstream errors. Set `APIINTEL_LOG_LEVEL=DEBUG` in your IntelliJ run configuration to include successful platform GETs.

| Console event | What to check |
| --- | --- |
| `platform_request status=401 reason=platform_session_required` | Sign back into the platform. The request stopped at Spring Security. |
| `platform_request status=401 reason=login_rejected` | Check the platform account and the password configured on the running instance. |
| `platform_request status=403 reason=csrf_missing` or `csrf_invalid` | Reload or sign in again, then retry. |
| `upstream_not_sent` | Check the indicated validation stage, destination, or credential profile. |
| `upstream_result status=401` | The remote server responded. Match the earlier `upstream_start` line to see the authentication scheme, credential-header presence, and whether a profile was attached. Check the token's expiry, audience, and target environment. |
| `upstream_result status=403` | Check upstream token scopes and user or service account permissions. |

A contract with no security declaration can still describe an API that requires credentials. In **Try it out**, choose **Credential override for undocumented authentication**, select Bearer, Basic, or API key header, and select the saved environment and variable. The environment must allow the exact target hostname. Browser cookies are intentionally excluded by the guarded executor. The logs omit raw URL paths, query strings, header values, request or response bodies, and secrets.

## AI Tools company endpoints

AI Copilot displays Microsoft Copilot, Devin, GitHub Copilot, and Stylus tabs by default, plus any additional configured AI providers. Configure a company endpoint under the tab or Integration Hub. This feature uses company-provided endpoints and does not select or contact public vendor APIs automatically. GitHub Copilot connections are independent of the GitHub repository connector.

- Save the credential in the existing vault and enter its reference in AI Tools. With AUTHORIZATION, store the full header value (for example, Bearer followed by the token). With BEARER, store the token itself. AI Tools does not load existing secret values.
- Configure a full URL, POST JSON template containing `{{prompt}}`, and JSON pointer or SSE response mapping. GET retrieves a configured response without a prompt or context. Use a same-host GET probe for connection tests. Without a probe, POST connectivity checks require explicit permission to send a synthetic prompt.
- Additional headers are a JSON object. Public protocol headers such as Accept and Content-Type can use literal values. Use `{{vault:reference-name}}` for custom headers. Authorization is handled by the selected auth mode. Browser cookies and transport headers are not forwarded.
- Preview the data before sending. Choose the current OpenAPI revision, a selected operation, or up to ten saved test summaries. The confirmation is single-use and expires after three minutes. It is invalid if the request, revision, or connection changes. Test response bodies and common credential/example fields are excluded. Review descriptions and your prompt for anything sensitive.
- Enable the approval option for company endpoints that create tasks. A confirmed POST enters Integration Hub's approval queue and makes no outbound call until approved and executed. Configure a read-only probe for these endpoints.
- Stylus accepts direct HTTP setup or redacted cURL templates. For example, use `-H 'Authorization: Bearer {{vault:stylus-token}}'`. Import parses the HTTP settings and never runs Bash. Export produces vault placeholders. File arguments, shell substitutions, and literal authorization values are rejected.

Keep `INTEGRATION_HOSTS` and, where applicable, `INTEGRATION_PRIVATE_HOSTS` configured for your approved destination. Request IDs connect connection tests and execution errors to the backend console and Admin usage history. The feature works with the embedded SQLite default and the explicit H2 configuration.
